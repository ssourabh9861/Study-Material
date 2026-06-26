/* =====================================================================================
 * LLD: Jukebox / Music Player  —  single-file REVIEW / REVISION artifact
 * -------------------------------------------------------------------------------------
 * This file is for reading & last-minute revision. It is complete and correct-by-
 * inspection; it is NOT meant to be a production build target. Pure JDK only.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional:
 *   1. Transport: just play/pause/stop/next/prev, or also seek/volume? (-> State surface)
 *   2. Play orders: sequential, shuffle, repeat-one, repeat-all? Can shuffle + repeat
 *      be COMBINED? (decides Strategy-vs-flag for repeat)
 *   3. "Previous" under shuffle: play-history back, or list-order back?
 *   4. Separate up-next QUEUE with priority over the playlist? Does "play now" jump it?
 *   5. Playlist ops: create/rename/delete, add/remove/reorder, dedupe? Ordered?
 *   6. Library/search: by title/artist/album; substring vs prefix vs ranked? Browse album?
 *   7. Users/accounts (per-user playlists/history) or single-user device?
 * Non-functional:
 *   8. Concurrency: UI command thread + background playback clock thread? -> thread-safe core.
 *   9. Persistence: in-memory only or load/save? (assume in-memory)
 *  10. Scale: thousands (hash maps) vs millions (external index)?
 *  11. Observer notification: synchronous vs async; must a slow/throwing observer not
 *      stall playback?
 * Scope / out-of-scope:
 *  12. Out: real codec/DSP, streaming, DRM, gapless/crossfade, recommendations, UI.
 *  13. Undo of playlist edits? Equalizer/effects? (likely out; cheap as extensions)
 *
 * ASSUMED ANSWERS (so we can build):
 *   - Single device, single user, in-memory.
 *   - Sequential + Shuffle as Strategy; Repeat is an ORTHOGONAL enum (combinable).
 *   - Priority up-next Queue; "play now" jumps the line.
 *   - Substring search.
 *   - Background clock thread + UI thread -> player core MUST be thread-safe.
 *   - Observers notified synchronously but defensively (isolated, dispatched outside lock).
 *   - Audio output mocked behind AudioOutput.
 *
 * PATTERNS: State (transport), Strategy (play order), Iterator (playlist),
 *           Observer (now-playing), Singleton (library). Decorator/Command mentioned.
 * ===================================================================================== */

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/* =====================================================================================
 * DOMAIN ENTITIES (immutable where practical)
 * ===================================================================================== */

final class Artist {
    final String id;
    final String name;
    Artist(String id, String name) { this.id = id; this.name = name; }
    @Override public String toString() { return name; }
}

final class Album {
    final String id;
    final String title;
    final Artist artist;
    final List<Song> tracks = new ArrayList<>(); // filled at registration
    Album(String id, String title, Artist artist) {
        this.id = id; this.title = title; this.artist = artist;
    }
    void addTrack(Song s) { tracks.add(s); }
    List<Song> tracks() { return Collections.unmodifiableList(tracks); }
    @Override public String toString() { return title + " by " + artist; }
}

final class Song {
    final String id;
    final String title;
    final int durationSeconds;
    final Artist artist;
    final Album album;
    Song(String id, String title, int durationSeconds, Album album) {
        this.id = id; this.title = title; this.durationSeconds = durationSeconds;
        this.album = album; this.artist = album.artist;
    }
    @Override public boolean equals(Object o) {
        return (o instanceof Song) && ((Song) o).id.equals(id);
    }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() {
        return "'" + title + "' (" + artist + ", " + durationSeconds + "s)";
    }
}

/* =====================================================================================
 * SINGLETON — MusicLibrary (catalog). Holder idiom: lazy + thread-safe, no sync cost.
 * NOTE: DI of a single instance is the cleaner production choice; Singleton used here
 * because the catalog is conceptually device-wide and the prompt asks for it.
 * ===================================================================================== */

final class MusicLibrary {
    private final Map<String, Song> songs = new ConcurrentHashMap<>();
    private final Map<String, Album> albums = new ConcurrentHashMap<>();

    private MusicLibrary() { }
    private static class Holder { static final MusicLibrary INSTANCE = new MusicLibrary(); }
    static MusicLibrary getInstance() { return Holder.INSTANCE; }

    void registerAlbum(Album album) {
        albums.put(album.id, album);
        for (Song s : album.tracks) songs.put(s.id, s);
    }

    Album album(String id) { return albums.get(id); }
    Song song(String id) { return songs.get(id); }

    /** Substring search across title / artist / album. */
    List<Song> search(String query) {
        String q = query.toLowerCase();
        List<Song> out = new ArrayList<>();
        for (Song s : songs.values()) {
            if (s.title.toLowerCase().contains(q)
                    || s.artist.name.toLowerCase().contains(q)
                    || s.album.title.toLowerCase().contains(q)) {
                out.add(s);
            }
        }
        return out;
    }
}

/* =====================================================================================
 * ITERATOR — Playlist is Iterable<Song>; traversal decoupled from storage.
 * Tracks operate on the Song identity so the playhead survives reorders/removals.
 * ===================================================================================== */

final class Playlist implements Iterable<Song> {
    private final String name;
    private final List<Song> songs = new ArrayList<>();

    Playlist(String name) { this.name = name; }
    String name() { return name; }

    void add(Song s) { songs.add(s); }
    boolean remove(Song s) { return songs.remove(s); }
    void move(int from, int to) {
        if (from < 0 || from >= songs.size() || to < 0 || to >= songs.size()) return;
        Song s = songs.remove(from);
        songs.add(to, s);
    }
    Song get(int i) { return songs.get(i); }
    int indexOf(Song s) { return songs.indexOf(s); }
    int size() { return songs.size(); }
    boolean isEmpty() { return songs.isEmpty(); }

    @Override public Iterator<Song> iterator() {
        // Custom iterator: read-only traversal; hides the backing list.
        return new Iterator<Song>() {
            private int cursor = 0;
            @Override public boolean hasNext() { return cursor < songs.size(); }
            @Override public Song next() { return songs.get(cursor++); }
        };
    }
}

/* =====================================================================================
 * Up-next QUEUE — FIFO with priority over the playlist. Thread-safe (concurrent deque).
 * enqueue() = append (low priority); addFront() = jump the line ("play now").
 * ===================================================================================== */

final class PlayQueue {
    private final Deque<Song> items = new ConcurrentLinkedDeque<>();
    void enqueue(Song s) { items.addLast(s); }
    void addFront(Song s) { items.addFirst(s); }
    Song poll() { return items.pollFirst(); }
    Song peek() { return items.peekFirst(); }
    boolean isEmpty() { return items.isEmpty(); }
    int size() { return items.size(); }
}

/* =====================================================================================
 * STRATEGY — play order. Repeat is intentionally NOT here (kept orthogonal as an enum)
 * so shuffle + repeat-all can compose without a subclass explosion.
 * Contract: return -1 to signal "no next/previous in this cycle" (player applies repeat).
 * ===================================================================================== */

interface PlayStrategy {
    int nextIndex(int current, int size);
    int previousIndex(int current, int size);
    int peekNext(int current, int size);    // look-ahead for gapless/crossfade extension
    void onListChanged(int size);           // reset internal cursor / reshuffle
    String name();
}

final class SequentialStrategy implements PlayStrategy {
    @Override public int nextIndex(int current, int size) {
        if (size == 0) return -1;
        int n = current + 1;
        return n < size ? n : -1;            // -1 => end of list; player decides repeat
    }
    @Override public int previousIndex(int current, int size) {
        if (size == 0) return -1;
        int p = current - 1;
        return p >= 0 ? p : -1;
    }
    @Override public int peekNext(int current, int size) { return nextIndex(current, size); }
    @Override public void onListChanged(int size) { /* stateless */ }
    @Override public String name() { return "SEQUENTIAL"; }
}

final class ShuffleStrategy implements PlayStrategy {
    // Permutation-per-cycle so every track plays once before any repeats.
    private List<Integer> order = new ArrayList<>();
    private int pos = -1;

    @Override public void onListChanged(int size) {
        order = new ArrayList<>();
        for (int i = 0; i < size; i++) order.add(i);
        Collections.shuffle(order);
        pos = -1;
    }
    @Override public int nextIndex(int current, int size) {
        if (size == 0) return -1;
        if (order.size() != size) onListChanged(size);
        if (pos + 1 < order.size()) { pos++; return order.get(pos); }
        return -1;                            // cycle exhausted; player reshuffles if REPEAT_ALL
    }
    @Override public int previousIndex(int current, int size) {
        if (size == 0) return -1;
        if (pos - 1 >= 0) { pos--; return order.get(pos); }
        return -1;
    }
    @Override public int peekNext(int current, int size) {
        if (size == 0) return -1;
        if (order.size() != size) onListChanged(size);
        return (pos + 1 < order.size()) ? order.get(pos + 1) : -1;
    }
    @Override public String name() { return "SHUFFLE"; }
}

/** Repeat is orthogonal to order; applied by the player at list boundaries. */
enum RepeatMode { NONE, REPEAT_ONE, REPEAT_ALL }

/* =====================================================================================
 * AUDIO OUTPUT — abstracts the sink so the core is testable and swappable
 * (cast device / equalizer via Decorator are extensions).
 * ===================================================================================== */

interface AudioOutput {
    void load(Song s);
    void start();
    void pause();
    void stop();
    void setVolume(int v);
    void seek(int seconds);
}

final class MockAudioOutput implements AudioOutput {
    @Override public void load(Song s)        { System.out.println("    [audio] load " + s.title); }
    @Override public void start()             { System.out.println("    [audio] start"); }
    @Override public void pause()             { System.out.println("    [audio] pause"); }
    @Override public void stop()              { System.out.println("    [audio] stop"); }
    @Override public void setVolume(int v)    { System.out.println("    [audio] volume=" + v); }
    @Override public void seek(int seconds)   { System.out.println("    [audio] seek=" + seconds); }
}

/* =====================================================================================
 * OBSERVER — now-playing / state / position events. Isolated + dispatched outside lock.
 * ===================================================================================== */

final class PlaybackEvent {
    enum Type { TRACK_CHANGED, STATE_CHANGED, POSITION }
    final Type type;
    final Song song;
    final String state;
    final int positionSeconds;
    PlaybackEvent(Type type, Song song, String state, int positionSeconds) {
        this.type = type; this.song = song; this.state = state; this.positionSeconds = positionSeconds;
    }
}

interface PlaybackObserver { void onEvent(PlaybackEvent e); }

final class NowPlayingDisplay implements PlaybackObserver {
    @Override public void onEvent(PlaybackEvent e) {
        switch (e.type) {
            case TRACK_CHANGED:
                System.out.println("  [display] Now Playing: "
                        + (e.song == null ? "<nothing>" : e.song.title + " — " + e.song.artist));
                break;
            case STATE_CHANGED:
                System.out.println("  [display] State -> " + e.state);
                break;
            case POSITION:
                // Throttle in a real UI; here we print every few seconds only.
                if (e.song != null && e.positionSeconds % 2 == 0)
                    System.out.println("  [display] " + e.song.title + " @ " + e.positionSeconds + "s");
                break;
        }
    }
}

final class ScrobbleLogger implements PlaybackObserver {
    @Override public void onEvent(PlaybackEvent e) {
        if (e.type == PlaybackEvent.Type.TRACK_CHANGED && e.song != null)
            System.out.println("  [scrobble] played " + e.song.title);
    }
}

/* =====================================================================================
 * STATE — player transport lifecycle. Each state decides legal verbs + transitions.
 * Verbs take the player as context so they can mutate it and switch states.
 * ===================================================================================== */

interface PlayerState {
    void play(MusicPlayer p);
    void pause(MusicPlayer p);
    void resume(MusicPlayer p);
    void stop(MusicPlayer p);
    void next(MusicPlayer p);
    void previous(MusicPlayer p);
    String name();
}

final class StoppedState implements PlayerState {
    @Override public void play(MusicPlayer p)     { p.startFromBeginning(); }
    @Override public void pause(MusicPlayer p)    { /* no-op: nothing playing */ }
    @Override public void resume(MusicPlayer p)   { p.startFromBeginning(); }
    @Override public void stop(MusicPlayer p)     { /* already stopped */ }
    @Override public void next(MusicPlayer p)     { p.startFromBeginning(); } // start, then advance handled by play
    @Override public void previous(MusicPlayer p) { p.startFromBeginning(); }
    @Override public String name() { return "STOPPED"; }
}

final class PlayingState implements PlayerState {
    @Override public void play(MusicPlayer p)     { /* already playing */ }
    @Override public void pause(MusicPlayer p)    { p.doPause(); }
    @Override public void resume(MusicPlayer p)   { /* already playing */ }
    @Override public void stop(MusicPlayer p)     { p.doStop(); }
    @Override public void next(MusicPlayer p)     { p.advance(+1); }
    @Override public void previous(MusicPlayer p) { p.advance(-1); }
    @Override public String name() { return "PLAYING"; }
}

final class PausedState implements PlayerState {
    @Override public void play(MusicPlayer p)     { p.doResume(); }
    @Override public void pause(MusicPlayer p)    { /* already paused */ }
    @Override public void resume(MusicPlayer p)   { p.doResume(); }
    @Override public void stop(MusicPlayer p)     { p.doStop(); }
    @Override public void next(MusicPlayer p)     { p.advance(+1); }   // skip while paused -> plays next
    @Override public void previous(MusicPlayer p) { p.advance(-1); }
    @Override public String name() { return "PAUSED"; }
}

/* =====================================================================================
 * MusicPlayer — the orchestrator / context. Thread-safe (coarse lock on transport).
 * Tracks the CURRENT SONG (not just an index) so the playhead survives playlist edits.
 * Keeps a HISTORY deque for correct "previous" under shuffle.
 * ===================================================================================== */

final class MusicPlayer {
    private final Object lock = new Object();

    private PlayerState state = new StoppedState();
    private PlayStrategy strategy = new SequentialStrategy();
    private RepeatMode repeatMode = RepeatMode.NONE;

    private Playlist current;                       // active playlist
    private final PlayQueue queue = new PlayQueue(); // priority up-next
    private final Deque<Song> history = new ArrayDeque<>();
    private final AudioOutput output;

    private final List<PlaybackObserver> observers = new CopyOnWriteArrayList<>();

    private Song currentSong;                        // identity-tracked playhead
    private final AtomicInteger positionSeconds = new AtomicInteger(0);
    private int volume = 50;

    MusicPlayer(AudioOutput output) { this.output = output; }

    /* ---- configuration ---- */
    void loadPlaylist(Playlist p) {
        synchronized (lock) {
            this.current = p;
            strategy.onListChanged(p.size());
        }
    }
    void setPlayStrategy(PlayStrategy s) {
        synchronized (lock) {
            this.strategy = s;
            if (current != null) s.onListChanged(current.size());
            System.out.println("[player] strategy -> " + s.name());
        }
    }
    void setRepeatMode(RepeatMode m) {
        synchronized (lock) { this.repeatMode = m; System.out.println("[player] repeat -> " + m); }
    }
    void addObserver(PlaybackObserver o)    { observers.add(o); }
    void removeObserver(PlaybackObserver o) { observers.remove(o); }

    /* ---- transport (delegate to State) ---- */
    void play()              { synchronized (lock) { state.play(this); } }
    void pause()             { synchronized (lock) { state.pause(this); } }
    void resume()            { synchronized (lock) { state.resume(this); } }
    void stop()              { synchronized (lock) { state.stop(this); } }
    void next()              { synchronized (lock) { state.next(this); } }
    void previous()          { synchronized (lock) { state.previous(this); } }

    void seek(int seconds) {
        synchronized (lock) {
            if (currentSong == null) return;
            int clamped = Math.max(0, Math.min(seconds, currentSong.durationSeconds));
            positionSeconds.set(clamped);
            output.seek(clamped);
        }
    }
    void setVolume(int v) {
        synchronized (lock) { volume = Math.max(0, Math.min(100, v)); output.setVolume(volume); }
    }

    /* ---- queue ops ---- */
    void enqueue(Song s) { queue.enqueue(s); System.out.println("[player] enqueued " + s.title); }
    void playNow(Song s) {
        // Jump the line: prepend to queue and force an advance so it plays immediately.
        synchronized (lock) {
            queue.addFront(s);
            System.out.println("[player] play-now " + s.title);
            advance(+1);
        }
    }

    /* ---- queries ---- */
    Song nowPlaying() { synchronized (lock) { return currentSong; } }
    String stateName() { synchronized (lock) { return state.name(); } }
    int position() { return positionSeconds.get(); }

    /* ---- State callbacks (package-visible; always called under lock) ---- */

    void setState(PlayerState s) {
        this.state = s;
        notifyOutsideLock(new PlaybackEvent(PlaybackEvent.Type.STATE_CHANGED, currentSong, s.name(), position()));
    }

    /** Begin playback: queue has priority; else first track of the playlist. */
    void startFromBeginning() {
        Song first = queue.poll();
        if (first == null) {
            if (current == null || current.isEmpty()) { return; } // nothing to play -> stay stopped
            strategy.onListChanged(current.size());
            first = current.get(0);
        }
        playSong(first);
    }

    /** Compute and play the next/previous track. dir = +1 forward, -1 backward. */
    void advance(int dir) {
        // 1) Queue has priority on forward moves.
        if (dir > 0) {
            Song q = queue.poll();
            if (q != null) { pushHistory(); playSong(q); return; }
        }
        // 2) Backward: prefer history (correct "previous" under shuffle).
        if (dir < 0 && !history.isEmpty()) {
            Song prev = history.pollLast();
            playSong(prev);
            return;
        }
        if (current == null || current.isEmpty()) { doStop(); return; }

        // 3) REPEAT_ONE: replay the same track.
        if (repeatMode == RepeatMode.REPEAT_ONE && currentSong != null && dir > 0) {
            positionSeconds.set(0); output.seek(0); output.start();
            return;
        }

        int size = current.size();
        int curIdx = currentSong == null ? -1 : current.indexOf(currentSong);
        int idx = (dir > 0) ? strategy.nextIndex(curIdx, size)
                            : strategy.previousIndex(curIdx, size);

        // 4) Boundary handling with RepeatMode.
        if (idx < 0) {
            if (repeatMode == RepeatMode.REPEAT_ALL) {
                strategy.onListChanged(size);                       // reshuffle / reset cursor
                idx = (dir > 0) ? strategy.nextIndex(-1, size) : size - 1;
            } else {
                doStop();                                           // ran off the end
                return;
            }
        }
        if (idx < 0) { doStop(); return; }
        if (dir > 0) pushHistory();
        playSong(current.get(idx));
    }

    private void pushHistory() {
        if (currentSong != null) {
            history.addLast(currentSong);
            if (history.size() > 200) history.pollFirst(); // bound memory
        }
    }

    private void playSong(Song s) {
        currentSong = s;
        positionSeconds.set(0);
        output.load(s);
        output.start();
        this.state = new PlayingState();
        notifyOutsideLock(new PlaybackEvent(PlaybackEvent.Type.TRACK_CHANGED, s, "PLAYING", 0));
    }

    void doPause() {
        output.pause();
        this.state = new PausedState();
        notifyOutsideLock(new PlaybackEvent(PlaybackEvent.Type.STATE_CHANGED, currentSong, "PAUSED", position()));
    }
    void doResume() {
        output.start();
        this.state = new PlayingState();
        notifyOutsideLock(new PlaybackEvent(PlaybackEvent.Type.STATE_CHANGED, currentSong, "PLAYING", position()));
    }
    void doStop() {
        output.stop();
        currentSong = null;
        positionSeconds.set(0);
        this.state = new StoppedState();
        notifyOutsideLock(new PlaybackEvent(PlaybackEvent.Type.STATE_CHANGED, null, "STOPPED", 0));
    }

    /** Called by the playback clock thread once per simulated second. */
    void tick() {
        Song s; boolean ended;
        synchronized (lock) {
            if (!(state instanceof PlayingState) || currentSong == null) return;
            int pos = positionSeconds.incrementAndGet();
            s = currentSong;
            ended = pos >= s.durationSeconds;
        }
        // Notify position OUTSIDE the lock.
        notifyAll(new PlaybackEvent(PlaybackEvent.Type.POSITION, s, "PLAYING", position()));
        if (ended) {
            synchronized (lock) { advance(+1); }  // auto-advance at natural end
        }
    }

    /* ---- observer dispatch: snapshot under lock, notify outside, isolate exceptions ---- */
    private void notifyOutsideLock(PlaybackEvent e) {
        // CopyOnWriteArrayList gives a safe snapshot; we still guard each callback.
        notifyAll(e);
    }
    private void notifyAll(PlaybackEvent e) {
        for (PlaybackObserver o : observers) {
            try { o.onEvent(e); }
            catch (RuntimeException ex) {
                System.out.println("  [player] observer threw, isolated: " + ex.getMessage());
            }
        }
    }
}

/* =====================================================================================
 * DEMO — illustrates the key scenarios (not a test harness).
 * ===================================================================================== */

public class Solution {
    public static void main(String[] args) throws InterruptedException {
        // ----- build a tiny library -----
        MusicLibrary lib = MusicLibrary.getInstance();
        Artist queen = new Artist("a1", "Queen");
        Album aNight = new Album("al1", "A Night at the Opera", queen);
        Song s1 = new Song("s1", "Bohemian Rhapsody", 4, aNight);
        Song s2 = new Song("s2", "Love of My Life", 3, aNight);
        Song s3 = new Song("s3", "You're My Best Friend", 3, aNight);
        aNight.addTrack(s1); aNight.addTrack(s2); aNight.addTrack(s3);
        lib.registerAlbum(aNight);

        Artist daft = new Artist("a2", "Daft Punk");
        Album disc = new Album("al2", "Discovery", daft);
        Song s4 = new Song("s4", "One More Time", 3, disc);
        Song s5 = new Song("s5", "Harder Better Faster", 2, disc);
        disc.addTrack(s4); disc.addTrack(s5);
        lib.registerAlbum(disc);

        System.out.println("== Search 'queen' == " + lib.search("queen"));

        // ----- build the player -----
        MusicPlayer player = new MusicPlayer(new MockAudioOutput());
        player.addObserver(new NowPlayingDisplay());
        player.addObserver(new ScrobbleLogger());
        // Demonstrate observer isolation: a broken observer cannot crash playback.
        player.addObserver(e -> { if (e.type == PlaybackEvent.Type.TRACK_CHANGED) throw new RuntimeException("boom"); });

        Playlist favs = new Playlist("Favs");
        favs.add(s1); favs.add(s2); favs.add(s3);
        player.loadPlaylist(favs);

        System.out.println("\n== Scenario 1: play / pause / resume (State pattern) ==");
        player.play();                      // Stopped -> Playing (s1)
        player.pause();                     // Playing -> Paused
        player.resume();                    // Paused  -> Playing

        System.out.println("\n== Scenario 2: next / previous (Sequential) ==");
        player.next();                      // s1 -> s2
        player.next();                      // s2 -> s3
        player.previous();                  // history -> s2

        System.out.println("\n== Scenario 3: up-next queue priority + play-now ==");
        player.enqueue(s4);                 // queued behind current
        player.playNow(s5);                 // jumps the line -> plays s5 now
        player.next();                      // then the earlier-queued s4

        System.out.println("\n== Scenario 4: Shuffle + Repeat-All (Strategy + orthogonal enum) ==");
        player.setPlayStrategy(new ShuffleStrategy());
        player.setRepeatMode(RepeatMode.REPEAT_ALL);
        player.loadPlaylist(favs);
        player.play();
        for (int i = 0; i < 4; i++) player.next();   // wraps via reshuffle at cycle end

        System.out.println("\n== Scenario 5: Repeat-One ==");
        player.setPlayStrategy(new SequentialStrategy());
        player.setRepeatMode(RepeatMode.REPEAT_ONE);
        player.loadPlaylist(favs);
        player.play();
        player.next();                      // stays on the same song

        System.out.println("\n== Scenario 6: background clock auto-advances at track end ==");
        player.setRepeatMode(RepeatMode.NONE);
        player.setPlayStrategy(new SequentialStrategy());
        Playlist shortList = new Playlist("Short");
        shortList.add(s5); shortList.add(s4);   // 2s then 3s
        player.loadPlaylist(shortList);
        player.play();
        // simulate the playback clock thread ticking once per "second"
        Thread clock = new Thread(() -> {
            for (int t = 0; t < 8; t++) {
                player.tick();
                try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            }
        }, "playback-clock");
        clock.start();
        clock.join();

        System.out.println("\n== Scenario 7: Iterator over a playlist ==");
        System.out.print("  Favs: ");
        for (Song s : favs) System.out.print(s.title + " | ");
        System.out.println();

        System.out.println("\n== Done. nowPlaying=" + player.nowPlaying() + ", state=" + player.stateName());
    }
}
