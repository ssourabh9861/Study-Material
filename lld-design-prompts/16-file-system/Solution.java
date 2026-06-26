/* =============================================================================
 *  In-Memory File System  —  single-file LLD review / revision artifact
 *  (Read-and-revise: complete & correct-by-inspection; not meant to be run in prod.)
 * =============================================================================
 *
 *  CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 *  ----------------------------------------------------------
 *  Functional scope:
 *    1. Which commands? (mkdir, ls, writeFile, readFile, cd, pwd minimum; also mv/cp/rm -r/find?)
 *    2. Absolute paths only (LeetCode 588) or relative paths + CWD with '.' and '..'?
 *    3. ls semantics: file -> [name]; directory -> children sorted lexicographically?
 *    4. mkdir create intermediate dirs (-p) or fail if parent missing?
 *    5. File content: opaque String? byte[]? append vs overwrite vs seek?
 *    6. Conflict policy: file-vs-dir name clash, mv onto existing, rm non-empty dir?
 *  Non-functional:
 *    7. Concurrency: single-threaded toy or thread-safe many readers/writers?
 *    8. Scale: thousands vs millions of entries (per-dir O(1) lookup, memory)?
 *    9. find/search latency: full walk OK or need an index?
 *   10. Case sensitivity: Unix (sensitive) or Windows (insensitive)?
 *  Scope-narrowing / extensions:
 *   11. Permissions / ownership (rwx, users, groups)?
 *   12. Symbolic / hard links? (tree -> graph; cycle handling)
 *   13. Persistence / snapshot+restore, or just a clean seam?
 *   14. Quotas / max file size / max path length?
 *   15. Change notifications / watch API?
 *   16. Undo/redo of mutating operations?
 *
 *  CHOSEN ASSUMPTIONS (what this file builds):
 *    - Relative paths + CWD with '.'/'..'; mkdir -p; String content (append+overwrite);
 *      recursive rm; glob/predicate find; lexicographic ls; case-sensitive names.
 *    - Thread-safe via a single ReentrantReadWriteLock (shared reads / exclusive writes).
 *    - Designed-for (lightly coded): PermissionSet on entries, SymbolicLink + cycle-safe
 *      resolver, ExportVisitor persistence seam, Command-based undo.
 *
 *  PATTERNS DEMONSTRATED:
 *    Composite (Entry/File/Directory) | Visitor (Size/Find/Export/Copy)
 *    Strategy (SearchPredicate) | Facade + (optional) Singleton (FileSystem)
 *    Command (undo) | Iterator (DFS) | Value object (Path)
 * =============================================================================
 */

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Solution {

    /* =========================================================================
     * Exceptions
     * ========================================================================= */
    static class FsException extends RuntimeException { FsException(String m){ super(m);} }
    static class NoSuchPathException extends FsException { NoSuchPathException(String m){ super(m);} }
    static class PathAlreadyExistsException extends FsException { PathAlreadyExistsException(String m){ super(m);} }
    static class NotADirectoryException extends FsException { NotADirectoryException(String m){ super(m);} }
    static class IsADirectoryException extends FsException { IsADirectoryException(String m){ super(m);} }
    static class InvalidPathException extends FsException { InvalidPathException(String m){ super(m);} }
    static class AccessDeniedException extends FsException { AccessDeniedException(String m){ super(m);} }
    static class TooManyLinksException extends FsException { TooManyLinksException(String m){ super(m);} }

    /* =========================================================================
     * Permissions (extension; enforced by a guard at the facade, not by entities)
     * ========================================================================= */
    static final class PermissionSet {
        boolean read = true, write = true, execute = true;
        PermissionSet() {}
        PermissionSet(boolean r, boolean w, boolean x){ read=r; write=w; execute=x; }
        @Override public String toString(){
            return "" + (read?"r":"-") + (write?"w":"-") + (execute?"x":"-");
        }
    }

    /* =========================================================================
     * Visitor (double-dispatch). Operations over the tree live here, not on entities.
     * ========================================================================= */
    interface Visitor<R> {
        R visitFile(File f);
        R visitDirectory(Directory d);
        R visitLink(SymbolicLink l);
    }

    /* =========================================================================
     * Composite: Entry (component), File (leaf), Directory (composite), SymbolicLink.
     * ========================================================================= */
    static abstract class Entry {
        protected String name;
        protected Directory parent;            // null only for root
        protected final Instant createdAt;
        protected Instant modifiedAt;
        protected String owner = "root";
        protected final PermissionSet permissions = new PermissionSet();

        Entry(String name) {
            this.name = name;
            this.createdAt = Instant.now();
            this.modifiedAt = this.createdAt;
        }

        String getName() { return name; }
        Directory getParent() { return parent; }
        Instant getCreatedAt() { return createdAt; }
        Instant getModifiedAt() { return modifiedAt; }
        void touch() { this.modifiedAt = Instant.now(); }

        boolean isDirectory() { return false; }
        boolean isLink() { return false; }

        /** Absolute path from root, e.g. "/a/b/c.txt". Root returns "/". */
        String getPath() {
            if (parent == null) return "/";               // root
            Deque<String> parts = new ArrayDeque<>();
            Entry cur = this;
            while (cur != null && cur.parent != null) {    // stop before root
                parts.addFirst(cur.name);
                cur = cur.parent;
            }
            return "/" + String.join("/", parts);
        }

        abstract <R> R accept(Visitor<R> v);
    }

    static final class File extends Entry {
        private final StringBuilder content = new StringBuilder();

        File(String name) { super(name); }

        String read() { return content.toString(); }

        void write(String data) {                          // overwrite
            content.setLength(0);
            content.append(data == null ? "" : data);
            touch();
        }

        void append(String data) {
            if (data != null) content.append(data);
            touch();
        }

        /** Size in bytes (chars here as a stand-in for content length). */
        long size() { return content.length(); }

        @Override <R> R accept(Visitor<R> v) { return v.visitFile(this); }
    }

    static final class Directory extends Entry {
        // TreeMap -> children iterate in lexicographic order (drives sorted ls()).
        private final NavigableMap<String, Entry> children = new TreeMap<>();

        Directory(String name) { super(name); }

        @Override boolean isDirectory() { return true; }

        Entry getChild(String name) { return children.get(name); }

        void addChild(Entry e) {
            if (children.containsKey(e.name))
                throw new PathAlreadyExistsException("already exists: " + e.name + " in " + getPath());
            e.parent = this;
            children.put(e.name, e);
            touch();
        }

        Entry removeChild(String name) {
            Entry e = children.remove(name);
            if (e != null) { e.parent = null; touch(); }
            return e;
        }

        /** Rename a child in place (key change within the same directory). */
        void renameChild(String oldName, String newName) {
            Entry e = children.remove(oldName);
            if (e == null) throw new NoSuchPathException("no child: " + oldName);
            if (children.containsKey(newName))
                throw new PathAlreadyExistsException("target exists: " + newName);
            e.name = newName;
            children.put(newName, e);
            touch();
        }

        List<String> list() { return new ArrayList<>(children.keySet()); }   // already sorted
        Collection<Entry> children() { return children.values(); }
        boolean isEmpty() { return children.isEmpty(); }

        @Override <R> R accept(Visitor<R> v) { return v.visitDirectory(this); }
    }

    static final class SymbolicLink extends Entry {       // extension
        private final String targetPath;
        SymbolicLink(String name, String targetPath) { super(name); this.targetPath = targetPath; }
        String getTarget() { return targetPath; }
        @Override boolean isLink() { return true; }
        @Override <R> R accept(Visitor<R> v) { return v.visitLink(this); }
    }

    /* =========================================================================
     * Path — immutable value object. All parsing/normalization lives here.
     * ========================================================================= */
    static final class Path {
        private final List<String> segments;   // normalized: no "", no "."
        private final boolean absolute;

        private Path(List<String> segments, boolean absolute) {
            this.segments = Collections.unmodifiableList(segments);
            this.absolute = absolute;
        }

        static Path parse(String raw) {
            if (raw == null) throw new InvalidPathException("null path");
            boolean abs = raw.startsWith("/");
            List<String> segs = new ArrayList<>();
            for (String part : raw.split("/")) {
                if (part.isEmpty() || part.equals(".")) continue;   // skip "" and "."
                // ".." kept here; resolver applies it. Reject illegal names.
                segs.add(part);
            }
            return new Path(segs, abs);
        }

        boolean isAbsolute() { return absolute; }
        List<String> segments() { return segments; }
        boolean isEmpty() { return segments.isEmpty(); }
        String leaf() { return segments.isEmpty() ? null : segments.get(segments.size() - 1); }

        /** Path without its last segment (the would-be parent). */
        Path parent() {
            if (segments.isEmpty()) return this;
            return new Path(segments.subList(0, segments.size() - 1), absolute);
        }
    }

    /* =========================================================================
     * PathResolver — turns Path + CWD into an Entry (or its parent). Single
     * Responsibility: all walking, '..' handling and symlink-following lives here.
     * ========================================================================= */
    static final class PathResolver {
        private static final int MAX_LINK_HOPS = 20;
        private final Directory root;
        PathResolver(Directory root) { this.root = root; }

        /** Resolve a path to an existing entry; throws if any segment missing. */
        Entry resolve(Path path, Directory cwd) {
            Directory base = path.isAbsolute() ? root : cwd;
            Entry cur = base;
            for (String seg : path.segments()) {
                cur = step(cur, seg);
            }
            return cur;
        }

        /** Resolve the PARENT directory of a path, creating intermediates when create=true (mkdir -p). */
        Directory resolveParent(Path path, Directory cwd, boolean createIntermediate) {
            Directory base = path.isAbsolute() ? root : cwd;
            Entry cur = base;
            List<String> segs = path.segments();
            for (int i = 0; i < segs.size() - 1; i++) {       // stop before leaf
                String seg = segs.get(i);
                if (seg.equals("..")) { cur = parentOf(cur); continue; }
                if (!(cur instanceof Directory))
                    throw new NotADirectoryException("not a directory: " + cur.getPath());
                Directory dir = (Directory) cur;
                Entry next = dir.getChild(seg);
                if (next == null) {
                    if (!createIntermediate)
                        throw new NoSuchPathException("missing intermediate dir: " + seg);
                    Directory created = new Directory(seg);
                    dir.addChild(created);
                    next = created;
                }
                cur = next;
            }
            if (!(cur instanceof Directory))
                throw new NotADirectoryException("parent is not a directory: " + cur.getPath());
            return (Directory) cur;
        }

        private Entry step(Entry cur, String seg) {
            if (seg.equals("..")) return parentOf(cur);
            if (!(cur instanceof Directory))
                throw new NotADirectoryException("not a directory: " + cur.getPath());
            Entry next = ((Directory) cur).getChild(seg);
            if (next == null)
                throw new NoSuchPathException("no such path segment: " + seg);
            return next;
        }

        private Entry parentOf(Entry cur) {
            // ".." above root clamps to root (Unix behavior).
            return (cur.getParent() == null) ? root : cur.getParent();
        }

        /** Follow a chain of symbolic links to a concrete entry, with a hop cap (ELOOP guard). */
        Entry resolveFollowingLinks(Path path, Directory cwd) {
            Entry e = resolve(path, cwd);
            int hops = 0;
            while (e instanceof SymbolicLink) {
                if (++hops > MAX_LINK_HOPS)
                    throw new TooManyLinksException("symlink cycle / too many hops");
                e = resolve(Path.parse(((SymbolicLink) e).getTarget()), cwd);
            }
            return e;
        }
    }

    /* =========================================================================
     * Strategy — pluggable, composable match rules used by FindVisitor.
     * ========================================================================= */
    interface SearchPredicate {
        boolean test(Entry e);
        default SearchPredicate and(SearchPredicate o){ return e -> this.test(e) && o.test(e); }
        default SearchPredicate or (SearchPredicate o){ return e -> this.test(e) || o.test(e); }

        static SearchPredicate byName(String exact){ return e -> e.getName().equals(exact); }
        static SearchPredicate glob(String pattern){
            Pattern p = Pattern.compile(globToRegex(pattern));
            return e -> p.matcher(e.getName()).matches();
        }
        static SearchPredicate regex(String regex){
            Pattern p = Pattern.compile(regex);
            return e -> p.matcher(e.getName()).matches();
        }
        static SearchPredicate largerThan(long bytes){
            return e -> (e instanceof File) && ((File) e).size() > bytes;
        }
        static SearchPredicate modifiedAfter(Instant t){ return e -> e.getModifiedAt().isAfter(t); }
        static SearchPredicate filesOnly(){ return e -> e instanceof File; }
        static SearchPredicate dirsOnly(){ return e -> e instanceof Directory; }

        static String globToRegex(String glob){
            StringBuilder sb = new StringBuilder();
            for (char c : glob.toCharArray()) {
                switch (c) {
                    case '*': sb.append(".*"); break;
                    case '?': sb.append('.'); break;
                    case '.': sb.append("\\."); break;
                    default:  sb.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return sb.toString();
        }
    }

    /* =========================================================================
     * Concrete Visitors
     * ========================================================================= */

    /** Recursive byte size; dedupes hard-linked Files by identity. */
    static final class SizeVisitor implements Visitor<Long> {
        private final Set<Entry> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        @Override public Long visitFile(File f) {
            return visited.add(f) ? f.size() : 0L;          // count each underlying file once
        }
        @Override public Long visitDirectory(Directory d) {
            long total = 0;
            for (Entry c : d.children()) total += c.accept(this);
            return total;
        }
        @Override public Long visitLink(SymbolicLink l) { return 0L; } // links don't add target size
    }

    /** Collects entries (paths) matching a Strategy. Does NOT follow links (cycle-safe). */
    static final class FindVisitor implements Visitor<Void> {
        private final SearchPredicate predicate;
        private final List<Entry> matches = new ArrayList<>();
        FindVisitor(SearchPredicate p){ this.predicate = p; }
        List<Entry> result(){ return matches; }

        @Override public Void visitFile(File f){ if (predicate.test(f)) matches.add(f); return null; }
        @Override public Void visitLink(SymbolicLink l){ if (predicate.test(l)) matches.add(l); return null; }
        @Override public Void visitDirectory(Directory d){
            if (predicate.test(d)) matches.add(d);
            for (Entry c : d.children()) c.accept(this);    // recurse
            return null;
        }
    }

    /** Flattens the tree into (path -> serialized record): persistence seam (SRP). */
    static final class ExportVisitor implements Visitor<Void> {
        final Map<String, String> dump = new LinkedHashMap<>();
        @Override public Void visitFile(File f){
            dump.put(f.getPath(), "FILE:" + f.read()); return null;
        }
        @Override public Void visitLink(SymbolicLink l){
            dump.put(l.getPath(), "LINK:" + l.getTarget()); return null;
        }
        @Override public Void visitDirectory(Directory d){
            dump.put(d.getPath(), "DIR");
            for (Entry c : d.children()) c.accept(this);
            return null;
        }
    }

    /** Deep clone of a subtree (Composite copy). Used by cp. */
    static final class CopyVisitor implements Visitor<Entry> {
        @Override public Entry visitFile(File f){
            File copy = new File(f.getName());
            copy.write(f.read());
            return copy;
        }
        @Override public Entry visitLink(SymbolicLink l){
            return new SymbolicLink(l.getName(), l.getTarget());
        }
        @Override public Entry visitDirectory(Directory d){
            Directory copy = new Directory(d.getName());
            for (Entry c : d.children()) copy.addChild(c.accept(this));
            return copy;
        }
    }

    /* =========================================================================
     * Command — reversible mutations for undo (extension).
     * ========================================================================= */
    interface Command { void execute(); void undo(); }

    /* =========================================================================
     * FileSystem — Facade + (optional) Singleton. Owns root, resolver, locking,
     * undo stack, CWD. Single intention-revealing API; the seam for permissions,
     * persistence and concurrency-strategy swaps.
     * ========================================================================= */
    static final class FileSystem {
        private final Directory root = new Directory("/");
        private final PathResolver resolver = new PathResolver(root);
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final Deque<Command> undoStack = new ArrayDeque<>();
        private Directory cwd = root;                      // simple single-session CWD

        // ---- Singleton convenience (DI still primary; constructor is public) ----
        private static volatile FileSystem DEFAULT;
        public FileSystem() {}
        public static FileSystem getDefault() {
            if (DEFAULT == null) {
                synchronized (FileSystem.class) {
                    if (DEFAULT == null) DEFAULT = new FileSystem();
                }
            }
            return DEFAULT;
        }

        // ---------- read helpers (shared lock) ----------
        private <T> T read(java.util.function.Supplier<T> body) {
            lock.readLock().lock();
            try { return body.get(); } finally { lock.readLock().unlock(); }
        }
        private void write(Runnable body) {
            lock.writeLock().lock();
            try { body.run(); } finally { lock.writeLock().unlock(); }
        }

        private void checkWrite(Entry e) {
            if (e != null && !e.permissions.write)
                throw new AccessDeniedException("write denied: " + e.getPath());
        }
        private void checkRead(Entry e) {
            if (e != null && !e.permissions.read)
                throw new AccessDeniedException("read denied: " + e.getPath());
        }

        // ---------- navigation ----------
        public String pwd() { return read(() -> cwd.getPath()); }

        public void cd(String path) {
            write(() -> {
                Entry e = resolver.resolve(Path.parse(path), cwd);
                if (!(e instanceof Directory))
                    throw new NotADirectoryException("cd: not a directory: " + path);
                cwd = (Directory) e;
            });
        }

        // ---------- mkdir -p ----------
        public void mkdir(String path) {
            write(() -> {
                Path p = Path.parse(path);
                if (p.isEmpty()) throw new InvalidPathException("mkdir: empty path");
                Directory parent = resolver.resolveParent(p, cwd, true);
                String leaf = p.leaf();
                Entry existing = parent.getChild(leaf);
                if (existing instanceof Directory) return;     // idempotent
                if (existing != null)
                    throw new PathAlreadyExistsException("mkdir: file exists: " + path);
                checkWrite(parent);
                Directory created = new Directory(leaf);
                parent.addChild(created);
                undoStack.push(new Command() {
                    public void execute() { parent.addChild(created); }
                    public void undo()    { parent.removeChild(leaf); }
                });
            });
        }

        // ---------- ls ----------
        public List<String> ls(String path) {
            return read(() -> {
                Entry e = resolver.resolve(Path.parse(path), cwd);
                checkRead(e);
                if (e instanceof Directory) return ((Directory) e).list();
                return Collections.singletonList(e.getName());  // file -> just its name
            });
        }

        // ---------- file content ----------
        public void writeFile(String path, String content) {
            write(() -> {
                File f = touchFile(path);
                checkWrite(f);
                String prev = f.read();
                f.write(content);
                undoStack.push(new Command() {
                    public void execute() { f.write(content); }
                    public void undo()    { f.write(prev); }
                });
            });
        }

        public void appendFile(String path, String content) {
            write(() -> {
                File f = touchFile(path);
                checkWrite(f);
                int prevLen = f.read().length();
                f.append(content);
                undoStack.push(new Command() {
                    public void execute() { f.append(content); }
                    public void undo()    { f.write(f.read().substring(0, prevLen)); }
                });
            });
        }

        public String readFile(String path) {
            return read(() -> {
                Entry e = resolver.resolve(Path.parse(path), cwd);
                if (e instanceof Directory) throw new IsADirectoryException("readFile: is a directory: " + path);
                checkRead(e);
                return ((File) e).read();
            });
        }

        /** Resolve-or-create a File at path (with -p parents). Caller holds write lock. */
        private File touchFile(String path) {
            Path p = Path.parse(path);
            if (p.isEmpty()) throw new InvalidPathException("empty file path");
            Directory parent = resolver.resolveParent(p, cwd, true);
            String leaf = p.leaf();
            Entry e = parent.getChild(leaf);
            if (e == null) {
                File f = new File(leaf);
                parent.addChild(f);
                return f;
            }
            if (e instanceof Directory) throw new IsADirectoryException("is a directory: " + path);
            return (File) e;
        }

        // ---------- mv (rename + reparent), cycle-safe ----------
        public void mv(String src, String dst) {
            write(() -> {
                Path sp = Path.parse(src), dp = Path.parse(dst);
                Entry node = resolver.resolve(sp, cwd);
                if (node.getParent() == null) throw new FsException("cannot move root");
                Directory dstParent = resolver.resolveParent(dp, cwd, true);
                String dstName = dp.leaf();

                // cycle guard: cannot move a directory into its own subtree
                if (node instanceof Directory) {
                    for (Entry a = dstParent; a != null; a = a.getParent())
                        if (a == node) throw new FsException("mv: cannot move into own subtree");
                }
                if (dstParent.getChild(dstName) != null)
                    throw new PathAlreadyExistsException("mv: target exists: " + dst);

                Directory srcParent = node.getParent();
                String srcName = node.getName();
                checkWrite(srcParent); checkWrite(dstParent);

                srcParent.removeChild(srcName);
                node.name = dstName;
                dstParent.addChild(node);

                undoStack.push(new Command() {
                    public void execute() {
                        srcParent.removeChild(srcName); node.name = dstName; dstParent.addChild(node);
                    }
                    public void undo() {
                        dstParent.removeChild(dstName); node.name = srcName; srcParent.addChild(node);
                    }
                });
            });
        }

        // ---------- cp (deep copy via CopyVisitor) ----------
        public void cp(String src, String dst) {
            write(() -> {
                Entry node = resolver.resolve(Path.parse(src), cwd);
                Path dp = Path.parse(dst);
                Directory dstParent = resolver.resolveParent(dp, cwd, true);
                String dstName = dp.leaf();
                if (dstParent.getChild(dstName) != null)
                    throw new PathAlreadyExistsException("cp: target exists: " + dst);
                checkWrite(dstParent);

                Entry copy = node.accept(new CopyVisitor());   // deep clone
                copy.name = dstName;
                dstParent.addChild(copy);

                undoStack.push(new Command() {
                    public void execute() { dstParent.addChild(copy); }
                    public void undo()    { dstParent.removeChild(dstName); }
                });
            });
        }

        // ---------- rm (recursive) ----------
        public void rm(String path) {
            write(() -> {
                Entry node = resolver.resolve(Path.parse(path), cwd);
                if (node.getParent() == null) throw new FsException("cannot remove root");
                Directory parent = node.getParent();
                String name = node.getName();
                checkWrite(parent);
                parent.removeChild(name);                       // whole subtree detaches with it
                undoStack.push(new Command() {
                    public void execute() { parent.removeChild(name); }
                    public void undo()    { parent.addChild(node); }   // node still intact in memory
                });
            });
        }

        // ---------- du (size via Visitor) ----------
        public long du(String path) {
            return read(() -> resolver.resolve(Path.parse(path), cwd).accept(new SizeVisitor()));
        }

        // ---------- find (Visitor + Strategy) ----------
        public List<String> find(String base, SearchPredicate predicate) {
            return read(() -> {
                Entry start = resolver.resolve(Path.parse(base), cwd);
                FindVisitor v = new FindVisitor(predicate);
                start.accept(v);
                return v.result().stream().map(Entry::getPath).sorted().collect(Collectors.toList());
            });
        }

        // ---------- symlink (extension) ----------
        public void ln(String linkPath, String targetPath) {
            write(() -> {
                Path lp = Path.parse(linkPath);
                Directory parent = resolver.resolveParent(lp, cwd, true);
                if (parent.getChild(lp.leaf()) != null)
                    throw new PathAlreadyExistsException("ln: exists: " + linkPath);
                parent.addChild(new SymbolicLink(lp.leaf(), targetPath));
            });
        }

        public String readLink(String linkPath) {
            return read(() -> resolver.resolveFollowingLinks(Path.parse(linkPath), cwd) instanceof File f
                    ? f.read() : "<dir>");
        }

        // ---------- persistence seam (ExportVisitor) ----------
        public Map<String, String> export() {
            return read(() -> { ExportVisitor v = new ExportVisitor(); root.accept(v); return v.dump; });
        }

        // ---------- undo ----------
        public void undo() {
            write(() -> { if (!undoStack.isEmpty()) undoStack.pop().undo(); });
        }
    }

    /* =========================================================================
     * Demo — illustrates key scenarios (for the reader; not a test harness).
     * ========================================================================= */
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        System.out.println("== mkdir -p, ls, cd, pwd ==");
        fs.mkdir("/a/b/c");
        fs.mkdir("/a/d");
        System.out.println("ls /a        -> " + fs.ls("/a"));        // [b, d]
        fs.cd("/a/b");
        System.out.println("pwd          -> " + fs.pwd());           // /a/b
        fs.mkdir("e");                                               // relative under /a/b
        System.out.println("ls /a/b      -> " + fs.ls("/a/b"));      // [c, e]
        fs.cd("..");
        System.out.println("pwd after .. -> " + fs.pwd());           // /a

        System.out.println("\n== files: write/append/read, ls-on-file ==");
        fs.writeFile("/a/b/notes.txt", "hello");
        fs.appendFile("/a/b/notes.txt", " world");
        System.out.println("read         -> " + fs.readFile("/a/b/notes.txt")); // hello world
        System.out.println("ls file      -> " + fs.ls("/a/b/notes.txt"));       // [notes.txt]

        System.out.println("\n== du (SizeVisitor) ==");
        fs.writeFile("/a/big.log", "1234567890");
        System.out.println("du /a        -> " + fs.du("/a") + " bytes"); // 11 + 10 = 21

        System.out.println("\n== find (Visitor + Strategy) ==");
        fs.writeFile("/a/x/server.log", "log data here....");
        System.out.println("*.log        -> " + fs.find("/", SearchPredicate.glob("*.log")));
        System.out.println("*.log & >5b  -> " +
                fs.find("/", SearchPredicate.glob("*.log").and(SearchPredicate.largerThan(5))));

        System.out.println("\n== mv (rename+reparent) & cp (deep copy) ==");
        fs.mv("/a/big.log", "/a/d/renamed.log");
        System.out.println("ls /a/d      -> " + fs.ls("/a/d"));      // [renamed.log]
        fs.cp("/a/b", "/a/b-copy");
        System.out.println("ls /a/b-copy -> " + fs.ls("/a/b-copy")); // deep clone of b
        System.out.println("copy read    -> " + fs.readFile("/a/b-copy/notes.txt"));

        System.out.println("\n== rm (recursive) ==");
        fs.rm("/a/b-copy");
        System.out.println("ls /a        -> " + fs.ls("/a"));

        System.out.println("\n== symbolic link (extension) ==");
        fs.ln("/a/link-to-notes", "/a/b/notes.txt");
        System.out.println("readLink     -> " + fs.readLink("/a/link-to-notes"));

        System.out.println("\n== undo (Command) ==");
        fs.writeFile("/a/temp.txt", "v1");
        fs.writeFile("/a/temp.txt", "v2");
        System.out.println("before undo  -> " + fs.readFile("/a/temp.txt")); // v2
        fs.undo();
        System.out.println("after undo   -> " + fs.readFile("/a/temp.txt")); // v1

        System.out.println("\n== export (persistence seam) ==");
        fs.export().forEach((k, v) -> System.out.println("  " + k + " => " + v));

        System.out.println("\n== edge cases ==");
        safe("rm root",   () -> fs.rm("/"));
        safe("read dir",  () -> fs.readFile("/a"));
        safe("cd file",   () -> fs.cd("/a/b/notes.txt"));
        safe("missing",   () -> fs.readFile("/nope/x"));
        safe("mv->self",  () -> fs.mv("/a", "/a/b/loop"));
    }

    /** Helper to print expected exceptions in the demo. */
    private static void safe(String label, Runnable r) {
        try { r.run(); System.out.println(label + ": (no error)"); }
        catch (FsException e) { System.out.println(label + ": " + e.getClass().getSimpleName() + " - " + e.getMessage()); }
    }
}
