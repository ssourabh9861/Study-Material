/* ============================================================================
 * Chess — Single-file LLD review / revision artifact.
 *
 * This file is for READING and REVISION before an interview. It is complete and
 * intended to be correct-by-inspection; you do NOT need to compile or run it.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 *  Functional:
 *   - Rules engine for two humans, or do we also need an AI opponent / search?
 *   - Full rule set (castling, en passant, promotion, 50-move, threefold,
 *     insufficient material), or just basic movement + check/checkmate?
 *   - Undo/redo required? How deep — single, or full history?
 *   - Load/save games (FEN / PGN)? Replay from a move list?
 *   - Clock / time control in scope?
 *  Non-functional:
 *   - Single process in-memory, or networked multiplayer (who arbitrates moves)?
 *   - Performance: UI-grade (make/test/unmake legality is fine) or engine-grade
 *     (would need bitboards)?
 *   - Move input: algebraic notation ("Nf3") or (from,to,promotion) coordinates?
 *  Scope / edge:
 *   - Illegal move: throw, or return a typed result the caller inspects?
 *   - Are resignation / draw-by-agreement part of the engine API?
 *   - Under-promotion (knight/bishop/rook) allowed, or auto-queen only?
 *
 *  ASSUMED HERE: two-player rules engine, full standard rules, full undo/redo,
 *  in-memory single process, moves as (from,to,promotion), illegal moves
 *  returned as a typed MoveResult, AI + notation left as clean extension points.
 *
 * ----------------------------------------------------------------------------
 * PATTERNS: Strategy (per-piece movement), Command (reversible moves -> undo/
 * redo, atomic castling, en-passant), Factory (MoveFactory + PieceFactory),
 * State (GameStatus enum), Facade (Game). SOLID: SRP/OCP/LSP/ISP/DIP throughout.
 *
 * KEY IDEA: validate every move by make -> "is my king attacked?" -> maybe
 * unmake. Pins, discovered checks, checkmate and stalemate all fall out of this
 * plus Board.isSquareAttackedBy(...). Rules-dependent STATE (castling rights,
 * en-passant target, half-move clock) lives on the Board and is restored on undo.
 * ============================================================================ */

import java.util.*;

public class Solution {

    /* ===================== Basic value types ===================== */

    enum Color {
        WHITE, BLACK;
        Color opposite() { return this == WHITE ? BLACK : WHITE; }
    }

    enum PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

    enum GameStatus {
        ACTIVE, CHECK, CHECKMATE, STALEMATE,
        DRAW_FIFTY_MOVE, DRAW_REPETITION, DRAW_INSUFFICIENT,
        DRAW_AGREED, RESIGNED
    }

    /** Immutable board coordinate. row 0..7 (rank 1..8), col 0..7 (file a..h). */
    static final class Position {
        final int row, col;
        Position(int row, int col) { this.row = row; this.col = col; }
        boolean onBoard() { return row >= 0 && row < 8 && col >= 0 && col < 8; }
        Position plus(int dr, int dc) { return new Position(row + dr, col + dc); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Position)) return false;
            Position p = (Position) o; return p.row == row && p.col == col;
        }
        @Override public int hashCode() { return row * 8 + col; }
        @Override public String toString() {
            return "" + (char) ('a' + col) + (char) ('1' + row);
        }
    }

    /** Result returned to the caller; exceptions are clumsy for "bad UI click". */
    static final class MoveResult {
        enum Kind { OK, ILLEGAL, NOT_YOUR_TURN, EMPTY_SOURCE, GAME_OVER, LEAVES_KING_IN_CHECK }
        final Kind kind; final GameStatus status; final String message;
        MoveResult(Kind kind, GameStatus status, String message) {
            this.kind = kind; this.status = status; this.message = message;
        }
        static MoveResult ok(GameStatus s) { return new MoveResult(Kind.OK, s, "ok"); }
        static MoveResult err(Kind k, String m) { return new MoveResult(k, null, m); }
        boolean isOk() { return kind == Kind.OK; }
        @Override public String toString() {
            return kind + (status != null ? " (" + status + ")" : "") + " - " + message;
        }
    }

    /* ===================== Cell ===================== */

    static final class Cell {
        final Position pos;
        Piece piece;            // 0..1 piece
        Cell(Position pos) { this.pos = pos; }
    }

    /* ===================== Strategy: movement ===================== */

    /**
     * One piece type's geometry. Returns PSEUDO-LEGAL targets: geometrically
     * valid + obstruction/capture respected, but IGNORES whether the move leaves
     * the mover's own king in check (the Game applies that universal filter).
     */
    interface MovementStrategy {
        List<Position> pseudoLegalMoves(Board board, Color color, Position from);
    }

    /** Shared helper for sliding pieces (rook/bishop/queen). */
    abstract static class SlidingMovement implements MovementStrategy {
        protected abstract int[][] directions();
        @Override public List<Position> pseudoLegalMoves(Board b, Color color, Position from) {
            List<Position> out = new ArrayList<>();
            for (int[] d : directions()) {
                Position p = from.plus(d[0], d[1]);
                while (p.onBoard()) {
                    Piece occ = b.pieceAt(p);
                    if (occ == null) { out.add(p); }
                    else { if (occ.color != color) out.add(p); break; } // capture then stop
                    p = p.plus(d[0], d[1]);
                }
            }
            return out;
        }
    }

    static final class RookMovement extends SlidingMovement {
        protected int[][] directions() { return new int[][]{{1,0},{-1,0},{0,1},{0,-1}}; }
    }
    static final class BishopMovement extends SlidingMovement {
        protected int[][] directions() { return new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}}; }
    }
    static final class QueenMovement extends SlidingMovement {
        protected int[][] directions() {
            return new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        }
    }

    /** King: one step any direction. Castling is generated by MoveFactory, not here. */
    static final class KingMovement implements MovementStrategy {
        @Override public List<Position> pseudoLegalMoves(Board b, Color color, Position from) {
            List<Position> out = new ArrayList<>();
            int[][] d = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
            for (int[] s : d) {
                Position p = from.plus(s[0], s[1]);
                if (!p.onBoard()) continue;
                Piece occ = b.pieceAt(p);
                if (occ == null || occ.color != color) out.add(p);
            }
            return out;
        }
    }

    static final class KnightMovement implements MovementStrategy {
        @Override public List<Position> pseudoLegalMoves(Board b, Color color, Position from) {
            List<Position> out = new ArrayList<>();
            int[][] d = {{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};
            for (int[] s : d) {
                Position p = from.plus(s[0], s[1]);
                if (!p.onBoard()) continue;
                Piece occ = b.pieceAt(p);
                if (occ == null || occ.color != color) out.add(p);
            }
            return out;
        }
    }

    /** Pawn: forward push (1, or 2 from home), diagonal captures, and en passant. */
    static final class PawnMovement implements MovementStrategy {
        @Override public List<Position> pseudoLegalMoves(Board b, Color color, Position from) {
            List<Position> out = new ArrayList<>();
            int dir = (color == Color.WHITE) ? 1 : -1;
            int startRow = (color == Color.WHITE) ? 1 : 6;

            // single push
            Position one = from.plus(dir, 0);
            if (one.onBoard() && b.pieceAt(one) == null) {
                out.add(one);
                // double push from home, both squares empty
                Position two = from.plus(2 * dir, 0);
                if (from.row == startRow && b.pieceAt(two) == null) out.add(two);
            }
            // diagonal captures (incl. en passant target)
            for (int dc : new int[]{-1, 1}) {
                Position cap = from.plus(dir, dc);
                if (!cap.onBoard()) continue;
                Piece occ = b.pieceAt(cap);
                if (occ != null && occ.color != color) out.add(cap);
                else if (cap.equals(b.getEnPassantTarget())) out.add(cap); // ep: dest empty
            }
            return out;
        }
    }

    /* ===================== Piece hierarchy ===================== */

    abstract static class Piece {
        final Color color;
        final PieceType type;
        final MovementStrategy movement;
        boolean hasMoved = false;     // needed for castling & pawn double-step history
        Piece(Color color, PieceType type, MovementStrategy movement) {
            this.color = color; this.type = type; this.movement = movement;
        }
        List<Position> pseudoLegalMoves(Board b, Position from) {
            return movement.pseudoLegalMoves(b, color, from);
        }
        abstract char glyph();   // for printing
    }
    static final class King   extends Piece { King(Color c)   { super(c, PieceType.KING,   new KingMovement());   } char glyph(){return color==Color.WHITE?'K':'k';} }
    static final class Queen  extends Piece { Queen(Color c)  { super(c, PieceType.QUEEN,  new QueenMovement());  } char glyph(){return color==Color.WHITE?'Q':'q';} }
    static final class Rook   extends Piece { Rook(Color c)   { super(c, PieceType.ROOK,   new RookMovement());   } char glyph(){return color==Color.WHITE?'R':'r';} }
    static final class Bishop extends Piece { Bishop(Color c) { super(c, PieceType.BISHOP, new BishopMovement()); } char glyph(){return color==Color.WHITE?'B':'b';} }
    static final class Knight extends Piece { Knight(Color c) { super(c, PieceType.KNIGHT, new KnightMovement()); } char glyph(){return color==Color.WHITE?'N':'n';} }
    static final class Pawn   extends Piece { Pawn(Color c)   { super(c, PieceType.PAWN,   new PawnMovement());   } char glyph(){return color==Color.WHITE?'P':'p';} }

    /** Factory: type+color -> wired piece. Also used by promotion. */
    static final class PieceFactory {
        static Piece create(PieceType type, Color color) {
            switch (type) {
                case KING:   return new King(color);
                case QUEEN:  return new Queen(color);
                case ROOK:   return new Rook(color);
                case BISHOP: return new Bishop(color);
                case KNIGHT: return new Knight(color);
                case PAWN:   return new Pawn(color);
                default: throw new IllegalArgumentException("unknown type " + type);
            }
        }
    }

    /* ===================== Castling rights (board state) ===================== */

    static final class CastlingRights implements Cloneable {
        boolean whiteKingSide = true, whiteQueenSide = true;
        boolean blackKingSide = true, blackQueenSide = true;
        @Override protected CastlingRights clone() {
            CastlingRights c = new CastlingRights();
            c.whiteKingSide = whiteKingSide; c.whiteQueenSide = whiteQueenSide;
            c.blackKingSide = blackKingSide; c.blackQueenSide = blackQueenSide;
            return c;
        }
    }

    /* ===================== Board ===================== */

    /**
     * The 8x8 grid PLUS the positional state rules depend on: castling rights,
     * en-passant target (valid one ply only), and the half-move clock.
     * Knows how to place/remove, find a king, and answer isSquareAttackedBy(...).
     * It does NOT know about turns or game-over.
     */
    static final class Board {
        private final Cell[][] cells = new Cell[8][8];
        private CastlingRights castling = new CastlingRights();
        private Position enPassantTarget = null;   // square a pawn may capture onto
        private int halfMoveClock = 0;              // for the fifty-move rule

        Board() {
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++)
                    cells[r][c] = new Cell(new Position(r, c));
        }

        Piece pieceAt(Position p) { return cells[p.row][p.col].piece; }
        void place(Position p, Piece piece) { cells[p.row][p.col].piece = piece; }
        Piece remove(Position p) {
            Piece pc = cells[p.row][p.col].piece;
            cells[p.row][p.col].piece = null;
            return pc;
        }

        CastlingRights getCastling() { return castling; }
        void setCastling(CastlingRights c) { this.castling = c; }
        Position getEnPassantTarget() { return enPassantTarget; }
        void setEnPassantTarget(Position p) { this.enPassantTarget = p; }
        int getHalfMoveClock() { return halfMoveClock; }
        void setHalfMoveClock(int v) { this.halfMoveClock = v; }

        Position findKing(Color color) {
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++) {
                    Piece p = cells[r][c].piece;
                    if (p != null && p.type == PieceType.KING && p.color == color)
                        return new Position(r, c);
                }
            throw new IllegalStateException("no " + color + " king on board");
        }

        /**
         * Could any 'attacker'-colored piece pseudo-legally move onto p?
         * This is the primitive that check detection (and castling-through-check)
         * is built on. Pawns are special-cased: they ATTACK diagonally only.
         */
        boolean isSquareAttackedBy(Position target, Color attacker) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = cells[r][c].piece;
                    if (p == null || p.color != attacker) continue;
                    Position from = new Position(r, c);
                    if (p.type == PieceType.PAWN) {
                        int dir = (attacker == Color.WHITE) ? 1 : -1;
                        if (target.equals(from.plus(dir, -1)) || target.equals(from.plus(dir, 1)))
                            return true;
                    } else {
                        // For non-pawns, pseudo-legal targets == attacked squares.
                        if (p.pseudoLegalMoves(this, from).contains(target)) return true;
                    }
                }
            }
            return false;
        }

        /** Position key for threefold repetition: placement + side hints + rights + ep. */
        String positionKey(Color sideToMove) {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++) {
                    Piece p = cells[r][c].piece;
                    sb.append(p == null ? '.' : p.glyph());
                }
            sb.append('|').append(sideToMove)
              .append('|').append(castling.whiteKingSide ? 'K':'-')
              .append(castling.whiteQueenSide ? 'Q':'-')
              .append(castling.blackKingSide ? 'k':'-')
              .append(castling.blackQueenSide ? 'q':'-')
              .append('|').append(enPassantTarget == null ? "-" : enPassantTarget.toString());
            return sb.toString();
        }

        void print() {
            for (int r = 7; r >= 0; r--) {
                StringBuilder sb = new StringBuilder((r + 1) + " ");
                for (int c = 0; c < 8; c++) {
                    Piece p = cells[r][c].piece;
                    sb.append(p == null ? '.' : p.glyph()).append(' ');
                }
                System.out.println(sb);
            }
            System.out.println("  a b c d e f g h");
        }
    }

    /* ===================== Command: reversible moves ===================== */

    /**
     * Each move is an executable, self-reversing action. It captures the small
     * "memento" of state it needs to undo itself (captured piece, prior rights,
     * prior ep-target, prior half-move clock, hasMoved flags).
     */
    interface MoveCommand {
        void execute(Board board);
        void undo(Board board);
        Position from();
        Position to();
    }

    /** Common state every command must save/restore. */
    abstract static class AbstractMove implements MoveCommand {
        final Position from, to;
        // saved board-level state for undo:
        CastlingRights prevCastling;
        Position prevEnPassant;
        int prevHalfMoveClock;
        boolean movedPieceHadMoved;

        AbstractMove(Position from, Position to) { this.from = from; this.to = to; }
        public Position from() { return from; }
        public Position to()   { return to; }

        void saveBoardState(Board b, Piece moving) {
            prevCastling = b.getCastling().clone();
            prevEnPassant = b.getEnPassantTarget();
            prevHalfMoveClock = b.getHalfMoveClock();
            movedPieceHadMoved = moving.hasMoved;
        }
        void restoreBoardState(Board b) {
            b.setCastling(prevCastling);
            b.setEnPassantTarget(prevEnPassant);
            b.setHalfMoveClock(prevHalfMoveClock);
        }

        /** Update castling rights when a king/rook moves or a rook is captured. */
        static void revokeRightsFor(Board b, Piece moving, Position from, Piece captured, Position to) {
            CastlingRights cr = b.getCastling();
            if (moving.type == PieceType.KING) {
                if (moving.color == Color.WHITE) { cr.whiteKingSide = cr.whiteQueenSide = false; }
                else { cr.blackKingSide = cr.blackQueenSide = false; }
            }
            if (moving.type == PieceType.ROOK) revokeRookRight(cr, from);
            if (captured != null && captured.type == PieceType.ROOK) revokeRookRight(cr, to);
        }
        private static void revokeRookRight(CastlingRights cr, Position sq) {
            if (sq.equals(new Position(0,0))) cr.whiteQueenSide = false;
            if (sq.equals(new Position(0,7))) cr.whiteKingSide = false;
            if (sq.equals(new Position(7,0))) cr.blackQueenSide = false;
            if (sq.equals(new Position(7,7))) cr.blackKingSide = false;
        }
    }

    /** Normal move or ordinary capture. */
    static final class NormalMove extends AbstractMove {
        private Piece captured;       // null if quiet move
        NormalMove(Position from, Position to) { super(from, to); }

        @Override public void execute(Board b) {
            Piece moving = b.pieceAt(from);
            saveBoardState(b, moving);
            captured = b.pieceAt(to);

            AbstractMove.revokeRightsFor(b, moving, from, captured, to);
            b.remove(from);
            b.place(to, moving);
            moving.hasMoved = true;

            // en-passant target: set only on a pawn double-step, else clear.
            if (moving.type == PieceType.PAWN && Math.abs(to.row - from.row) == 2)
                b.setEnPassantTarget(new Position((from.row + to.row) / 2, from.col));
            else
                b.setEnPassantTarget(null);

            // half-move clock: reset on pawn move or capture, else +1.
            if (moving.type == PieceType.PAWN || captured != null) b.setHalfMoveClock(0);
            else b.setHalfMoveClock(prevHalfMoveClock + 1);
        }

        @Override public void undo(Board b) {
            Piece moving = b.pieceAt(to);
            b.remove(to);
            b.place(from, moving);
            moving.hasMoved = movedPieceHadMoved;
            if (captured != null) b.place(to, captured);
            restoreBoardState(b);
        }
    }

    /** En passant: capture a pawn that sits on a different square than the destination. */
    static final class EnPassantMove extends AbstractMove {
        private Piece capturedPawn;
        private Position capturedSquare;
        EnPassantMove(Position from, Position to) { super(from, to); }

        @Override public void execute(Board b) {
            Piece moving = b.pieceAt(from);
            saveBoardState(b, moving);
            // the captured pawn is on the moving pawn's row, destination's column
            capturedSquare = new Position(from.row, to.col);
            capturedPawn = b.remove(capturedSquare);

            b.remove(from);
            b.place(to, moving);
            moving.hasMoved = true;
            b.setEnPassantTarget(null);
            b.setHalfMoveClock(0); // pawn move + capture
        }

        @Override public void undo(Board b) {
            Piece moving = b.pieceAt(to);
            b.remove(to);
            b.place(from, moving);
            moving.hasMoved = movedPieceHadMoved;
            b.place(capturedSquare, capturedPawn);
            restoreBoardState(b);
        }
    }

    /** Castling: moves king and rook atomically. Preconditions checked in MoveFactory. */
    static final class CastlingMove extends AbstractMove {
        private final boolean kingSide;
        private Position rookFrom, rookTo;
        private boolean rookHadMoved;
        CastlingMove(Position kingFrom, Position kingTo, boolean kingSide) {
            super(kingFrom, kingTo); this.kingSide = kingSide;
        }
        @Override public void execute(Board b) {
            Piece king = b.pieceAt(from);
            saveBoardState(b, king);
            int row = from.row;
            rookFrom = new Position(row, kingSide ? 7 : 0);
            rookTo   = new Position(row, kingSide ? 5 : 3);
            Piece rook = b.pieceAt(rookFrom);
            rookHadMoved = rook.hasMoved;

            b.remove(from); b.place(to, king); king.hasMoved = true;
            b.remove(rookFrom); b.place(rookTo, rook); rook.hasMoved = true;

            AbstractMove.revokeRightsFor(b, king, from, null, to);
            b.setEnPassantTarget(null);
            b.setHalfMoveClock(prevHalfMoveClock + 1);
        }
        @Override public void undo(Board b) {
            Piece king = b.pieceAt(to);
            Piece rook = b.pieceAt(rookTo);
            b.remove(to); b.place(from, king); king.hasMoved = movedPieceHadMoved;
            b.remove(rookTo); b.place(rookFrom, rook); rook.hasMoved = rookHadMoved;
            restoreBoardState(b);
        }
    }

    /** Promotion: pawn reaches last rank -> chosen piece (under-promotion allowed). */
    static final class PromotionMove extends AbstractMove {
        private final PieceType promoteTo;
        private Piece captured;
        private Piece pawn;          // saved so undo can put the pawn back
        PromotionMove(Position from, Position to, PieceType promoteTo) {
            super(from, to); this.promoteTo = promoteTo;
        }
        @Override public void execute(Board b) {
            pawn = b.pieceAt(from);
            saveBoardState(b, pawn);
            captured = b.pieceAt(to);
            AbstractMove.revokeRightsFor(b, pawn, from, captured, to);

            b.remove(from);
            Piece promoted = PieceFactory.create(promoteTo, pawn.color);
            promoted.hasMoved = true;
            b.place(to, promoted);
            b.setEnPassantTarget(null);
            b.setHalfMoveClock(0); // pawn move (and possibly capture)
        }
        @Override public void undo(Board b) {
            b.remove(to);
            b.place(from, pawn);
            pawn.hasMoved = movedPieceHadMoved;
            if (captured != null) b.place(to, captured);
            restoreBoardState(b);
        }
    }

    /* ===================== Factory: classify intent -> command ===================== */

    /**
     * Given (from, to, promotion) and the board, decide which concrete command
     * to build. Returns null if the target is not even pseudo-legal for the
     * piece (caller treats that as ILLEGAL). Castling preconditions (incl. not
     * moving through check) are validated here.
     */
    static final class MoveFactory {
        static MoveCommand create(Board b, Position from, Position to, PieceType promotion) {
            Piece moving = b.pieceAt(from);
            if (moving == null) return null;

            // Castling: king moves two squares horizontally.
            if (moving.type == PieceType.KING && from.row == to.row
                    && Math.abs(to.col - from.col) == 2) {
                boolean kingSide = to.col > from.col;
                return canCastle(b, moving.color, kingSide)
                        ? new CastlingMove(from, to, kingSide) : null;
            }

            // Must be a pseudo-legal target for everything else.
            if (!moving.pseudoLegalMoves(b, from).contains(to)) return null;

            // En passant: pawn moves diagonally onto the empty ep-target square.
            if (moving.type == PieceType.PAWN && to.equals(b.getEnPassantTarget())
                    && b.pieceAt(to) == null) {
                return new EnPassantMove(from, to);
            }

            // Promotion: pawn reaches last rank.
            int lastRow = (moving.color == Color.WHITE) ? 7 : 0;
            if (moving.type == PieceType.PAWN && to.row == lastRow) {
                PieceType chosen = (promotion == null) ? PieceType.QUEEN : promotion;
                if (chosen == PieceType.KING || chosen == PieceType.PAWN)
                    chosen = PieceType.QUEEN; // guard against nonsense promotion
                return new PromotionMove(from, to, chosen);
            }

            return new NormalMove(from, to);
        }

        /** All castling preconditions: rights, empty path, not in/through/into check. */
        private static boolean canCastle(Board b, Color color, boolean kingSide) {
            CastlingRights cr = b.getCastling();
            boolean right = (color == Color.WHITE)
                    ? (kingSide ? cr.whiteKingSide : cr.whiteQueenSide)
                    : (kingSide ? cr.blackKingSide : cr.blackQueenSide);
            if (!right) return false;

            int row = (color == Color.WHITE) ? 0 : 7;
            Color enemy = color.opposite();
            Position kingPos = new Position(row, 4);
            if (b.isSquareAttackedBy(kingPos, enemy)) return false; // can't castle out of check

            if (kingSide) {
                // squares f,g empty; king passes e->f->g none attacked
                if (b.pieceAt(new Position(row,5)) != null) return false;
                if (b.pieceAt(new Position(row,6)) != null) return false;
                if (b.isSquareAttackedBy(new Position(row,5), enemy)) return false;
                if (b.isSquareAttackedBy(new Position(row,6), enemy)) return false;
            } else {
                // squares b,c,d empty; king passes e->d->c none attacked (b need not be safe)
                if (b.pieceAt(new Position(row,1)) != null) return false;
                if (b.pieceAt(new Position(row,2)) != null) return false;
                if (b.pieceAt(new Position(row,3)) != null) return false;
                if (b.isSquareAttackedBy(new Position(row,3), enemy)) return false;
                if (b.isSquareAttackedBy(new Position(row,2), enemy)) return false;
            }
            return true;
        }
    }

    /* ===================== Check detection ===================== */

    static final class CheckDetector {
        static boolean isInCheck(Board b, Color color) {
            return b.isSquareAttackedBy(b.findKing(color), color.opposite());
        }
    }

    /* ===================== Player ===================== */

    static final class Player {
        final Color color; final String name;
        Player(Color color, String name) { this.color = color; this.name = name; }
        // Extension point: an AI Player would choose from Game.allLegalMoves(color).
    }

    /* ===================== Game (Facade / orchestrator) ===================== */

    static final class Game {
        private final Board board = new Board();
        private final Player white, black;
        private Color turn = Color.WHITE;
        private GameStatus status = GameStatus.ACTIVE;
        private final Deque<MoveCommand> undoStack = new ArrayDeque<>();
        private final Deque<MoveCommand> redoStack = new ArrayDeque<>();
        private final Map<String, Integer> positionCounts = new HashMap<>(); // threefold

        Game(String whiteName, String blackName) {
            white = new Player(Color.WHITE, whiteName);
            black = new Player(Color.BLACK, blackName);
            setupStartingPosition();
            recordPosition();
        }

        Board getBoard() { return board; }
        GameStatus getStatus() { return status; }
        Color getTurn() { return turn; }

        private void setupStartingPosition() {
            PieceType[] back = {PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK};
            for (int c = 0; c < 8; c++) {
                board.place(new Position(0, c), PieceFactory.create(back[c], Color.WHITE));
                board.place(new Position(1, c), PieceFactory.create(PieceType.PAWN, Color.WHITE));
                board.place(new Position(6, c), PieceFactory.create(PieceType.PAWN, Color.BLACK));
                board.place(new Position(7, c), PieceFactory.create(back[c], Color.BLACK));
            }
        }

        /** The core move loop: validate -> build command -> make -> king-safety -> commit. */
        MoveResult move(Position from, Position to, PieceType promotion) {
            if (isGameOver())
                return MoveResult.err(MoveResult.Kind.GAME_OVER, "game already ended: " + status);
            Piece moving = board.pieceAt(from);
            if (moving == null)
                return MoveResult.err(MoveResult.Kind.EMPTY_SOURCE, "no piece at " + from);
            if (moving.color != turn)
                return MoveResult.err(MoveResult.Kind.NOT_YOUR_TURN, "it is " + turn + " to move");

            MoveCommand cmd = MoveFactory.create(board, from, to, promotion);
            if (cmd == null)
                return MoveResult.err(MoveResult.Kind.ILLEGAL, "not a legal target for that piece");

            cmd.execute(board);
            if (CheckDetector.isInCheck(board, turn)) {       // make -> test -> unmake
                cmd.undo(board);
                return MoveResult.err(MoveResult.Kind.LEAVES_KING_IN_CHECK,
                        "move would leave your king in check");
            }

            undoStack.push(cmd);
            redoStack.clear();
            turn = turn.opposite();
            recordPosition();
            status = computeStatus();
            return MoveResult.ok(status);
        }

        boolean undo() {
            if (undoStack.isEmpty()) return false;
            MoveCommand cmd = undoStack.pop();
            forgetPosition();
            cmd.undo(board);
            turn = turn.opposite();
            redoStack.push(cmd);
            status = computeStatus();
            return true;
        }

        boolean redo() {
            if (redoStack.isEmpty()) return false;
            MoveCommand cmd = redoStack.pop();
            cmd.execute(board);
            turn = turn.opposite();
            undoStack.push(cmd);
            recordPosition();
            status = computeStatus();
            return true;
        }

        void resign(Color who) { status = GameStatus.RESIGNED; }
        void agreeDraw()        { status = GameStatus.DRAW_AGREED; }

        boolean isGameOver() {
            return status != GameStatus.ACTIVE && status != GameStatus.CHECK;
        }

        /** Legal moves from a square = pseudo-legal filtered by king safety. */
        List<Position> legalMoves(Position from) {
            List<Position> legal = new ArrayList<>();
            Piece p = board.pieceAt(from);
            if (p == null || p.color != turn) return legal;

            // candidate targets = pseudo-legal squares + (king) the two castle squares
            Set<Position> candidates = new LinkedHashSet<>(p.pseudoLegalMoves(board, from));
            if (p.type == PieceType.KING) {
                candidates.add(from.plus(0, 2));
                candidates.add(from.plus(0, -2));
            }
            for (Position to : candidates) {
                if (!to.onBoard()) continue;
                MoveCommand cmd = MoveFactory.create(board, from, to, PieceType.QUEEN);
                if (cmd == null) continue;
                cmd.execute(board);
                boolean safe = !CheckDetector.isInCheck(board, p.color);
                cmd.undo(board);
                if (safe) legal.add(to);
            }
            return legal;
        }

        /** Does the side to move have ANY legal move? (basis of mate/stalemate) */
        private boolean hasAnyLegalMove(Color color) {
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++) {
                    Piece p = board.pieceAt(new Position(r, c));
                    if (p != null && p.color == color && !legalMoves(new Position(r, c)).isEmpty())
                        return true;
                }
            return false;
        }

        private GameStatus computeStatus() {
            if (status == GameStatus.RESIGNED || status == GameStatus.DRAW_AGREED) return status;
            boolean inCheck = CheckDetector.isInCheck(board, turn);
            boolean anyMove = hasAnyLegalMove(turn);
            if (inCheck && !anyMove) return GameStatus.CHECKMATE;
            if (!inCheck && !anyMove) return GameStatus.STALEMATE;
            if (board.getHalfMoveClock() >= 100) return GameStatus.DRAW_FIFTY_MOVE; // 100 half-moves
            if (isThreefold()) return GameStatus.DRAW_REPETITION;
            if (isInsufficientMaterial()) return GameStatus.DRAW_INSUFFICIENT;
            return inCheck ? GameStatus.CHECK : GameStatus.ACTIVE;
        }

        /* --- draw bookkeeping --- */
        private void recordPosition() {
            String key = board.positionKey(turn);
            positionCounts.merge(key, 1, Integer::sum);
        }
        private void forgetPosition() {
            String key = board.positionKey(turn);
            Integer n = positionCounts.get(key);
            if (n != null) { if (n <= 1) positionCounts.remove(key); else positionCounts.put(key, n - 1); }
        }
        private boolean isThreefold() {
            return positionCounts.getOrDefault(board.positionKey(turn), 0) >= 3;
        }
        private boolean isInsufficientMaterial() {
            List<Piece> pieces = new ArrayList<>();
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++) {
                    Piece p = board.pieceAt(new Position(r, c));
                    if (p != null) pieces.add(p);
                }
            if (pieces.size() == 2) return true;                       // K vs K
            if (pieces.size() == 3) {                                  // K+minor vs K
                for (Piece p : pieces)
                    if (p.type == PieceType.BISHOP || p.type == PieceType.KNIGHT) return true;
            }
            return false;
        }
    }

    /* ===================== Demo (main) ===================== */

    static Position sq(String s) { // "e2" -> Position
        return new Position(s.charAt(1) - '1', s.charAt(0) - 'a');
    }
    static void doMove(Game g, String from, String to) { doMove(g, from, to, null); }
    static void doMove(Game g, String from, String to, PieceType promo) {
        MoveResult r = g.move(sq(from), sq(to), promo);
        System.out.println(from + "-" + to
                + (promo != null ? "=" + promo : "") + " : " + r);
    }

    public static void main(String[] args) {
        System.out.println("=== Scholar's-mate-style demo (ends in checkmate) ===");
        Game g = new Game("Alice (White)", "Bob (Black)");
        g.getBoard().print();

        doMove(g, "e2", "e4");   // White
        doMove(g, "e7", "e5");   // Black
        doMove(g, "f1", "c4");   // Bishop out
        doMove(g, "b8", "c6");
        doMove(g, "d1", "h5");   // Queen out
        doMove(g, "g8", "f6");   // Black blunders
        doMove(g, "h5", "f7");   // Qxf7# checkmate
        g.getBoard().print();
        System.out.println("Status: " + g.getStatus());

        System.out.println("\n=== Undo the mating move, then redo ===");
        g.undo();
        System.out.println("After undo, status: " + g.getStatus() + ", turn: " + g.getTurn());
        g.redo();
        System.out.println("After redo, status: " + g.getStatus());

        System.out.println("\n=== Castling demo ===");
        Game c = new Game("W", "B");
        doMove(c, "e2", "e4"); doMove(c, "e7", "e5");
        doMove(c, "g1", "f3"); doMove(c, "b8", "c6");
        doMove(c, "f1", "c4"); doMove(c, "f8", "c5");
        doMove(c, "e1", "g1"); // White O-O (king e1->g1, rook h1->f1)
        c.getBoard().print();
        System.out.println("White castled king-side. Status: " + c.getStatus());

        System.out.println("\n=== Legal moves for a knight from g1 (fresh game) ===");
        Game d = new Game("W", "B");
        System.out.println("g1 -> " + d.legalMoves(sq("g1")));

        System.out.println("\n=== Illegal move feedback ===");
        Game e = new Game("W", "B");
        System.out.println(e.move(sq("e2"), sq("e5"), null)); // pawn can't jump 3
        System.out.println(e.move(sq("e7"), sq("e5"), null)); // not Black's turn
    }
}
