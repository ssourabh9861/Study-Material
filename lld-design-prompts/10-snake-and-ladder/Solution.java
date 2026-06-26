/*
 * =============================================================================
 * LLD: Snake and Ladder  —  Single-file REVIEW / REVISION artifact
 * =============================================================================
 *
 * This file is meant to be READ and revised, not compiled/run in an interview.
 * It is, however, self-contained and correct-by-inspection (no TODOs / no
 * omitted bodies), with a main() that illustrates the key scenarios.
 *
 * -----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -----------------------------------------------------------------------------
 * Functional:
 *   1. Board size fixed (100) or configurable N? Square grid only?
 *   2. How many players? Min 2? Fixed turn order or randomized?
 *   3. One die or multiple? Always 6 faces? Need deterministic (seeded) dice?
 *   4. Snakes/ladders given by config or random? Can two entities share a start
 *      cell? Are chains (ladder top == snake head) allowed?
 *   5. Win = land EXACTLY on last cell, or reach/pass? Overshoot -> stay or
 *      bounce back?
 *   6. Bonus roll on max face? Cap on consecutive maxes (e.g. 3 -> forfeit)?
 *   7. Players start on cell 1 or off-board at 0?
 *   8. If a ladder lands you on a snake head, do you slide again (transitive)?
 * Non-functional:
 *   9. Single-process turn-based sim, or server running many concurrent games?
 *  10. Which variants must we anticipate (crocodiles, mines, teleports)?
 *  11. Need a move log / event stream (replay, UI, analytics)?
 *  12. Must dice be injectable for reproducible tests?
 * Scope:
 *  13. Out: networking, persistence, UI, matchmaking, AI. In: the rules engine.
 *
 * -----------------------------------------------------------------------------
 * DESIGN PATTERNS USED
 * -----------------------------------------------------------------------------
 *   Strategy  -> Dice (Single/Multi/Seeded), WinningStrategy (Bounce/Stay/Pass)
 *   Factory   -> BoardEntityFactory (build + validate snakes/ladders/specials)
 *   Observer  -> GameObserver / ConsoleLogger (decouple engine from reactions)
 *   Builder   -> GameConfig.Builder (immutable, fluent assembly)
 *   Generalization -> snakes/ladders/crocodiles are one BoardEntity concept
 *
 * SOLID: SRP (one job/class), OCP (extend via new classes), LSP (substitutable
 * abstractions), ISP (small interfaces), DIP (engine depends on abstractions
 * injected via GameConfig).
 * =============================================================================
 */

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class Solution {

    // =========================================================================
    // Board entities (Factory builds these; generalization over Snake/Ladder/..)
    // =========================================================================

    enum EntityKind { SNAKE, LADDER, CROCODILE }

    /** A thing sitting on a cell that, when landed on, sends you elsewhere. */
    interface BoardEntity {
        int start();          // the cell you land on
        int end();            // where it sends you
        EntityKind kind();
    }

    static abstract class AbstractJump implements BoardEntity {
        private final int start;
        private final int end;
        AbstractJump(int start, int end) { this.start = start; this.end = end; }
        public int start() { return start; }
        public int end()   { return end; }
        @Override public String toString() {
            return kind() + "(" + start + "->" + end + ")";
        }
    }

    /** Snake: head (high) -> tail (low). Always goes DOWN. */
    static final class Snake extends AbstractJump {
        Snake(int head, int tail) { super(head, tail); }
        public EntityKind kind() { return EntityKind.SNAKE; }
    }

    /** Ladder: bottom (low) -> top (high). Always goes UP. */
    static final class Ladder extends AbstractJump {
        Ladder(int bottom, int top) { super(bottom, top); }
        public EntityKind kind() { return EntityKind.LADDER; }
    }

    /** Example "special" cell to show extensibility: behaves like a snake. */
    static final class Crocodile extends AbstractJump {
        Crocodile(int mouth, int dropTo) { super(mouth, dropTo); }
        public EntityKind kind() { return EntityKind.CROCODILE; }
    }

    // =========================================================================
    // FACTORY  — centralizes construction + validation (fail fast)
    // =========================================================================

    static final class BoardEntityFactory {

        static BoardEntity snake(int head, int tail) {
            if (head <= tail)
                throw new IllegalArgumentException("Snake must go down: " + head + "->" + tail);
            return new Snake(head, tail);
        }

        static BoardEntity ladder(int bottom, int top) {
            if (bottom >= top)
                throw new IllegalArgumentException("Ladder must go up: " + bottom + "->" + top);
            return new Ladder(bottom, top);
        }

        static BoardEntity crocodile(int mouth, int dropTo) {
            if (mouth <= dropTo)
                throw new IllegalArgumentException("Crocodile must drop you: " + mouth + "->" + dropTo);
            return new Crocodile(mouth, dropTo);
        }

        /**
         * Build an immutable Board from a size and a list of entities, validating:
         *  - every position within (0, size) exclusive (no jump on 0 or final cell)
         *  - no two entities share a start cell
         */
        static Board buildBoard(int size, List<BoardEntity> entities) {
            if (size < 2) throw new IllegalArgumentException("Board size must be >= 2");
            Map<Integer, BoardEntity> map = new HashMap<>();
            for (BoardEntity e : entities) {
                int s = e.start();
                if (s <= 0 || s >= size)
                    throw new IllegalArgumentException("Entity start out of range (0," + size + "): " + e);
                if (e.end() < 0 || e.end() > size)
                    throw new IllegalArgumentException("Entity end out of range: " + e);
                if (map.containsKey(s))
                    throw new IllegalArgumentException("Two entities on cell " + s + ": " + map.get(s) + " & " + e);
                map.put(s, e);
            }
            return new Board(size, map);
        }
    }

    // =========================================================================
    // Cell + Board (Board is a 1-D track 0..N; grid layout is a render concern)
    // =========================================================================

    static final class Cell {
        final int position;
        final BoardEntity entity; // may be null
        Cell(int position, BoardEntity entity) { this.position = position; this.entity = entity; }
    }

    static final class Board {
        private final int size;                          // final cell index (e.g. 100)
        private final Map<Integer, BoardEntity> entities; // unmodifiable -> safe to share
        private final Cell[] cells;
        private static final int MAX_JUMP_CHAIN = 16;    // cycle/chain guard

        Board(int size, Map<Integer, BoardEntity> entities) {
            this.size = size;
            this.entities = Collections.unmodifiableMap(new HashMap<>(entities));
            this.cells = new Cell[size + 1];
            for (int i = 0; i <= size; i++) cells[i] = new Cell(i, entities.get(i));
        }

        int getSize() { return size; }

        BoardEntity entityAt(int pos) { return entities.get(pos); }

        Cell cellAt(int pos) { return cells[pos]; }

        /**
         * Apply the entity at the landed cell, transitively (ladder -> snake -> ...),
         * bounded by MAX_JUMP_CHAIN and a visited-set to break misconfigured cycles.
         */
        int resolveFinalPosition(int landed) {
            int pos = landed;
            Set<Integer> visited = new HashSet<>();
            int hops = 0;
            BoardEntity e;
            while ((e = entities.get(pos)) != null && hops++ < MAX_JUMP_CHAIN) {
                if (!visited.add(pos)) break; // cycle detected -> stop where we are
                pos = e.end();
            }
            return pos;
        }
    }

    // =========================================================================
    // STRATEGY  — Dice
    // =========================================================================

    static final class RollResult {
        final int total;
        final List<Integer> values;
        final boolean isAllMax; // all dice showed their max face -> bonus candidate
        RollResult(int total, List<Integer> values, boolean isAllMax) {
            this.total = total;
            this.values = Collections.unmodifiableList(values);
            this.isAllMax = isAllMax;
        }
        @Override public String toString() { return values + " = " + total; }
    }

    interface Dice {
        RollResult roll();
        int maxFaceValue(); // per-die max (for bonus-roll rule)
    }

    /** One fair die with configurable faces (default 6). */
    static class SingleDie implements Dice {
        protected final int faces;
        protected final Random rng;
        SingleDie() { this(6, new Random()); }
        SingleDie(int faces) { this(faces, new Random()); }
        SingleDie(int faces, Random rng) {
            if (faces < 2) throw new IllegalArgumentException("die needs >= 2 faces");
            this.faces = faces; this.rng = rng;
        }
        public RollResult roll() {
            int v = 1 + rng.nextInt(faces);
            List<Integer> vals = new ArrayList<>(); vals.add(v);
            return new RollResult(v, vals, v == faces);
        }
        public int maxFaceValue() { return faces; }
    }

    /** Deterministic die for reproducible tests (seeded RNG). */
    static final class SeededDie extends SingleDie {
        SeededDie(long seed) { super(6, new Random(seed)); }
        SeededDie(int faces, long seed) { super(faces, new Random(seed)); }
    }

    /** Composite of k dice; engine only consumes the total. */
    static final class MultiDice implements Dice {
        private final List<SingleDie> dice = new ArrayList<>();
        private final int faces;
        MultiDice(int count, int faces, Random rng) {
            if (count < 1) throw new IllegalArgumentException("need >= 1 die");
            this.faces = faces;
            for (int i = 0; i < count; i++) dice.add(new SingleDie(faces, rng));
        }
        public RollResult roll() {
            int total = 0; boolean allMax = true;
            List<Integer> vals = new ArrayList<>();
            for (SingleDie d : dice) {
                int v = d.roll().total;
                vals.add(v); total += v;
                if (v != faces) allMax = false;
            }
            return new RollResult(total, vals, allMax);
        }
        public int maxFaceValue() { return faces; }
    }

    // =========================================================================
    // STRATEGY  — Winning / overshoot policy
    // =========================================================================

    interface WinningStrategy {
        /** Candidate position after moving `steps` from `current` on a board of `size`. */
        int nextPosition(int current, int steps, int size);
        boolean hasWon(int position, int size);
    }

    /** Must land exactly on the last cell; overshoot reflects back. (Default) */
    static final class ExactWithBounceBack implements WinningStrategy {
        public int nextPosition(int current, int steps, int size) {
            int target = current + steps;
            if (target > size) target = size - (target - size); // reflect
            return Math.max(target, 0);
        }
        public boolean hasWon(int position, int size) { return position == size; }
    }

    /** Must land exactly; overshoot means you stay put. */
    static final class ExactStay implements WinningStrategy {
        public int nextPosition(int current, int steps, int size) {
            int target = current + steps;
            return target > size ? current : target;
        }
        public boolean hasWon(int position, int size) { return position == size; }
    }

    /** Reaching or passing the last cell wins. */
    static final class ReachOrPass implements WinningStrategy {
        public int nextPosition(int current, int steps, int size) {
            return Math.min(current + steps, size);
        }
        public boolean hasWon(int position, int size) { return position >= size; }
    }

    // =========================================================================
    // Player
    // =========================================================================

    static final class Player {
        final String id;
        private int position = 0;          // start off-board
        private int consecutiveMaxRolls = 0;
        Player(String id) { this.id = Objects.requireNonNull(id); }
        int position() { return position; }
        void setPosition(int p) { position = p; }
        int consecutiveMaxRolls() { return consecutiveMaxRolls; }
        void incMax() { consecutiveMaxRolls++; }
        void resetMax() { consecutiveMaxRolls = 0; }
        @Override public String toString() { return id + "@" + position; }
    }

    // =========================================================================
    // Bonus-roll rule (configurable; localized to the turn loop)
    // =========================================================================

    static final class BonusRule {
        final boolean bonusOnMax;        // extra turn when all dice show max
        final int forfeitAfterConsec;    // e.g. 3 consecutive maxes forfeits turn
        BonusRule(boolean bonusOnMax, int forfeitAfterConsec) {
            this.bonusOnMax = bonusOnMax;
            this.forfeitAfterConsec = forfeitAfterConsec;
        }
        static BonusRule classic() { return new BonusRule(true, 3); }
        static BonusRule none() { return new BonusRule(false, Integer.MAX_VALUE); }
    }

    // =========================================================================
    // OBSERVER  — game events
    // =========================================================================

    enum EventType { MOVE, JUMP, WIN }

    static final class GameEvent {
        final EventType type;
        final Player player;
        final RollResult roll;   // may be null for WIN summary
        final int from;
        final int to;
        final EntityKind jumpKind; // non-null only for JUMP
        GameEvent(EventType type, Player player, RollResult roll, int from, int to, EntityKind jumpKind) {
            this.type = type; this.player = player; this.roll = roll;
            this.from = from; this.to = to; this.jumpKind = jumpKind;
        }
    }

    interface GameObserver {
        void onMove(GameEvent e);
        void onJump(GameEvent e);
        void onWin(GameEvent e);
    }

    static final class ConsoleLogger implements GameObserver {
        public void onMove(GameEvent e) {
            System.out.printf("  %s rolls %s : %d -> %d%n",
                    e.player.id, e.roll, e.from, e.to);
        }
        public void onJump(GameEvent e) {
            System.out.printf("    >> %s at %d : %d -> %d%n",
                    e.jumpKind, e.from, e.from, e.to);
        }
        public void onWin(GameEvent e) {
            System.out.printf("*** %s WINS (reached %d) ***%n", e.player.id, e.to);
        }
    }

    // =========================================================================
    // Value object returned per turn (handy for tests/UI)
    // =========================================================================

    static final class MoveResult {
        final Player player;
        final RollResult roll;
        final int from;
        final int to;
        final boolean won;
        final boolean bonusTurn;
        MoveResult(Player player, RollResult roll, int from, int to, boolean won, boolean bonusTurn) {
            this.player = player; this.roll = roll; this.from = from;
            this.to = to; this.won = won; this.bonusTurn = bonusTurn;
        }
    }

    // =========================================================================
    // BUILDER  — immutable game configuration
    // =========================================================================

    static final class GameConfig {
        final Board board;
        final Dice dice;
        final WinningStrategy winStrategy;
        final BonusRule bonusRule;
        final List<Player> players;
        final List<GameObserver> observers;

        private GameConfig(Builder b) {
            this.board = Objects.requireNonNull(b.board, "board");
            this.dice = b.dice != null ? b.dice : new SingleDie();
            this.winStrategy = b.winStrategy != null ? b.winStrategy : new ExactWithBounceBack();
            this.bonusRule = b.bonusRule != null ? b.bonusRule : BonusRule.classic();
            if (b.players.size() < 1)
                throw new IllegalArgumentException("need at least 1 player");
            this.players = Collections.unmodifiableList(new ArrayList<>(b.players));
            this.observers = Collections.unmodifiableList(new ArrayList<>(b.observers));
        }

        static Builder builder() { return new Builder(); }

        static final class Builder {
            private Board board;
            private Dice dice;
            private WinningStrategy winStrategy;
            private BonusRule bonusRule;
            private final List<Player> players = new ArrayList<>();
            private final List<GameObserver> observers = new ArrayList<>();

            Builder board(Board b) { this.board = b; return this; }
            Builder dice(Dice d) { this.dice = d; return this; }
            Builder winStrategy(WinningStrategy w) { this.winStrategy = w; return this; }
            Builder bonusRule(BonusRule r) { this.bonusRule = r; return this; }
            Builder addPlayer(Player p) { this.players.add(p); return this; }
            Builder addPlayer(String id) { this.players.add(new Player(id)); return this; }
            Builder addObserver(GameObserver o) { this.observers.add(o); return this; }
            GameConfig build() { return new GameConfig(this); }
        }
    }

    // =========================================================================
    // Game engine  (single-threaded turn loop; depends only on abstractions)
    // =========================================================================

    static final class Game {
        private final Board board;
        private final Dice dice;
        private final WinningStrategy winStrategy;
        private final BonusRule bonusRule;
        private final List<GameObserver> observers;
        private final Deque<Player> turnOrder = new ArrayDeque<>();
        private boolean over = false;
        private Player winner = null;

        Game(GameConfig cfg) {
            this.board = cfg.board;
            this.dice = cfg.dice;
            this.winStrategy = cfg.winStrategy;
            this.bonusRule = cfg.bonusRule;
            this.observers = cfg.observers;
            this.turnOrder.addAll(cfg.players);
        }

        boolean isOver() { return over; }
        Player getWinner() { return winner; }

        /** Play exactly one turn (one full roll, possibly a bonus continues for same player). */
        MoveResult playTurn() {
            if (over) throw new IllegalStateException("game is already over");
            Player p = turnOrder.peekFirst(); // keep at head; we decide re-enqueue at end

            RollResult r = dice.roll();
            int from = p.position();

            int landed = winStrategy.nextPosition(from, r.total, board.getSize());
            int finalPos = board.resolveFinalPosition(landed);

            // Fire jump event if a board entity moved the player from the landed cell.
            BoardEntity jumped = board.entityAt(landed);
            if (jumped != null && finalPos != landed) {
                notifyJump(new GameEvent(EventType.JUMP, p, r, landed, finalPos, jumped.kind()));
            }

            p.setPosition(finalPos);
            notifyMove(new GameEvent(EventType.MOVE, p, r, from, finalPos, null));

            // Win check
            if (winStrategy.hasWon(finalPos, board.getSize())) {
                over = true; winner = p;
                turnOrder.removeFirst();
                notifyWin(new GameEvent(EventType.WIN, p, r, from, finalPos, null));
                return new MoveResult(p, r, from, finalPos, true, false);
            }

            // Bonus-roll handling with consecutive-max forfeit guard.
            boolean bonus = false;
            if (bonusRule.bonusOnMax && r.isAllMax) {
                p.incMax();
                if (p.consecutiveMaxRolls() >= bonusRule.forfeitAfterConsec) {
                    // Too many maxes in a row -> forfeit: lose the bonus AND this turn's net move?
                    // Classic rule: forfeit the turn -> pass to next player and reset counter.
                    p.resetMax();
                    rotate();
                } else {
                    bonus = true; // same player goes again; do NOT rotate
                }
            } else {
                p.resetMax();
                rotate();
            }
            return new MoveResult(p, r, from, finalPos, false, bonus);
        }

        /** Drive the whole game to completion and return the winner. */
        Player play() {
            while (!over && !turnOrder.isEmpty()) playTurn();
            return winner;
        }

        private void rotate() {
            Player p = turnOrder.removeFirst();
            turnOrder.addLast(p);
        }

        private void notifyMove(GameEvent e) { for (GameObserver o : observers) o.onMove(e); }
        private void notifyJump(GameEvent e) { for (GameObserver o : observers) o.onJump(e); }
        private void notifyWin(GameEvent e)  { for (GameObserver o : observers) o.onWin(e); }
    }

    // =========================================================================
    // Demo / illustrative main (NOT for grading; just shows usage)
    // =========================================================================

    private static List<BoardEntity> classicEntities() {
        List<BoardEntity> es = new ArrayList<>();
        // Ladders (up)
        es.add(BoardEntityFactory.ladder(2, 38));
        es.add(BoardEntityFactory.ladder(7, 14));
        es.add(BoardEntityFactory.ladder(8, 31));
        es.add(BoardEntityFactory.ladder(28, 84));
        es.add(BoardEntityFactory.ladder(36, 44));
        es.add(BoardEntityFactory.ladder(51, 67));
        es.add(BoardEntityFactory.ladder(71, 91));
        // Snakes (down)
        es.add(BoardEntityFactory.snake(16, 6));
        es.add(BoardEntityFactory.snake(47, 26));
        es.add(BoardEntityFactory.snake(49, 11));
        es.add(BoardEntityFactory.snake(56, 53));
        es.add(BoardEntityFactory.snake(62, 19));
        es.add(BoardEntityFactory.snake(87, 24));
        es.add(BoardEntityFactory.snake(98, 78)); // near-finish snake makes exact-finish interesting
        // A "special" cell to demonstrate extensibility
        es.add(BoardEntityFactory.crocodile(64, 40));
        return es;
    }

    public static void main(String[] args) {

        // -------- Scenario 1: classic 100-board, 2 dice... no, single die, 3 players,
        //          seeded for reproducibility, exact-finish-with-bounce-back. --------
        System.out.println("=== Scenario 1: classic 100, single seeded die, exact+bounce ===");
        Board board = BoardEntityFactory.buildBoard(100, classicEntities());
        GameConfig cfg1 = GameConfig.builder()
                .board(board)
                .dice(new SeededDie(42))                 // deterministic
                .winStrategy(new ExactWithBounceBack())  // overshoot reflects back
                .bonusRule(BonusRule.classic())          // bonus on 6, forfeit after 3
                .addPlayer("Alice")
                .addPlayer("Bob")
                .addPlayer("Carol")
                .addObserver(new ConsoleLogger())
                .build();
        Game g1 = new Game(cfg1);
        Player w1 = g1.play();
        System.out.println("Winner: " + (w1 == null ? "none" : w1.id) + "\n");

        // -------- Scenario 2: multiple dice + reach-or-pass win + no bonus. --------
        System.out.println("=== Scenario 2: two dice, reach-or-pass, no bonus ===");
        Board board2 = BoardEntityFactory.buildBoard(50, List.of(
                BoardEntityFactory.ladder(3, 22),
                BoardEntityFactory.ladder(5, 8),
                BoardEntityFactory.snake(27, 1),
                BoardEntityFactory.snake(40, 3),
                BoardEntityFactory.snake(43, 18)
        ));
        GameConfig cfg2 = GameConfig.builder()
                .board(board2)
                .dice(new MultiDice(2, 6, new Random(7))) // two seeded dice
                .winStrategy(new ReachOrPass())
                .bonusRule(BonusRule.none())
                .addPlayer("Dave")
                .addPlayer("Eve")
                .addObserver(new ConsoleLogger())
                .build();
        Player w2 = new Game(cfg2).play();
        System.out.println("Winner: " + (w2 == null ? "none" : w2.id) + "\n");

        // -------- Scenario 3: validation fails fast on a bad board. --------
        System.out.println("=== Scenario 3: factory validation (expected to throw) ===");
        try {
            BoardEntityFactory.buildBoard(20, List.of(
                    BoardEntityFactory.ladder(5, 15),
                    BoardEntityFactory.snake(5, 2) // collision: two entities on cell 5
            ));
        } catch (IllegalArgumentException ex) {
            System.out.println("Caught expected: " + ex.getMessage());
        }
        try {
            BoardEntityFactory.snake(3, 9); // snake must go down
        } catch (IllegalArgumentException ex) {
            System.out.println("Caught expected: " + ex.getMessage());
        }

        // -------- Scenario 4: demonstrate transitive (chained) jump resolution. --------
        System.out.println("\n=== Scenario 4: chained jump (ladder -> snake) resolution ===");
        Board chain = BoardEntityFactory.buildBoard(30, List.of(
                BoardEntityFactory.ladder(4, 20),  // land 4 -> 20 ...
                BoardEntityFactory.snake(20, 6)    // ... 20 is a snake head -> 6
        ));
        System.out.println("Land on 4 resolves to: " + chain.resolveFinalPosition(4)
                + " (4 -> ladder -> 20 -> snake -> 6)");
    }
}
