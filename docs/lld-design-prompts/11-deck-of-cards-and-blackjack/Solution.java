/* =============================================================================
 * LLD REVIEW / REVISION ARTIFACT — Deck of Cards & Blackjack
 * Single-file Java solution. Read-and-revise only (no need to compile/run).
 *
 * ---------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ---------------------------------------------------------------------------
 * Functional scope:
 *   1. Just Blackjack, or a reusable card framework Blackjack sits on?
 *   2. One table / how many seats? Player-vs-dealer only (yes for Blackjack)?
 *   3. Which actions: Hit, Stand, Double Down, Split, Surrender, Insurance?
 *   4. Dealer rule: stand on soft 17 or hit on soft 17?
 *   5. Blackjack payout: 3:2 or 6:5? Push on tie? Dealer-blackjack handling?
 *   6. Betting model: per-player balance, table min/max? Or out of scope?
 * Card/Deck mechanics:
 *   7. Number of decks in shoe (1/4/6/8)? Configurable?
 *   8. Real shuffle (Fisher-Yates), swappable/seedable? Reshuffle (cut card)?
 *   9. Jokers / wild cards? (Standard Blackjack: no.)
 * Non-functional:
 *  10. Single-threaded sim, or concurrent players on a shared shoe?
 *  11. Deterministic/seedable for tests? Persistence? Scale (many tables)?
 *  12. Interface: CLI demo, library API, or full UI?
 * Out of scope (assumed): real money, auth, networking, anti-cheat, RNG certs.
 *
 * ---------------------------------------------------------------------------
 * SCOPE BUILT HERE
 * ---------------------------------------------------------------------------
 *  - Reusable core: Suit, Rank, Card (immutable), CardFactory (flyweight),
 *    Deck, Shoe (multi-deck, synchronized for thread-safety), Hand.
 *  - Strategies: ShuffleStrategy (Fisher-Yates, seedable), HandEvaluator
 *    (Blackjack ace soft/hard), PlayerStrategy + DealerPlayStrategy, PayoutRule.
 *  - State machine: BETTING -> DEALING -> PLAYER_TURN -> DEALER_TURN ->
 *    SETTLEMENT -> ROUND_OVER (enum-driven inside BlackjackGame).
 *  - Actions: HIT, STAND, DOUBLE_DOWN, SPLIT. Betting + 3:2 payout + push.
 *  - Observer: GameEventListener for logging/UI.
 *
 * PATTERNS: Strategy (shuffle/score/decide/payout), State (rounds),
 *           Factory + Flyweight (cards), Observer (events),
 *           Template Method (Participant).
 * ============================================================================= */

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Solution {

    /* ===================== CORE: enums & value objects ===================== */

    /** The four suits. */
    enum Suit { CLUBS("♣"), DIAMONDS("♦"), HEARTS("♥"), SPADES("♠");
        private final String symbol;
        Suit(String s) { this.symbol = s; }
        @Override public String toString() { return symbol; }
    }

    /** Ranks carry a base pip value (Ace base = 1; promoted to 11 by evaluator). */
    enum Rank {
        ACE("A", 1), TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5),
        SIX("6", 6), SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9), TEN("10", 10),
        JACK("J", 10), QUEEN("Q", 10), KING("K", 10);
        private final String label;
        private final int baseValue;
        Rank(String label, int baseValue) { this.label = label; this.baseValue = baseValue; }
        int baseValue() { return baseValue; }
        @Override public String toString() { return label; }
    }

    /**
     * Immutable value object. Because it is immutable and there are only 52
     * distinct values, instances are shared via CardFactory (Flyweight) and are
     * safe to read across threads without synchronization.
     */
    static final class Card {
        private final Suit suit;
        private final Rank rank;
        Card(Suit suit, Rank rank) { this.suit = suit; this.rank = rank; }
        Suit getSuit() { return suit; }
        Rank getRank() { return rank; }
        @Override public String toString() { return rank.toString() + suit.toString(); }
    }

    /**
     * FACTORY + FLYWEIGHT: central, validated card construction; caches and
     * shares the 52 canonical immutable Card instances across all decks/shoes.
     */
    static final class CardFactory {
        private static final Map<String, Card> CACHE = new HashMap<>();
        private CardFactory() {}
        static synchronized Card of(Suit suit, Rank rank) {
            String key = suit.name() + ":" + rank.name();
            return CACHE.computeIfAbsent(key, k -> new Card(suit, rank));
        }
        /** Build one standard 52-card list (sharing flyweight instances). */
        static List<Card> buildStandard52() {
            List<Card> cards = new ArrayList<>(52);
            for (Suit s : Suit.values())
                for (Rank r : Rank.values())
                    cards.add(of(s, r));
            return cards;
        }
    }

    /* ===================== STRATEGY: shuffling ===================== */

    interface ShuffleStrategy { void shuffle(List<Card> cards); }

    /** Unbiased Fisher-Yates. Seedable RNG so simulations are reproducible. */
    static final class FisherYatesShuffle implements ShuffleStrategy {
        private final Random rng;
        FisherYatesShuffle() { this.rng = new Random(); }
        FisherYatesShuffle(long seed) { this.rng = new Random(seed); }
        @Override public void shuffle(List<Card> cards) {
            for (int i = cards.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                Collections.swap(cards, i, j);
            }
        }
    }

    /** Identity shuffle — handy for deterministic tests. */
    static final class NoShuffle implements ShuffleStrategy {
        @Override public void shuffle(List<Card> cards) { /* leave order intact */ }
    }

    /* ===================== CORE: Deck & Shoe ===================== */

    /** A single ordered 52-card deck. Knows nothing about how to shuffle. */
    static final class Deck {
        private final List<Card> cards;
        Deck() { this.cards = CardFactory.buildStandard52(); }
        void shuffle(ShuffleStrategy strategy) { strategy.shuffle(cards); }
        List<Card> cards() { return cards; }
        int size() { return cards.size(); }
    }

    /**
     * A shoe = K decks combined into one draw pile. Tracks penetration and
     * decides when to reshuffle (cut-card style). deal()/reshuffle() are
     * synchronized so concurrent seats cannot draw the same card.
     */
    static final class Shoe {
        private final int numDecks;
        private final ShuffleStrategy shuffleStrategy;
        private final double penetrationThreshold; // reshuffle when fraction dealt exceeds this
        private final Deque<Card> drawPile = new ArrayDeque<>();
        private int totalCards;

        Shoe(int numDecks, ShuffleStrategy shuffleStrategy, double penetrationThreshold) {
            if (numDecks < 1) throw new IllegalArgumentException("numDecks must be >= 1");
            this.numDecks = numDecks;
            this.shuffleStrategy = shuffleStrategy;
            this.penetrationThreshold = penetrationThreshold;
            reshuffle();
        }

        synchronized void reshuffle() {
            List<Card> all = new ArrayList<>();
            for (int i = 0; i < numDecks; i++) all.addAll(new Deck().cards());
            shuffleStrategy.shuffle(all);
            drawPile.clear();
            drawPile.addAll(all);
            totalCards = drawPile.size();
        }

        synchronized Card deal() {
            if (drawPile.isEmpty()) reshuffle(); // never silently fail mid-deal
            return drawPile.pollFirst();
        }

        /** True once we've dealt past the penetration threshold (cut card reached). */
        synchronized boolean needsReshuffle() {
            int dealt = totalCards - drawPile.size();
            return totalCards == 0 || ((double) dealt / totalCards) >= penetrationThreshold;
        }

        synchronized int remaining() { return drawPile.size(); }
    }

    /* ===================== CORE: Hand ===================== */

    /** A set of cards held by a participant. Game-agnostic: no scoring here. */
    static final class Hand {
        private final List<Card> cards = new ArrayList<>();
        void add(Card c) { cards.add(c); }
        List<Card> getCards() { return cards; }
        int size() { return cards.size(); }
        @Override public String toString() { return cards.toString(); }
    }

    /* ===================== STRATEGY: hand evaluation ===================== */

    interface HandEvaluator {
        int value(Hand hand);
        boolean isBust(Hand hand);
    }

    /** Blackjack scoring: ace soft/hard logic, bust, blackjack, soft detection. */
    static final class BlackjackHandEvaluator implements HandEvaluator {
        static final int TARGET = 21;
        @Override public int value(Hand hand) {
            int sum = 0, aces = 0;
            for (Card c : hand.getCards()) {
                sum += c.getRank().baseValue();
                if (c.getRank() == Rank.ACE) aces++;
            }
            // Promote aces from 1 -> 11 (add 10) while staying <= 21.
            while (aces > 0 && sum + 10 <= TARGET) { sum += 10; aces--; }
            return sum;
        }
        @Override public boolean isBust(Hand hand) { return value(hand) > TARGET; }
        boolean isBlackjack(Hand hand) { return hand.size() == 2 && value(hand) == TARGET; }
        /** Soft = at least one ace is still counted as 11. */
        boolean isSoft(Hand hand) {
            int sum = 0, aces = 0;
            for (Card c : hand.getCards()) {
                sum += c.getRank().baseValue();
                if (c.getRank() == Rank.ACE) aces++;
            }
            int promoted = 0;
            while (aces - promoted > 0 && sum + 10 <= TARGET) { sum += 10; promoted++; }
            return promoted > 0;
        }
    }

    /* ===================== Actions, outcomes, money ===================== */

    enum PlayerAction { HIT, STAND, DOUBLE_DOWN, SPLIT }

    enum Outcome { PLAYER_BLACKJACK, WIN, PUSH, LOSE }

    enum BetStatus { OPEN, WON, LOST, PUSHED }

    /** A single stake on a single hand. */
    static final class Bet {
        private long amount;
        private BetStatus status = BetStatus.OPEN;
        Bet(long amount) { this.amount = amount; }
        long amount() { return amount; }
        void doubleStake() { this.amount *= 2; }
        void setStatus(BetStatus s) { this.status = s; }
        BetStatus status() { return status; }
    }

    /* ===================== STRATEGY: payout ===================== */

    interface PayoutRule {
        /** Total returned to the player (stake + winnings). 0 means stake lost. */
        long payout(long bet, Outcome outcome);
    }

    /** Standard casino rule: blackjack pays 3:2, normal win 1:1, push returns stake. */
    static final class StandardPayoutRule implements PayoutRule {
        @Override public long payout(long bet, Outcome o) {
            switch (o) {
                case PLAYER_BLACKJACK: return bet + (bet * 3) / 2; // 3:2
                case WIN:              return bet * 2;              // 1:1
                case PUSH:             return bet;                  // stake back
                case LOSE:             default: return 0;
            }
        }
    }

    /** 6:5 variant — demonstrates pluggability. */
    static final class SixToFivePayoutRule implements PayoutRule {
        @Override public long payout(long bet, Outcome o) {
            switch (o) {
                case PLAYER_BLACKJACK: return bet + (bet * 6) / 5; // 6:5
                case WIN:              return bet * 2;
                case PUSH:             return bet;
                case LOSE:             default: return 0;
            }
        }
    }

    /* ===================== STRATEGY: decisions ===================== */

    interface PlayerStrategy {
        PlayerAction decide(Hand hand, Card dealerUpCard, BlackjackHandEvaluator eval, boolean canDouble, boolean canSplit);
    }

    interface DealerPlayStrategy {
        boolean hit(Hand hand, BlackjackHandEvaluator eval);
    }

    /** Dealer stands on 17+ (treats soft 17 as a stand). Config flag flips it. */
    static final class StandardDealerStrategy implements DealerPlayStrategy {
        private final boolean hitSoft17;
        StandardDealerStrategy(boolean hitSoft17) { this.hitSoft17 = hitSoft17; }
        @Override public boolean hit(Hand hand, BlackjackHandEvaluator eval) {
            int v = eval.value(hand);
            if (v < 17) return true;
            if (v == 17 && hitSoft17 && eval.isSoft(hand)) return true;
            return false;
        }
    }

    /** Simple "basic strategy"-ish bot: hit below 17, otherwise stand. */
    static final class BasicBotStrategy implements PlayerStrategy {
        @Override public PlayerAction decide(Hand hand, Card up, BlackjackHandEvaluator eval,
                                             boolean canDouble, boolean canSplit) {
            int v = eval.value(hand);
            // Split a pair of aces or eights if allowed.
            if (canSplit) return PlayerAction.SPLIT;
            // Double down on 10 or 11 (two cards) when allowed.
            if (canDouble && (v == 10 || v == 11)) return PlayerAction.DOUBLE_DOWN;
            return v < 17 ? PlayerAction.HIT : PlayerAction.STAND;
        }
    }

    /** Scripted strategy for deterministic demos/tests. */
    static final class ScriptedStrategy implements PlayerStrategy {
        private final Deque<PlayerAction> script = new ArrayDeque<>();
        ScriptedStrategy(PlayerAction... actions) { for (PlayerAction a : actions) script.addLast(a); }
        @Override public PlayerAction decide(Hand hand, Card up, BlackjackHandEvaluator eval,
                                             boolean canDouble, boolean canSplit) {
            PlayerAction a = script.pollFirst();
            return a == null ? PlayerAction.STAND : a;
        }
    }

    /* ===================== Participants (Template Method) ===================== */

    /** Shared hand bookkeeping for any participant. */
    static abstract class Participant {
        protected final String name;
        protected final List<Hand> hands = new ArrayList<>();
        Participant(String name) { this.name = name; }
        String name() { return name; }
        List<Hand> hands() { return hands; }
        void clearHands() { hands.clear(); }
        void addHand(Hand h) { hands.add(h); }
    }

    /** A seated player: balance, bets per hand, and a decision strategy. */
    static final class Player extends Participant {
        private long balance;
        private final List<Bet> bets = new ArrayList<>();
        private final PlayerStrategy strategy;
        Player(String name, long balance, PlayerStrategy strategy) {
            super(name);
            this.balance = balance;
            this.strategy = strategy;
        }
        long balance() { return balance; }
        PlayerStrategy strategy() { return strategy; }
        List<Bet> bets() { return bets; }

        /** Validate and debit a stake; returns the created Bet or null if invalid. */
        Bet placeBet(long amount, long min, long max) {
            if (amount < min || amount > max || amount > balance) return null;
            balance -= amount;
            Bet bet = new Bet(amount);
            bets.add(bet);
            return bet;
        }
        boolean canAfford(long amount) { return balance >= amount; }
        void debit(long amount) { balance -= amount; }
        void credit(long amount) { balance += amount; }

        void resetForRound() { clearHands(); bets.clear(); }
    }

    /** The dealer: special participant driven by a DealerPlayStrategy. */
    static final class Dealer extends Participant {
        private final DealerPlayStrategy rule;
        Dealer(DealerPlayStrategy rule) { super("Dealer"); this.rule = rule; }
        Hand hand() { return hands.get(0); }
        /** Face-up card (index 0); index 1 is the hidden hole card. */
        Card upCard() { return hand().getCards().get(0); }
        boolean shouldHit(BlackjackHandEvaluator eval) { return rule.hit(hand(), eval); }
    }

    /* ===================== OBSERVER ===================== */

    interface GameEventListener { void onEvent(String event); }

    static final class ConsoleEventLogger implements GameEventListener {
        @Override public void onEvent(String event) { System.out.println("  [event] " + event); }
    }

    /* ===================== STATE: round phases ===================== */

    enum GameState { BETTING, DEALING, PLAYER_TURN, DEALER_TURN, SETTLEMENT, ROUND_OVER }

    /* ===================== ORCHESTRATOR: BlackjackGame ===================== */

    /**
     * Drives one round through the state machine. Depends only on abstractions
     * (strategies, evaluator, payout, listeners) injected via the constructor
     * (DIP). The enum-driven machine keeps illegal actions out of each phase.
     */
    static final class BlackjackGame {
        private final Shoe shoe;
        private final BlackjackHandEvaluator evaluator;
        private final PayoutRule payoutRule;
        private final Dealer dealer;
        private final List<Player> players;
        private final long minBet, maxBet;
        private final List<GameEventListener> listeners = new ArrayList<>();
        private GameState state = GameState.ROUND_OVER;

        BlackjackGame(Shoe shoe, BlackjackHandEvaluator evaluator, PayoutRule payoutRule,
                      Dealer dealer, List<Player> players, long minBet, long maxBet) {
            this.shoe = shoe;
            this.evaluator = evaluator;
            this.payoutRule = payoutRule;
            this.dealer = dealer;
            this.players = players;
            this.minBet = minBet;
            this.maxBet = maxBet;
        }

        void addListener(GameEventListener l) { listeners.add(l); }
        private void emit(String e) { for (GameEventListener l : listeners) l.onEvent(e); }
        private void transition(GameState next) { this.state = next; }

        /** Map a hand -> the bet covering it (parallel lists by index). */
        private final Map<Hand, Bet> handBets = new HashMap<>();

        /** Play exactly one full round. Each bet amount aligns by player index. */
        void playRound(Map<Player, Long> requestedBets) {
            handBets.clear();
            for (Player p : players) p.resetForRound();
            dealer.clearHands();

            betting(requestedBets);
            dealing();
            playerTurns();
            dealerTurn();
            settlement();
            roundOver();
        }

        /* ---- BETTING ---- */
        private void betting(Map<Player, Long> requestedBets) {
            transition(GameState.BETTING);
            for (Player p : players) {
                long amount = requestedBets.getOrDefault(p, minBet);
                Bet bet = p.placeBet(amount, minBet, maxBet);
                if (bet == null) {
                    emit(p.name() + " bet " + amount + " rejected (limits/balance).");
                    continue;
                }
                Hand h = new Hand();
                p.addHand(h);
                handBets.put(h, bet);
                emit(p.name() + " bets " + amount + " (balance " + p.balance() + ").");
            }
        }

        /* ---- DEALING ---- */
        private void dealing() {
            transition(GameState.DEALING);
            dealer.addHand(new Hand());
            // two cards each, players first then dealer (order is cosmetic here)
            for (int round = 0; round < 2; round++) {
                for (Player p : players)
                    for (Hand h : p.hands()) h.add(shoe.deal());
                dealer.hand().add(shoe.deal());
            }
            emit("Dealer shows " + dealer.upCard() + " (hole card hidden).");
            for (Player p : players)
                for (Hand h : p.hands())
                    emit(p.name() + " dealt " + h + " (=" + evaluator.value(h) + ")");
        }

        /* ---- PLAYER_TURN ---- */
        private void playerTurns() {
            transition(GameState.PLAYER_TURN);
            for (Player p : players) {
                // index-based loop: split may append new hands mid-iteration
                for (int i = 0; i < p.hands().size(); i++) {
                    playHand(p, p.hands().get(i));
                }
            }
        }

        private void playHand(Player p, Hand hand) {
            // Naturals need no action.
            if (evaluator.isBlackjack(hand)) { emit(p.name() + " has Blackjack!"); return; }
            boolean done = false;
            while (!done) {
                if (evaluator.isBust(hand)) { emit(p.name() + " busts with " + hand); return; }
                Bet bet = handBets.get(hand);
                boolean canDouble = hand.size() == 2 && bet != null && p.canAfford(bet.amount());
                boolean canSplit = canSplit(p, hand, bet);
                PlayerAction action = p.strategy().decide(hand, dealer.upCard(), evaluator, canDouble, canSplit);
                switch (action) {
                    case HIT:
                        hand.add(shoe.deal());
                        emit(p.name() + " hits -> " + hand + " (=" + evaluator.value(hand) + ")");
                        break;
                    case STAND:
                        emit(p.name() + " stands on " + evaluator.value(hand));
                        done = true;
                        break;
                    case DOUBLE_DOWN:
                        if (!canDouble) { done = true; break; }
                        p.debit(bet.amount());
                        bet.doubleStake();
                        hand.add(shoe.deal());
                        emit(p.name() + " doubles down -> " + hand + " (=" + evaluator.value(hand) + ")");
                        done = true; // exactly one card, then forced stand
                        break;
                    case SPLIT:
                        if (!canSplit) { done = true; break; }
                        doSplit(p, hand, bet);
                        // continue playing the (now 1-card) original hand
                        hand.add(shoe.deal());
                        emit(p.name() + " splits; this hand -> " + hand);
                        break;
                    default:
                        done = true;
                }
            }
        }

        private boolean canSplit(Player p, Hand hand, Bet bet) {
            if (hand.size() != 2 || bet == null) return false;
            if (!p.canAfford(bet.amount())) return false;
            return hand.getCards().get(0).getRank() == hand.getCards().get(1).getRank();
        }

        private void doSplit(Player p, Hand hand, Bet bet) {
            // Move the second card into a brand-new hand with its own matching bet.
            Card moved = hand.getCards().remove(1);
            Hand newHand = new Hand();
            newHand.add(moved);
            newHand.add(shoe.deal()); // deal one to the new hand
            p.debit(bet.amount());
            Bet newBet = new Bet(bet.amount());
            p.bets().add(newBet);
            p.addHand(newHand);
            handBets.put(newHand, newBet);
            emit(p.name() + " split into a new hand " + newHand);
        }

        /* ---- DEALER_TURN ---- */
        private void dealerTurn() {
            transition(GameState.DEALER_TURN);
            emit("Dealer reveals hole card: " + dealer.hand() + " (=" + evaluator.value(dealer.hand()) + ")");
            while (dealer.shouldHit(evaluator)) {
                dealer.hand().add(shoe.deal());
                emit("Dealer hits -> " + dealer.hand() + " (=" + evaluator.value(dealer.hand()) + ")");
            }
            emit("Dealer final: " + dealer.hand() + " (=" + evaluator.value(dealer.hand())
                    + (evaluator.isBust(dealer.hand()) ? ", BUST" : "") + ")");
        }

        /* ---- SETTLEMENT ---- */
        private void settlement() {
            transition(GameState.SETTLEMENT);
            Hand dh = dealer.hand();
            int dealerVal = evaluator.value(dh);
            boolean dealerBust = evaluator.isBust(dh);
            boolean dealerBJ = evaluator.isBlackjack(dh);

            for (Player p : players) {
                for (Hand h : p.hands()) {
                    Bet bet = handBets.get(h);
                    if (bet == null) continue;
                    Outcome outcome = resolve(h, dealerVal, dealerBust, dealerBJ);
                    long ret = payoutRule.payout(bet.amount(), outcome);
                    p.credit(ret);
                    bet.setStatus(toStatus(outcome));
                    emit(p.name() + " " + h + " -> " + outcome + ", returned " + ret
                            + " (balance " + p.balance() + ")");
                }
            }
        }

        private Outcome resolve(Hand player, int dealerVal, boolean dealerBust, boolean dealerBJ) {
            int pv = evaluator.value(player);
            boolean playerBust = evaluator.isBust(player);
            boolean playerBJ = evaluator.isBlackjack(player);
            if (playerBust) return Outcome.LOSE;            // bust always loses
            if (playerBJ && dealerBJ) return Outcome.PUSH;  // both naturals
            if (playerBJ) return Outcome.PLAYER_BLACKJACK;  // 3:2
            if (dealerBJ) return Outcome.LOSE;
            if (dealerBust) return Outcome.WIN;
            if (pv > dealerVal) return Outcome.WIN;
            if (pv < dealerVal) return Outcome.LOSE;
            return Outcome.PUSH;
        }

        private BetStatus toStatus(Outcome o) {
            switch (o) {
                case WIN: case PLAYER_BLACKJACK: return BetStatus.WON;
                case PUSH: return BetStatus.PUSHED;
                default: return BetStatus.LOST;
            }
        }

        /* ---- ROUND_OVER ---- */
        private void roundOver() {
            transition(GameState.ROUND_OVER);
            if (shoe.needsReshuffle()) {
                emit("Cut card reached — reshuffling shoe.");
                shoe.reshuffle();
            }
        }

        GameState state() { return state; }
    }

    /* ============================= DEMO ============================= */

    public static void main(String[] args) {
        System.out.println("=== Blackjack LLD demo ===\n");

        // Deterministic shuffle for a reproducible demo.
        ShuffleStrategy shuffle = new FisherYatesShuffle(42L);
        Shoe shoe = new Shoe(/*numDecks*/ 6, shuffle, /*penetration*/ 0.75);
        BlackjackHandEvaluator evaluator = new BlackjackHandEvaluator();
        PayoutRule payout = new StandardPayoutRule(); // 3:2 blackjack
        Dealer dealer = new Dealer(new StandardDealerStrategy(/*hitSoft17*/ false));

        Player alice = new Player("Alice", 1000, new BasicBotStrategy());
        Player bob   = new Player("Bob",   1000,
                new ScriptedStrategy(PlayerAction.HIT, PlayerAction.STAND));

        List<Player> players = new ArrayList<>();
        players.add(alice);
        players.add(bob);

        BlackjackGame game = new BlackjackGame(
                shoe, evaluator, payout, dealer, players, /*min*/ 10, /*max*/ 500);
        game.addListener(new ConsoleEventLogger());

        // Play three rounds.
        for (int round = 1; round <= 3; round++) {
            System.out.println("\n----- ROUND " + round + " -----");
            Map<Player, Long> bets = new HashMap<>();
            bets.put(alice, 100L);
            bets.put(bob, 50L);
            game.playRound(bets);
            System.out.println("End state: " + game.state()
                    + " | Alice=" + alice.balance() + " Bob=" + bob.balance()
                    + " | shoe remaining=" + shoe.remaining());
        }

        // Demonstrate ace soft/hard scoring directly.
        System.out.println("\n=== Ace scoring sanity checks ===");
        Hand soft = new Hand();
        soft.add(CardFactory.of(Suit.SPADES, Rank.ACE));
        soft.add(CardFactory.of(Suit.HEARTS, Rank.SIX));
        System.out.println("A♠ 6♥ -> value " + evaluator.value(soft)
                + ", soft=" + evaluator.isSoft(soft)); // 17, soft

        Hand twoAces = new Hand();
        twoAces.add(CardFactory.of(Suit.SPADES, Rank.ACE));
        twoAces.add(CardFactory.of(Suit.DIAMONDS, Rank.ACE));
        System.out.println("A♠ A♦ -> value " + evaluator.value(twoAces)); // 12

        Hand bj = new Hand();
        bj.add(CardFactory.of(Suit.CLUBS, Rank.ACE));
        bj.add(CardFactory.of(Suit.CLUBS, Rank.KING));
        System.out.println("A♣ K♣ -> value " + evaluator.value(bj)
                + ", blackjack=" + evaluator.isBlackjack(bj)); // 21, true

        // Flyweight check: factory returns the same shared instance.
        Card c1 = CardFactory.of(Suit.HEARTS, Rank.QUEEN);
        Card c2 = CardFactory.of(Suit.HEARTS, Rank.QUEEN);
        System.out.println("\nFlyweight shared instance for Q♥: " + (c1 == c2));
    }
}
