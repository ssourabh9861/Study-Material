/* =============================================================================
 * Tic-Tac-Toe — single-file LLD review/revision artifact.
 *
 * This file is for READING and REVISION before an interview. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). You do NOT need to compile
 * or run it; the main() only illustrates usage.
 *
 * -----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING:
 *   Functional
 *     - Board size: fixed 3x3 or general NxN? (Assume NxN, default 3.)
 *     - Win condition: full row/col/diagonal of one symbol, or K-in-a-row? (Full line.)
 *     - Number of players: exactly 2 or M players with M symbols? (2; generalizable.)
 *     - Player types: human only, or human-vs-bot/bot-vs-bot? Bot strength
 *       (random / rule-based / optimal minimax)? (Human + bot via strategy.)
 *     - Symbols: fixed X/O or arbitrary? (Per-player symbol, default X/O.)
 *     - Undo/redo? Move history / replay? (Undo supported via history.)
 *     - Draw detection: full board no winner, or detect impossible-win early? (Full board.)
 *   Non-functional
 *     - Win-check performance: re-scan vs O(1) per move? (O(1) incremental counters.)
 *     - Concurrency: single game loop vs server hosting many concurrent games?
 *       (Core single-threaded; per-game lock for a server.)
 *     - Input validation: out-of-bounds, occupied cell, move after game over.
 *     - Persistence/networking? (In-memory v1.)
 *   Scope
 *     - Console vs GUI/API? (Design the engine; I/O is a thin adapter.)
 *     - Multi-round scoring / tournament? (Out of scope v1.)
 *
 * PATTERNS DEMONSTRATED:
 *   - Strategy: WinningStrategy (counters vs brute force) and MoveStrategy (random/minimax)
 *   - Factory Method: PlayerFactory
 *   - State (lite): GameStatus enum + guards
 *   - Value Object: immutable Move
 *   - Composition: Game *-- Board *-- Cell; strategies/players injected (DIP/OCP)
 * ============================================================================= */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.function.BiFunction;

/* ------------------------------ Enums --------------------------------------- */

enum Symbol {
    X, O, EMPTY;

    /** Display character for rendering. */
    char glyph() {
        switch (this) {
            case X: return 'X';
            case O: return 'O';
            default: return '.';
        }
    }
}

enum GameStatus { IN_PROGRESS, WIN, DRAW }

/* ------------------------------ Cell ---------------------------------------- */

class Cell {
    private final int row;
    private final int col;
    private Symbol symbol;

    Cell(int row, int col) {
        this.row = row;
        this.col = col;
        this.symbol = Symbol.EMPTY;
    }

    boolean isEmpty()            { return symbol == Symbol.EMPTY; }
    Symbol  getSymbol()          { return symbol; }
    void    setSymbol(Symbol s)  { this.symbol = s; }
    int     getRow()             { return row; }
    int     getCol()             { return col; }
}

/* ------------------------------ Move (value object) ------------------------- */

/** Immutable record of an intended/applied action; enables history, undo, replay. */
final class Move {
    final Player player;
    final int row;
    final int col;

    Move(Player player, int row, int col) {
        this.player = player;
        this.row = row;
        this.col = col;
    }

    @Override public String toString() {
        return player.getSymbol().glyph() + "@(" + row + "," + col + ")";
    }
}

/* ------------------------------ Board --------------------------------------- */

class Board {
    private final int size;
    private final Cell[][] grid;
    private int filledCount = 0;

    Board(int size) {
        if (size < 1) throw new IllegalArgumentException("Board size must be >= 1");
        this.size = size;
        this.grid = new Cell[size][size];
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                grid[r][c] = new Cell(r, c);
    }

    int getSize() { return size; }

    boolean inBounds(int r, int c) {
        return r >= 0 && r < size && c >= 0 && c < size;
    }

    Cell getCell(int r, int c) {
        if (!inBounds(r, c)) throw new IllegalArgumentException("Out of bounds: (" + r + "," + c + ")");
        return grid[r][c];
    }

    /** Place the move's player's symbol; validates bounds + occupancy. */
    void place(Move m) {
        Cell cell = getCell(m.row, m.col);
        if (!cell.isEmpty())
            throw new IllegalStateException("Cell (" + m.row + "," + m.col + ") already occupied");
        cell.setSymbol(m.player.getSymbol());
        filledCount++;
    }

    /** Used by undo. */
    void clear(int r, int c) {
        Cell cell = getCell(r, c);
        if (!cell.isEmpty()) {
            cell.setSymbol(Symbol.EMPTY);
            filledCount--;
        }
    }

    boolean isFull() { return filledCount == size * size; }

    List<int[]> emptyPositions() {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                if (grid[r][c].isEmpty()) out.add(new int[]{r, c});
        return out;
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                sb.append(' ').append(grid[r][c].getSymbol().glyph()).append(' ');
                if (c < size - 1) sb.append('|');
            }
            sb.append('\n');
            if (r < size - 1) {
                for (int c = 0; c < size; c++) {
                    sb.append("---");
                    if (c < size - 1) sb.append('+');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}

/* ------------------------------ WinningStrategy (Strategy) ------------------ */

interface WinningStrategy {
    /** Return the winning Symbol if the just-played move wins, else null. */
    Symbol checkWinner(Board board, Move lastMove);

    /** Reverse any internal bookkeeping for an undone move. No-op for stateless impls. */
    default void undo(Move move) { }
}

/**
 * O(1)-per-move win detection using incremental counters per player.
 * rowCounts[s][r], colCounts[s][c], diagCounts[s], antiDiagCounts[s] where s is
 * the player's symbol ordinal. A line wins when its count reaches N.
 */
class CountersWinningStrategy implements WinningStrategy {
    private final int n;
    private final int[][] rowCounts;     // [symbol][row]
    private final int[][] colCounts;     // [symbol][col]
    private final int[]   diagCounts;    // [symbol]
    private final int[]   antiDiagCounts;// [symbol]

    CountersWinningStrategy(int n) {
        this.n = n;
        int symbols = Symbol.values().length; // X, O, EMPTY (EMPTY unused but keeps indexing simple)
        rowCounts = new int[symbols][n];
        colCounts = new int[symbols][n];
        diagCounts = new int[symbols];
        antiDiagCounts = new int[symbols];
    }

    @Override public Symbol checkWinner(Board board, Move m) {
        int s = m.player.getSymbol().ordinal();
        int r = m.row, c = m.col;

        if (++rowCounts[s][r] == n) return m.player.getSymbol();
        if (++colCounts[s][c] == n) return m.player.getSymbol();
        if (r == c && ++diagCounts[s] == n) return m.player.getSymbol();
        if (r + c == n - 1 && ++antiDiagCounts[s] == n) return m.player.getSymbol();
        return null;
    }

    @Override public void undo(Move m) {
        int s = m.player.getSymbol().ordinal();
        int r = m.row, c = m.col;
        rowCounts[s][r]--;
        colCounts[s][c]--;
        if (r == c) diagCounts[s]--;
        if (r + c == n - 1) antiDiagCounts[s]--;
    }
}

/** O(N^2) reference implementation: re-scans the affected lines around the move. */
class BruteForceWinningStrategy implements WinningStrategy {
    @Override public Symbol checkWinner(Board board, Move m) {
        int n = board.getSize();
        Symbol s = m.player.getSymbol();

        boolean rowWin = true, colWin = true, diagWin = true, antiWin = true;
        for (int i = 0; i < n; i++) {
            if (board.getCell(m.row, i).getSymbol() != s) rowWin = false;
            if (board.getCell(i, m.col).getSymbol() != s) colWin = false;
            if (board.getCell(i, i).getSymbol() != s) diagWin = false;
            if (board.getCell(i, n - 1 - i).getSymbol() != s) antiWin = false;
        }
        return (rowWin || colWin || diagWin || antiWin) ? s : null;
    }
    // stateless: default undo no-op is fine
}

/* ------------------------------ MoveStrategy (Strategy) --------------------- */

interface MoveStrategy {
    /** Choose an empty cell for the given symbol; returns {row, col}. */
    int[] chooseMove(Board board, Symbol symbol);
}

class RandomMoveStrategy implements MoveStrategy {
    private final Random random;
    RandomMoveStrategy()            { this(new Random()); }
    RandomMoveStrategy(long seed)   { this.random = new Random(seed); }

    @Override public int[] chooseMove(Board board, Symbol symbol) {
        List<int[]> empties = board.emptyPositions();
        if (empties.isEmpty()) throw new IllegalStateException("No moves available");
        return empties.get(random.nextInt(empties.size()));
    }
}

/**
 * Optimal play for small boards via minimax + alpha-beta pruning.
 * Practical only for small N (search is exponential). For large N use a
 * depth-limited heuristic variant — the engine is unaffected either way.
 */
class MinimaxMoveStrategy implements MoveStrategy {
    private final Symbol me;
    private final Symbol opponent;

    MinimaxMoveStrategy(Symbol me, Symbol opponent) {
        this.me = me;
        this.opponent = opponent;
    }

    @Override public int[] chooseMove(Board board, Symbol symbol) {
        int bestScore = Integer.MIN_VALUE;
        int[] best = null;
        for (int[] pos : board.emptyPositions()) {
            board.getCell(pos[0], pos[1]).setSymbol(me);
            int score = minimax(board, false, Integer.MIN_VALUE, Integer.MAX_VALUE, pos[0], pos[1], me);
            board.getCell(pos[0], pos[1]).setSymbol(Symbol.EMPTY);
            if (score > bestScore) { bestScore = score; best = pos; }
        }
        if (best == null) throw new IllegalStateException("No moves available");
        return best;
    }

    private int minimax(Board board, boolean maximizing, int alpha, int beta,
                        int lastR, int lastC, Symbol lastSymbol) {
        Symbol w = lineWinner(board, lastR, lastC, lastSymbol);
        if (w == me) return 10;
        if (w == opponent) return -10;
        if (board.emptyPositions().isEmpty()) return 0; // draw

        if (maximizing) {
            int best = Integer.MIN_VALUE;
            for (int[] pos : board.emptyPositions()) {
                board.getCell(pos[0], pos[1]).setSymbol(me);
                best = Math.max(best, minimax(board, false, alpha, beta, pos[0], pos[1], me));
                board.getCell(pos[0], pos[1]).setSymbol(Symbol.EMPTY);
                alpha = Math.max(alpha, best);
                if (beta <= alpha) break; // prune
            }
            return best;
        } else {
            int best = Integer.MAX_VALUE;
            for (int[] pos : board.emptyPositions()) {
                board.getCell(pos[0], pos[1]).setSymbol(opponent);
                best = Math.min(best, minimax(board, true, alpha, beta, pos[0], pos[1], opponent));
                board.getCell(pos[0], pos[1]).setSymbol(Symbol.EMPTY);
                beta = Math.min(beta, best);
                if (beta <= alpha) break; // prune
            }
            return best;
        }
    }

    /** Local win check around the last move (full-line rule). */
    private Symbol lineWinner(Board board, int r, int c, Symbol s) {
        int n = board.getSize();
        boolean rowWin = true, colWin = true, diagWin = (r == c), antiWin = (r + c == n - 1);
        for (int i = 0; i < n; i++) {
            if (board.getCell(r, i).getSymbol() != s) rowWin = false;
            if (board.getCell(i, c).getSymbol() != s) colWin = false;
            if (diagWin && board.getCell(i, i).getSymbol() != s) diagWin = false;
            if (antiWin && board.getCell(i, n - 1 - i).getSymbol() != s) antiWin = false;
        }
        return (rowWin || colWin || diagWin || antiWin) ? s : null;
    }
}

/* ------------------------------ Players ------------------------------------- */

abstract class Player {
    protected final String name;
    protected final Symbol symbol;

    Player(String name, Symbol symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    String getName()   { return name; }
    Symbol getSymbol() { return symbol; }

    /** Produce the next move for this player given the current board. */
    abstract Move makeMove(Board board);
}

/**
 * Human player. To keep the engine I/O-free, the source of the next (row,col)
 * is injected as a function of the board (in a console app this would read stdin).
 */
class HumanPlayer extends Player {
    private final BiFunction<Player, Board, int[]> inputSource;

    HumanPlayer(String name, Symbol symbol, BiFunction<Player, Board, int[]> inputSource) {
        super(name, symbol);
        this.inputSource = inputSource;
    }

    @Override Move makeMove(Board board) {
        int[] pos = inputSource.apply(this, board);
        return new Move(this, pos[0], pos[1]);
    }
}

/** Bot player delegates move selection to a pluggable MoveStrategy. */
class BotPlayer extends Player {
    private final MoveStrategy strategy;

    BotPlayer(String name, Symbol symbol, MoveStrategy strategy) {
        super(name, symbol);
        this.strategy = strategy;
    }

    @Override Move makeMove(Board board) {
        int[] pos = strategy.chooseMove(board, symbol);
        return new Move(this, pos[0], pos[1]);
    }
}

/* ------------------------------ PlayerFactory (Factory Method) -------------- */

class PlayerFactory {
    static Player createHuman(String name, Symbol symbol,
                              BiFunction<Player, Board, int[]> inputSource) {
        return new HumanPlayer(name, symbol, inputSource);
    }

    static Player createRandomBot(String name, Symbol symbol) {
        return new BotPlayer(name, symbol, new RandomMoveStrategy());
    }

    static Player createRandomBot(String name, Symbol symbol, long seed) {
        return new BotPlayer(name, symbol, new RandomMoveStrategy(seed));
    }

    static Player createUnbeatableBot(String name, Symbol symbol, Symbol opponentSymbol) {
        return new BotPlayer(name, symbol, new MinimaxMoveStrategy(symbol, opponentSymbol));
    }
}

/* ------------------------------ Game (orchestrator) ------------------------- */

class Game {
    private final Board board;
    private final List<Player> players;
    private final WinningStrategy winStrategy;
    private final Deque<Move> history = new ArrayDeque<>();
    private int currentIdx = 0;
    private GameStatus status = GameStatus.IN_PROGRESS;
    private Player winner = null;

    Game(int size, List<Player> players, WinningStrategy winStrategy) {
        if (players == null || players.size() < 2)
            throw new IllegalArgumentException("Need at least 2 players");
        this.board = new Board(size);
        this.players = new ArrayList<>(players);
        this.winStrategy = winStrategy;
    }

    GameStatus getStatus() { return status; }
    Player     getWinner() { return winner; }
    Board      getBoard()  { return board; }
    Player     currentPlayer() { return players.get(currentIdx); }

    /** Ask the current player for a move and apply it. */
    void playTurn() {
        if (status != GameStatus.IN_PROGRESS)
            throw new IllegalStateException("Game already over: " + status);
        Move move = currentPlayer().makeMove(board);
        applyMove(move);
    }

    /** Validate + apply a move, update win/draw status, advance turn. */
    void applyMove(Move move) {
        if (status != GameStatus.IN_PROGRESS)
            throw new IllegalStateException("Game already over: " + status);
        if (move.player != currentPlayer())
            throw new IllegalStateException("Not " + move.player.getName() + "'s turn");

        board.place(move);            // validates bounds + occupancy
        history.push(move);

        Symbol w = winStrategy.checkWinner(board, move);
        if (w != null) {
            status = GameStatus.WIN;
            winner = move.player;
        } else if (board.isFull()) {
            status = GameStatus.DRAW;
        } else {
            currentIdx = (currentIdx + 1) % players.size();
        }
    }

    /** Undo the last applied move: clear cell, reverse counters, restore turn/status. */
    void undo() {
        if (history.isEmpty()) return; // no-op
        Move last = history.pop();
        board.clear(last.row, last.col);
        winStrategy.undo(last);
        status = GameStatus.IN_PROGRESS;
        winner = null;
        currentIdx = players.indexOf(last.player); // it's that player's turn again
    }

    /** Convenience loop: bot-driven games can run to completion. */
    void playToEnd() {
        while (status == GameStatus.IN_PROGRESS) {
            playTurn();
        }
    }
}

/* ------------------------------ Demo (main) --------------------------------- */

public class Solution {
    public static void main(String[] args) {
        System.out.println("=== Scenario 1: scripted human game (X wins top row) ===");
        scriptedHumanGame();

        System.out.println("\n=== Scenario 2: undo then a different winning line ===");
        undoDemo();

        System.out.println("\n=== Scenario 3: random bot vs random bot (seeded) ===");
        botVsBot();

        System.out.println("\n=== Scenario 4: minimax bot is unbeatable (X never loses) ===");
        minimaxDemo();
    }

    // A scripted "human" input source: feeds a fixed list of (row,col) moves.
    private static BiFunction<Player, Board, int[]> scripted(int[]... moves) {
        final int[] idx = {0};
        return (player, board) -> moves[idx[0]++];
    }

    private static void scriptedHumanGame() {
        // X plays the top row; O plays the middle row but loses the race.
        Player x = PlayerFactory.createHuman("Alice", Symbol.X,
                scripted(new int[]{0,0}, new int[]{0,1}, new int[]{0,2}));
        Player o = PlayerFactory.createHuman("Bob", Symbol.O,
                scripted(new int[]{1,0}, new int[]{1,1}));

        Game game = new Game(3, Arrays.asList(x, o), new CountersWinningStrategy(3));
        game.playToEnd();

        System.out.print(game.getBoard().render());
        System.out.println("Status: " + game.getStatus()
                + (game.getWinner() != null ? " | Winner: " + game.getWinner().getName() : ""));
    }

    private static void undoDemo() {
        Player x = PlayerFactory.createHuman("Alice", Symbol.X,
                scripted(new int[]{0,0}, new int[]{2,2}, new int[]{1,1}));
        Player o = PlayerFactory.createHuman("Bob", Symbol.O,
                scripted(new int[]{0,1}, new int[]{0,2}));

        Game game = new Game(3, Arrays.asList(x, o), new CountersWinningStrategy(3));
        // X(0,0), O(0,1), X(2,2), O(0,2), X(1,1) -> X wins main diagonal (0,0)(1,1)(2,2)
        game.playToEnd();
        System.out.println("Before undo -> Status: " + game.getStatus()
                + (game.getWinner() != null ? " | Winner: " + game.getWinner().getName() : ""));

        game.undo(); // take back X's winning (1,1)
        System.out.println("After undo  -> Status: " + game.getStatus()
                + " | current turn: " + game.currentPlayer().getName());
        System.out.print(game.getBoard().render());
    }

    private static void botVsBot() {
        Player b1 = PlayerFactory.createRandomBot("Botty", Symbol.X, 42L);
        Player b2 = PlayerFactory.createRandomBot("Roomba", Symbol.O, 7L);

        Game game = new Game(3, Arrays.asList(b1, b2), new BruteForceWinningStrategy());
        game.playToEnd();

        System.out.print(game.getBoard().render());
        System.out.println("Status: " + game.getStatus()
                + (game.getWinner() != null ? " | Winner: " + game.getWinner().getName() : " (no winner)"));
    }

    private static void minimaxDemo() {
        // Unbeatable X (minimax) vs a random O: X should win or draw, never lose.
        Player x = PlayerFactory.createUnbeatableBot("Deep-X", Symbol.X, Symbol.O);
        Player o = PlayerFactory.createRandomBot("Rando-O", Symbol.O, 123L);

        Game game = new Game(3, Arrays.asList(x, o), new CountersWinningStrategy(3));
        game.playToEnd();

        System.out.print(game.getBoard().render());
        String result = game.getStatus() == GameStatus.WIN
                ? "Winner: " + game.getWinner().getName()
                : "Draw";
        System.out.println("Status: " + game.getStatus() + " | " + result
                + "  (minimax X never loses)");
    }
}
