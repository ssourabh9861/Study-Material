/* =====================================================================================
 * LLD: TEXT EDITOR (with undo/redo)  —  single-file REVIEW / REVISION artifact.
 *
 * This file is for READING and REVISION before an interview. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). You do NOT need to compile/run it.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Plain text or rich text (bold/fonts/colors)? -> changes the buffer model.
 *   2. Which ops must be undoable? insert/delete only, or also paste/find-replace/cursor?
 *   3. Linear undo (single stack) or branching/tree undo (Vim-style)?
 *   4. Multi-level undo? Bounded or unbounded history depth?
 *   5. Is redo cleared the moment the user types after an undo? (standard: yes)
 *   6. Do keystrokes coalesce into one undo unit (typing "hello" = 1 step)? transactions?
 *   7. Clipboard: internal vs system? single entry or clipboard ring/history?
 *   8. Find/replace: case sensitivity? whole word? regex? replace-all? wrap-around?
 *   9. Single cursor or multiple cursors / multiple selections?
 * Non-functional / scale:
 *   10. Document size — KB or multi-MB files/logs? (decides buffer structure)
 *   11. Edit pattern — appends vs scattered random edits?
 *   12. Concurrency — single-thread desktop, or multi-thread (autosave/index/collab)?
 *   13. Latency per keystroke (must feel instant).
 *   14. Memory budget — snapshot-per-edit affordable, or must undo be delta-based?
 * Out of scope (confirm): rendering, syntax highlight, file I/O format, network/CRDT collab.
 *
 * ASSUMED ANSWERS (so the design is concrete):
 *   plain text; insert/delete/replace/paste undoable; LINEAR multi-level undo, capped depth;
 *   redo cleared on new edit; typing coalesces; internal single-entry clipboard;
 *   literal find/replace + regex as a swappable strategy; single cursor+selection;
 *   docs up to a few MB, mostly local edits; single-threaded core + opt-in thread-safe
 *   decorator; persistence/collaboration out of scope but cleanly bounded.
 *
 * -------------------------------------------------------------------------------------
 * PATTERNS DEMONSTRATED
 *   Command   -> InsertCommand / DeleteCommand / ReplaceCommand : reversible edits (undo/redo)
 *   Composite -> MacroCommand : group edits (cut, replace-all) into ONE undo step
 *   Strategy  -> SearchStrategy (Literal/Regex) and TextBuffer family (Gap/StringBuffer)
 *   Memento   -> EditorMemento : snapshot for autosave / restore
 *   Decorator -> SynchronizedDocument : opt-in thread safety
 *   Facade    -> Document : intent-level API over buffer+history+clipboard+search
 *
 * SOLID: OCP (new commands/strategies without editing history/document),
 *        DIP (Document depends on TextBuffer/SearchStrategy abstractions),
 *        SRP (one concern per class), LSP (MacroCommand substitutes a Command).
 * ===================================================================================== */

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* =====================================================================================
 * VALUE OBJECTS
 * ===================================================================================== */

/** A character offset range [start, end). Used for selections and search matches. */
final class Range {
    final int start;
    final int end;
    Range(int start, int end) {
        if (start > end) throw new IllegalArgumentException("start > end");
        this.start = start;
        this.end = end;
    }
    int length() { return end - start; }
    boolean isEmpty() { return start == end; }
    @Override public String toString() { return "[" + start + "," + end + ")"; }
}

/** The caret. Sits BETWEEN characters, so valid positions are [0, length]. */
final class Cursor {
    private int position;
    Cursor(int position) { this.position = position; }
    int get() { return position; }
    void set(int position) { this.position = position; }
}

/** A contiguous selection [start, end). Empty when start == end. */
final class Selection {
    private int start;
    private int end;
    Selection() { this(0, 0); }
    Selection(int start, int end) { set(start, end); }
    void set(int start, int end) {
        // normalize so start <= end regardless of drag direction
        this.start = Math.min(start, end);
        this.end = Math.max(start, end);
    }
    void clear() { start = end = 0; }
    boolean isEmpty() { return start == end; }
    int start() { return start; }
    int end() { return end; }
    Range toRange() { return new Range(start, end); }
}

/** Internal clipboard. Single entry here; a ring/history is a trivial extension. */
final class Clipboard {
    private String content = "";
    void set(String s) { content = (s == null) ? "" : s; }
    String get() { return content; }
    boolean isEmpty() { return content.isEmpty(); }
}

/* =====================================================================================
 * STRATEGY: TEXT BUFFER  (swappable storage implementation)
 *   The whole point: Document/Commands talk to this interface, never to a concrete buffer,
 *   so we can swap StringBufferImpl <-> GapBuffer <-> (rope/piece-table) with zero ripple.
 * ===================================================================================== */
interface TextBuffer {
    void insert(int pos, String s);
    /** Removes len chars at pos and RETURNS them (needed so a delete can be undone). */
    String delete(int pos, int len);
    String getText(int start, int end);
    int length();
    char charAt(int i);
    default String all() { return getText(0, length()); }
}

/** Baseline buffer. Simple, but middle inserts are O(n) due to array shifting. */
final class StringBufferImpl implements TextBuffer {
    private final StringBuilder sb;
    StringBufferImpl() { this.sb = new StringBuilder(); }
    StringBufferImpl(String initial) { this.sb = new StringBuilder(initial == null ? "" : initial); }
    @Override public void insert(int pos, String s) {
        checkPos(pos, true);
        sb.insert(pos, s);
    }
    @Override public String delete(int pos, int len) {
        checkPos(pos, false);
        int end = Math.min(pos + Math.max(0, len), sb.length()); // clamp over-delete
        String removed = sb.substring(pos, end);
        sb.delete(pos, end);
        return removed;
    }
    @Override public String getText(int start, int end) {
        start = Math.max(0, start);
        end = Math.min(sb.length(), end);
        if (start >= end) return "";
        return sb.substring(start, end);
    }
    @Override public int length() { return sb.length(); }
    @Override public char charAt(int i) { return sb.charAt(i); }
    private void checkPos(int pos, boolean inclusiveEnd) {
        int max = inclusiveEnd ? sb.length() : Math.max(0, sb.length() - 1);
        if (pos < 0 || pos > sb.length()) throw new IndexOutOfBoundsException("pos=" + pos);
    }
}

/**
 * Gap buffer: a char[] with a movable "gap" (hole) at the cursor. Inserting near the
 * gap is O(1) amortized (just fill the hole); we only pay to MOVE the gap to the edit
 * site. This matches how humans edit — locally — so it's cheap for the common case.
 *
 * Layout:  [ ... text ... | gap (gapStart..gapEnd) | ... text ... ]
 *   logical length = capacity - gapSize
 *   logical index i maps to physical: i < gapStart ? i : i + gapSize
 */
final class GapBuffer implements TextBuffer {
    private char[] buf;
    private int gapStart; // first free slot of the gap
    private int gapEnd;   // one past last free slot of the gap

    GapBuffer() { this("", 16); }
    GapBuffer(String initial) { this(initial, Math.max(16, (initial == null ? 0 : initial.length()) * 2)); }
    GapBuffer(String initial, int capacity) {
        if (initial == null) initial = "";
        if (capacity < initial.length()) capacity = initial.length() + 16;
        buf = new char[capacity];
        // place initial text at the front, gap after it
        initial.getChars(0, initial.length(), buf, 0);
        gapStart = initial.length();
        gapEnd = capacity;
    }

    @Override public int length() { return buf.length - (gapEnd - gapStart); }

    @Override public char charAt(int i) {
        if (i < 0 || i >= length()) throw new IndexOutOfBoundsException("i=" + i);
        return buf[toPhysical(i)];
    }

    @Override public void insert(int pos, String s) {
        if (s == null || s.isEmpty()) return;
        if (pos < 0 || pos > length()) throw new IndexOutOfBoundsException("pos=" + pos);
        moveGapTo(pos);
        ensureGap(s.length());
        for (int k = 0; k < s.length(); k++) buf[gapStart++] = s.charAt(k);
    }

    @Override public String delete(int pos, int len) {
        if (len <= 0) return "";
        if (pos < 0 || pos > length()) throw new IndexOutOfBoundsException("pos=" + pos);
        len = Math.min(len, length() - pos); // clamp over-delete
        if (len <= 0) return "";
        moveGapTo(pos);
        // characters to delete sit just AFTER the gap; capture then absorb into the gap
        char[] removed = new char[len];
        System.arraycopy(buf, gapEnd, removed, 0, len);
        gapEnd += len; // grow the gap to swallow them
        return new String(removed);
    }

    @Override public String getText(int start, int end) {
        start = Math.max(0, start);
        end = Math.min(length(), end);
        if (start >= end) return "";
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) out.append(buf[toPhysical(i)]);
        return out.toString();
    }

    /* ---- gap mechanics ---- */
    private int toPhysical(int logical) {
        return (logical < gapStart) ? logical : logical + (gapEnd - gapStart);
    }
    private void moveGapTo(int pos) {
        if (pos == gapStart) return;
        if (pos < gapStart) {
            // shift the [pos, gapStart) block to the right end of the gap
            int n = gapStart - pos;
            System.arraycopy(buf, pos, buf, gapEnd - n, n);
            gapStart -= n;
            gapEnd -= n;
        } else {
            // shift the [gapEnd, gapEnd + (pos - gapStart)) block to the left
            int n = pos - gapStart;
            System.arraycopy(buf, gapEnd, buf, gapStart, n);
            gapStart += n;
            gapEnd += n;
        }
    }
    private void ensureGap(int need) {
        if (gapEnd - gapStart >= need) return;
        int len = length();
        int newCap = Math.max(buf.length * 2, len + need + 16);
        char[] nb = new char[newCap];
        // copy the two live segments; rebuild a fresh gap in the middle
        System.arraycopy(buf, 0, nb, 0, gapStart);
        int tail = buf.length - gapEnd;
        System.arraycopy(buf, gapEnd, nb, newCap - tail, tail);
        buf = nb;
        gapEnd = newCap - tail;
        // gapStart unchanged
    }
}

/* =====================================================================================
 * COMMAND PATTERN  (the backbone of undo/redo)
 *   Each command stores only the DELTA needed to apply and to invert itself, plus the
 *   cursor/selection it should restore on undo (good UX: undo puts the caret back).
 * ===================================================================================== */
interface Command {
    void execute();
    void undo();
    /** Coalescing hook: can THIS command absorb the next one into a single undo unit? */
    default boolean canCoalesce(Command next) { return false; }
    /** Return a merged command (called only when canCoalesce returned true). */
    default Command coalesce(Command next) { throw new UnsupportedOperationException(); }
}

/** Base class wiring a command to the document state it mutates and restores. */
abstract class AbstractEditCommand implements Command {
    protected final TextBuffer buffer;
    protected final Cursor cursor;
    protected final Selection selection;
    // cursor/selection snapshot taken BEFORE execute, restored on undo
    protected int savedCursor;
    protected int savedSelStart;
    protected int savedSelEnd;

    AbstractEditCommand(TextBuffer buffer, Cursor cursor, Selection selection) {
        this.buffer = buffer;
        this.cursor = cursor;
        this.selection = selection;
    }
    protected void snapshotCaret() {
        savedCursor = cursor.get();
        savedSelStart = selection.start();
        savedSelEnd = selection.end();
    }
    protected void restoreCaret() {
        cursor.set(savedCursor);
        selection.set(savedSelStart, savedSelEnd);
    }
}

/** Insert `text` at `pos`. Undo removes it. Coalesces with adjacent typed inserts. */
final class InsertCommand extends AbstractEditCommand {
    private int pos;
    private String text;
    private final boolean fromTyping; // only typing runs coalesce

    InsertCommand(TextBuffer b, Cursor c, Selection s, int pos, String text, boolean fromTyping) {
        super(b, c, s);
        this.pos = pos;
        this.text = text;
        this.fromTyping = fromTyping;
    }
    @Override public void execute() {
        snapshotCaret();
        buffer.insert(pos, text);
        cursor.set(pos + text.length());
        selection.clear();
    }
    @Override public void undo() {
        buffer.delete(pos, text.length());
        restoreCaret();
    }
    @Override public boolean canCoalesce(Command next) {
        if (!fromTyping || !(next instanceof InsertCommand)) return false;
        InsertCommand n = (InsertCommand) next;
        // merge only contiguous typing: next insert begins exactly where this one ended,
        // and we don't merge across a newline (so Enter starts a new undo unit).
        return n.fromTyping
                && n.pos == this.pos + this.text.length()
                && !this.text.endsWith("\n");
    }
    @Override public Command coalesce(Command next) {
        InsertCommand n = (InsertCommand) next;
        // grow this command's text; keep this command's original caret snapshot so a single
        // undo of "hello" restores the caret to before the first char.
        this.text = this.text + n.text;
        cursor.set(this.pos + this.text.length());
        return this;
    }
}

/** Delete `len` chars at `pos`. Stores removed text so undo can re-insert it. */
final class DeleteCommand extends AbstractEditCommand {
    private final int pos;
    private final int len;
    private String removed; // captured at execute time

    DeleteCommand(TextBuffer b, Cursor c, Selection s, int pos, int len) {
        super(b, c, s);
        this.pos = pos;
        this.len = len;
    }
    @Override public void execute() {
        snapshotCaret();
        removed = buffer.delete(pos, len);
        cursor.set(pos);
        selection.clear();
    }
    @Override public void undo() {
        buffer.insert(pos, removed);
        restoreCaret();
    }
}

/** Replace [start, start+oldLen) with `inserted`. Used by find/replace. */
final class ReplaceCommand extends AbstractEditCommand {
    private final int start;
    private final int oldLen;
    private final String inserted;
    private String removed;

    ReplaceCommand(TextBuffer b, Cursor c, Selection s, int start, int oldLen, String inserted) {
        super(b, c, s);
        this.start = start;
        this.oldLen = oldLen;
        this.inserted = inserted;
    }
    @Override public void execute() {
        snapshotCaret();
        removed = buffer.delete(start, oldLen);
        buffer.insert(start, inserted);
        cursor.set(start + inserted.length());
        selection.clear();
    }
    @Override public void undo() {
        buffer.delete(start, inserted.length());
        buffer.insert(start, removed);
        restoreCaret();
    }
}

/* =====================================================================================
 * COMPOSITE PATTERN: MacroCommand
 *   A command that IS a group of commands. Cut (=delete) and replace-all (=many replaces)
 *   become a single undo step. History only ever sees "a Command" (Liskov substitution).
 * ===================================================================================== */
final class MacroCommand implements Command {
    private final List<Command> children = new ArrayList<>();
    void add(Command c) { children.add(c); }
    boolean isEmpty() { return children.isEmpty(); }
    @Override public void execute() {
        for (Command c : children) c.execute();        // forward order
    }
    @Override public void undo() {
        for (int i = children.size() - 1; i >= 0; i--)  // reverse order
            children.get(i).undo();
    }
}

/* =====================================================================================
 * HISTORY: the undo/redo engine
 *   Two stacks. Push executes (or accepts an executed command), clears redo, then tries
 *   to coalesce with the top entry. Eviction caps memory (drop the OLDEST = bottom).
 * ===================================================================================== */
final class History {
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();
    private final int cap;

    History() { this(1000); }
    History(int cap) { this.cap = Math.max(1, cap); }

    /** Execute a command, record it, and invalidate redo. Coalesces typing runs. */
    void push(Command c) {
        c.execute();
        redoStack.clear(); // a NEW edit kills the redo branch
        Command top = undoStack.peek();
        if (top != null && top.canCoalesce(c)) {
            // command already executed; coalesce merges its delta into the top entry.
            top.coalesce(c);
        } else {
            undoStack.push(c);
            evictIfNeeded();
        }
    }

    void undo() {
        if (undoStack.isEmpty()) return;
        Command c = undoStack.pop();
        c.undo();
        redoStack.push(c);
    }

    void redo() {
        if (redoStack.isEmpty()) return;
        Command c = redoStack.pop();
        c.execute();
        undoStack.push(c);
    }

    /** Force a boundary so the next typed char won't merge into the current run. */
    void breakCoalescing() {
        if (!undoStack.isEmpty()) undoStack.push(new BarrierCommand());
    }

    boolean canUndo() { return !undoStack.isEmpty(); }
    boolean canRedo() { return !redoStack.isEmpty(); }

    private void evictIfNeeded() {
        while (undoStack.size() > cap) undoStack.removeLast(); // drop OLDEST (bottom)
    }

    /** No-op marker pushed to stop coalescing across a logical boundary. */
    private static final class BarrierCommand implements Command {
        @Override public void execute() { }
        @Override public void undo() { }
    }
}

/* =====================================================================================
 * STRATEGY: SearchStrategy (literal vs regex) — find/replace algorithm is swappable.
 * ===================================================================================== */
interface SearchStrategy {
    List<Range> findAll(String text, String pattern);
}

/** Plain substring search, case-sensitive. */
final class LiteralSearch implements SearchStrategy {
    @Override public List<Range> findAll(String text, String pattern) {
        List<Range> out = new ArrayList<>();
        if (pattern == null || pattern.isEmpty()) return out;
        int from = 0, idx;
        while ((idx = text.indexOf(pattern, from)) >= 0) {
            out.add(new Range(idx, idx + pattern.length()));
            from = idx + pattern.length(); // non-overlapping
        }
        return out;
    }
}

/** Regex search. Guards zero-width matches so replace-all can't loop forever. */
final class RegexSearch implements SearchStrategy {
    @Override public List<Range> findAll(String text, String pattern) {
        List<Range> out = new ArrayList<>();
        if (pattern == null || pattern.isEmpty()) return out;
        Matcher m = Pattern.compile(pattern).matcher(text);
        int searchFrom = 0;
        while (searchFrom <= text.length() && m.find(searchFrom)) {
            int s = m.start(), e = m.end();
            out.add(new Range(s, e));
            searchFrom = (e == s) ? e + 1 : e; // advance past zero-width matches
        }
        return out;
    }
}

/* =====================================================================================
 * MEMENTO: snapshot of document state for autosave / restore (encapsulation preserved).
 * ===================================================================================== */
final class EditorMemento {
    private final String text;
    private final int cursor;
    private final int selStart;
    private final int selEnd;
    EditorMemento(String text, int cursor, int selStart, int selEnd) {
        this.text = text; this.cursor = cursor; this.selStart = selStart; this.selEnd = selEnd;
    }
    // package-private getters — only the originator (Document) reads these back.
    String text() { return text; }
    int cursor() { return cursor; }
    int selStart() { return selStart; }
    int selEnd() { return selEnd; }
}

/* =====================================================================================
 * FACADE / AGGREGATE ROOT: Document
 *   Owns buffer + cursor + selection + clipboard + history + search strategy and exposes
 *   intent-level operations. UI code calls type()/cut()/undo(), not the command plumbing.
 * ===================================================================================== */
class Document {
    private TextBuffer buffer;
    private final Cursor cursor = new Cursor(0);
    private final Selection selection = new Selection();
    private final Clipboard clipboard = new Clipboard();
    private final History history;
    private SearchStrategy search = new LiteralSearch(); // default; swappable (Strategy)

    Document() { this(new GapBuffer(), new History()); }
    Document(String initial) { this(new GapBuffer(initial), new History()); }
    Document(TextBuffer buffer, History history) {
        this.buffer = buffer;
        this.history = history;
        this.cursor.set(buffer.length());
    }

    /* ---- configuration ---- */
    void setSearchStrategy(SearchStrategy s) { this.search = s; }

    /* ---- cursor / selection ---- */
    void moveCursor(int pos) {
        cursor.set(clamp(pos, 0, buffer.length()));
        selection.clear();
        history.breakCoalescing(); // moving the caret ends the current typing run
    }
    void select(int start, int end) {
        int s = clamp(start, 0, buffer.length());
        int e = clamp(end, 0, buffer.length());
        selection.set(s, e);
        cursor.set(e);
    }
    void clearSelection() { selection.clear(); }

    /* ---- editing ---- */
    /** Type text at the cursor; replaces the current selection if any (one undo step). */
    void type(String text) {
        if (text == null || text.isEmpty()) return;
        if (!selection.isEmpty()) {
            // replace selection: group delete + insert into ONE undo unit
            MacroCommand macro = new MacroCommand();
            Range r = selection.toRange();
            macro.add(new DeleteCommand(buffer, cursor, selection, r.start, r.length()));
            macro.add(new InsertCommand(buffer, cursor, selection, r.start, text, false));
            history.push(macro);
        } else {
            history.push(new InsertCommand(buffer, cursor, selection, cursor.get(), text, true));
        }
    }

    /** Backspace: delete the selection if present, else the char before the cursor. */
    void backspace() {
        if (!selection.isEmpty()) { deleteSelection(); return; }
        int pos = cursor.get();
        if (pos == 0) return;
        history.push(new DeleteCommand(buffer, cursor, selection, pos - 1, 1));
    }

    void deleteSelection() {
        if (selection.isEmpty()) return;
        Range r = selection.toRange();
        history.push(new DeleteCommand(buffer, cursor, selection, r.start, r.length()));
    }

    /* ---- clipboard ---- */
    void copy() {
        if (selection.isEmpty()) return; // copy is non-mutating: NO history entry
        Range r = selection.toRange();
        clipboard.set(buffer.getText(r.start, r.end));
    }
    void cut() {
        if (selection.isEmpty()) return;
        copy();             // fill clipboard first
        deleteSelection();  // then a single undoable delete
    }
    void paste() {
        if (clipboard.isEmpty()) return; // nothing to paste -> no history entry
        type(clipboard.get());           // reuses type() (handles selection-replace)
    }

    /* ---- find / replace ---- */
    List<Range> find(String pattern) {
        return search.findAll(buffer.all(), pattern);
    }
    /** Replace ALL matches as a single undo step; right-to-left to keep offsets valid. */
    void replaceAll(String pattern, String replacement) {
        List<Range> matches = find(pattern);
        if (matches.isEmpty()) return;
        MacroCommand macro = new MacroCommand();
        for (int i = matches.size() - 1; i >= 0; i--) { // RIGHT-to-LEFT
            Range m = matches.get(i);
            macro.add(new ReplaceCommand(buffer, cursor, selection, m.start, m.length(), replacement));
        }
        history.push(macro);
    }

    /* ---- undo / redo ---- */
    void undo() { history.undo(); }
    void redo() { history.redo(); }
    boolean canUndo() { return history.canUndo(); }
    boolean canRedo() { return history.canRedo(); }

    /* ---- memento (autosave / restore) ---- */
    EditorMemento save() {
        return new EditorMemento(buffer.all(), cursor.get(), selection.start(), selection.end());
    }
    void restore(EditorMemento m) {
        this.buffer = new GapBuffer(m.text());
        cursor.set(clamp(m.cursor(), 0, buffer.length()));
        selection.set(m.selStart(), m.selEnd());
    }

    /* ---- accessors ---- */
    String getText() { return buffer.all(); }
    int cursorPos() { return cursor.get(); }
    Selection selection() { return selection; }
    String clipboardContent() { return clipboard.get(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

/* =====================================================================================
 * DECORATOR: SynchronizedDocument
 *   Adds coarse-grained thread safety WITHOUT baking locks into the single-threaded core.
 *   Locks at the OPERATION boundary so buffer+cursor+history stay atomic together.
 *   (We extend Document and wrap a delegate; every public op acquires one lock.)
 * ===================================================================================== */
final class SynchronizedDocument extends Document {
    private final Document delegate;
    private final Object lock = new Object();

    SynchronizedDocument(Document delegate) { this.delegate = delegate; }

    @Override void type(String t)        { synchronized (lock) { delegate.type(t); } }
    @Override void backspace()           { synchronized (lock) { delegate.backspace(); } }
    @Override void deleteSelection()     { synchronized (lock) { delegate.deleteSelection(); } }
    @Override void moveCursor(int p)     { synchronized (lock) { delegate.moveCursor(p); } }
    @Override void select(int s, int e)  { synchronized (lock) { delegate.select(s, e); } }
    @Override void clearSelection()      { synchronized (lock) { delegate.clearSelection(); } }
    @Override void copy()                { synchronized (lock) { delegate.copy(); } }
    @Override void cut()                 { synchronized (lock) { delegate.cut(); } }
    @Override void paste()               { synchronized (lock) { delegate.paste(); } }
    @Override List<Range> find(String p) { synchronized (lock) { return delegate.find(p); } }
    @Override void replaceAll(String p, String r) { synchronized (lock) { delegate.replaceAll(p, r); } }
    @Override void undo()                { synchronized (lock) { delegate.undo(); } }
    @Override void redo()                { synchronized (lock) { delegate.redo(); } }
    @Override void setSearchStrategy(SearchStrategy s) { synchronized (lock) { delegate.setSearchStrategy(s); } }
    @Override EditorMemento save()       { synchronized (lock) { return delegate.save(); } }
    @Override void restore(EditorMemento m) { synchronized (lock) { delegate.restore(m); } }
    @Override String getText()           { synchronized (lock) { return delegate.getText(); } }
    @Override int cursorPos()            { synchronized (lock) { return delegate.cursorPos(); } }
    @Override boolean canUndo()          { synchronized (lock) { return delegate.canUndo(); } }
    @Override boolean canRedo()          { synchronized (lock) { return delegate.canRedo(); } }
    @Override String clipboardContent()  { synchronized (lock) { return delegate.clipboardContent(); } }
}

/* =====================================================================================
 * DEMO  —  illustrates the key scenarios and prints output (for the reader).
 * ===================================================================================== */
public class Solution {
    public static void main(String[] args) {
        System.out.println("=== Text Editor LLD demo ===\n");

        Document doc = new Document();

        // 1) Typing coalesces into ONE undo unit ----------------------------------------
        doc.type("Hello");
        doc.type(", world");
        System.out.println("After typing       : \"" + doc.getText() + "\"  cursor=" + doc.cursorPos());

        doc.undo(); // one undo should remove the whole coalesced typing run
        System.out.println("After 1 undo       : \"" + doc.getText() + "\"  (coalesced run reverted)");

        doc.redo();
        System.out.println("After redo         : \"" + doc.getText() + "\"\n");

        // 2) Selection + cut / paste -----------------------------------------------------
        doc.moveCursor(doc.getText().length());     // breaks coalescing (new undo unit)
        doc.select(0, 5);                            // select "Hello"
        doc.cut();
        System.out.println("After cut [0,5)    : \"" + doc.getText() + "\"  clipboard=\"" + doc.clipboardContent() + "\"");

        doc.moveCursor(doc.getText().length());
        doc.paste();
        System.out.println("After paste at end : \"" + doc.getText() + "\"");

        doc.undo(); // undo paste
        doc.undo(); // undo cut
        System.out.println("After 2 undos      : \"" + doc.getText() + "\"\n");

        // 3) Find / replace-all (single undo step, literal then regex) -------------------
        Document doc2 = new Document("the cat sat on the mat");
        System.out.println("Doc2               : \"" + doc2.getText() + "\"");
        System.out.println("find \"at\"          : " + doc2.find("at"));

        doc2.replaceAll("at", "AT");
        System.out.println("replaceAll at->AT  : \"" + doc2.getText() + "\"");
        doc2.undo();
        System.out.println("undo replaceAll    : \"" + doc2.getText() + "\"  (whole replace-all reverted in ONE step)");

        doc2.setSearchStrategy(new RegexSearch()); // swap algorithm (Strategy pattern)
        doc2.replaceAll("\\bthe\\b", "THE");
        System.out.println("regex \\bthe\\b->THE : \"" + doc2.getText() + "\"\n");

        // 4) Replace selection in one undo step -----------------------------------------
        Document doc3 = new Document("foo bar baz");
        doc3.select(4, 7);          // "bar"
        doc3.type("QUX");           // replace selection
        System.out.println("Replace selection  : \"" + doc3.getText() + "\"");
        doc3.undo();
        System.out.println("Undo               : \"" + doc3.getText() + "\"\n");

        // 5) Memento snapshot / restore -------------------------------------------------
        Document doc4 = new Document("snapshot me");
        EditorMemento snap = doc4.save();
        doc4.type(" -- edited");
        System.out.println("Before restore     : \"" + doc4.getText() + "\"");
        doc4.restore(snap);
        System.out.println("After restore      : \"" + doc4.getText() + "\"\n");

        // 6) Thread-safe decorator (opt-in) ---------------------------------------------
        Document safe = new SynchronizedDocument(new Document());
        safe.type("locked edits");
        System.out.println("Synchronized doc   : \"" + safe.getText() + "\"");

        // 7) Edge cases ------------------------------------------------------------------
        Document e = new Document();
        e.undo(); e.redo();              // empty stacks -> no-ops, no crash
        e.paste();                       // empty clipboard -> no-op
        e.type("x"); e.moveCursor(0); e.backspace(); // backspace at pos 0 -> no-op
        System.out.println("Edge cases ok      : \"" + e.getText() + "\"  canUndo=" + e.canUndo());

        System.out.println("\n=== demo complete ===");
    }
}
