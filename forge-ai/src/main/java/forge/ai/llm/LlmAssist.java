package forge.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The decision points where the standard AI hands over to the model. Each method returns the
 * standard AI's own answer unchanged unless the gate allows a call and the call succeeds.
 */
public final class LlmAssist {
    private LlmAssist() {
    }

    /** Called once per AI player as a match starts: begin working out the game plan in the background. */
    public static void gameStarting(Player ai) {
        GamePlanner.start(ai);
    }

    // ------------------------------------------------------------------
    // Hook 1: routed cards, the ones the standard AI can't be trusted with (see RoutedCards).
    // The model decides when to use them, their targets and their choices; the standard AI never
    // plays them on its own.
    // ------------------------------------------------------------------

    private static final List<String> MAIN1_OPTIONS = List.of(
            "Use it now, before combat", "Use it after combat this turn", "Hold it for a later turn");
    private static final List<String> NOW_OPTIONS = List.of("Use it now", "Hold it for a later turn");
    private static final int USE_NOW = 0, AFTER_COMBAT = 1, LATER = 2, ASK_AGAIN = 3;

    public static boolean isRouted(Player ai, SpellAbility sa) {
        return RoutedCards.isRouted(ai, sa);
    }

    /**
     * Routed abilities the model offers proactively (so the standard AI must not play them itself):
     * sorcery-speed ones, and any the standard AI can't play at all. Instant-speed routed abilities stay
     * with the standard AI, which proposes them at the right moments (e.g. in response); the model then
     * confirms in {@link #confirmPlay}.
     */
    public static boolean isProactiveRouted(Player ai, SpellAbility sa) {
        return RoutedCards.isRouted(ai, sa)
                && (!RoutedCards.isInstantSpeed(sa) || RoutedCards.isUnsupported(sa.getHostCard()) || isXSpell(sa));
    }

    /**
     * Instant X spells (White Sun's Zenith) are offered at an opponent's end step rather than left to the standard
     * AI, which rarely proposes them: in a test match one sat in hand for 25 turns.
     */
    private static boolean isXSpell(SpellAbility sa) {
        return sa.isSpell() && RoutedCards.staticTags(sa.getHostCard()).contains(RoutedCards.Tag.X_COST);
    }

    /**
     * Offer the model each routed ability it could use right now. Windows: own main phases with an empty
     * stack, and an opponent's end step for instant-speed abilities. In the first main phase the question
     * is "now, before combat / after combat / a later turn", so one answer covers the whole turn.
     *
     * @return an ability to play (targets already set), or null
     */
    public static SpellAbility playRouted(AiController aic, Player ai, List<SpellAbility> routed) {
        if (routed.isEmpty() || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return null;
        }
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        if (ph.getPhase() == null) {
            return null;
        }
        boolean stackEmpty = game.getStack().isEmpty();
        SpellAbility top = stackEmpty ? null : game.getStack().peekAbility();
        boolean opponentOnStack = top != null && top.getActivatingPlayer() != null && top.getActivatingPlayer().isOpponentOf(ai);
        boolean ownMain = stackEmpty && ph.isPlayerTurn(ai) && ph.getPhase().isMain();
        boolean oppEndStep = stackEmpty && !ph.isPlayerTurn(ai) && ph.is(PhaseType.END_OF_TURN);
        if (!ownMain && !oppEndStep && !opponentOnStack) {
            return null;
        }
        boolean main1 = ownMain && ph.is(PhaseType.MAIN1);
        String window = opponentOnStack ? "stack" + top.getId() : String.valueOf(ph.getPhase());
        if (ownMain) {
            SpellAbility loyalty = playLoyaltyAbility(aic, ai, routed, main1);
            if (loyalty != null) {
                return loyalty;
            }
        }

        // biggest plays first, so a cheap card the model approves doesn't spend the mana before the commander or a
        // payoff is even offered
        List<SpellAbility> ordered = new ArrayList<>(routed);
        ordered.sort(Comparator.comparingInt((SpellAbility sa) -> -sa.getHostCard().getCMC())
                .thenComparing(sa -> sa.getHostCard().isCommander() ? 0 : 1));
        // the state says whose turn it is, but the question must too: the model approved Chainer's "you may cast a
        // creature from your graveyard this turn" at opponents' end steps, discarding a card for nothing each time
        String notYourTurn;
        if (ownMain) {
            notYourTurn = "";
        } else if (oppEndStep) {
            notYourTurn = " It is now " + ph.getPlayerTurn().getName() + "'s end step, not your turn: anything that"
                    + " lasts \"this turn\" ends in moments, and you can't cast sorcery-speed spells.";
        } else {
            notYourTurn = " You would be responding to " + top.getHostCard().getName() + (ph.isPlayerTurn(ai) ? "."
                    : ", during " + ph.getPlayerTurn().getName() + "'s turn: anything that lasts \"this turn\" ends"
                    + " this turn, and you can't cast sorcery-speed spells.");
        }

        Set<String> seen = new HashSet<>();
        for (SpellAbility sa : ordered) {
            Card host = sa.getHostCard();
            String id = host.getId() + ":" + sa.getId();
            if (sa.isPwAbility() || !seen.add(id)) {
                continue;
            }
            // sorcery-speed cards in own main phases; instant-speed ones (only offered here when the standard AI
            // can't play them at all) when responding to an opponent or at an opponent's end step
            boolean instant = RoutedCards.isInstantSpeed(sa);
            if (instant == ownMain) {
                continue;
            }
            String decisionKey = "routed:" + id + (instant ? ":" + window : "");
            String triedKey = decisionKey + ":tried:" + window;
            if (LlmGate.cached(ai, triedKey) >= 0) {
                continue; // already played (or failed to) in this phase
            }
            int decided = LlmGate.cached(ai, decisionKey);
            if (decided == ASK_AGAIN) {
                decided = -1;
            }
            if (decided == LATER || (decided == AFTER_COMBAT && main1) || (instant && decided < 0 && backingOff(ai, sa))) {
                continue;
            }
            sa.setActivatingPlayer(ai);
            if (!sa.canPlay() || !sa.canCastTiming(ai) || !ComputerUtilCost.canPayCost(sa, ai, false)) {
                continue;
            }
            int xValue = -1;
            if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana().countX() > 0) {
                xValue = ComputerUtilCost.setMaxXValue(sa, ai, false);
                if (xValue <= 0) {
                    continue;
                }
            }

            if (!hasUsefulTarget(ai, sa)) {
                continue;
            }

            boolean go = decided == AFTER_COMBAT || decided == USE_NOW;
            if (decided < 0 && askedEnough(ai, sa)) {
                // reactive cards come up at every opportunity: reuse this turn's last answer
                go = lastAnswer(ai, sa) == USE_NOW;
                decided = go ? USE_NOW : LATER;
            }
            if (decided < 0) {
                String what = playLabel(sa) + (xValue > 0 ? " with X=" + xValue : "");
                int held = holdTurns(ai, host);
                String cost = holdCost(ai, sa);
                String question = "Should you " + what + "?" + notYourTurn + " This card needs judgment: "
                        + RoutedCards.guidance(ai, host) + "."
                        + (held > 0 ? " You have already held it for " + held + (held == 1 ? " turn" : " turns")
                        + "; a card that is never used does nothing." : "") + cost;
                List<String> options = main1 ? MAIN1_OPTIONS : NOW_OPTIONS;
                String qKey = decisionKey + ":q:" + (main1 ? "main1" : window);
                // when holding costs something, using it this turn is the default the model must clearly beat
                int pick = cost.isEmpty()
                        ? LlmGate.askRouted(ai, "routed", "deciding whether to " + what, question, options, List.of(host), -1, qKey)
                        : LlmGate.askRoutedWithDefault(ai, "routed", "deciding whether to " + what, question, options,
                                List.of(host), main1 ? AFTER_COMBAT : 0, qKey);
                if (pick < 0) {
                    continue; // no answer: the card stays unused this phase
                }
                int code = main1 ? pick : (pick == 0 ? USE_NOW : LATER);
                LlmGate.remember(ai, decisionKey, code);
                countAsk(ai, sa, code == USE_NOW);
                if (code == LATER) {
                    holdCounts.computeIfAbsent(game, g -> new HashMap<>()).merge(host.getId(), 1, Integer::sum);
                }
                go = code == USE_NOW;
            }
            if (!go) {
                continue;
            }
            LlmGate.remember(ai, triedKey, 1);
            if (!sa.isSpell()) {
                // one "use it" covers one activation: an equip approved before combat isn't repeated after it
                LlmGate.remember(ai, decisionKey, ASK_AGAIN);
            }
            if (prepareRoutedTargets(aic, ai, sa, xValue)) {
                return sa;
            }
        }
        return null;
    }

    /** Life at or below this fraction of the starting total counts as under pressure. */
    private static final int LOW_LIFE_DIVISOR = 3;
    /** From this round on the game is late: a card held for a better moment may never get one. */
    private static final int LATE_ROUND = 8;

    /**
     * Why holding this card has a cost right now, for the question; empty if it has none. Unspent mana (with no
     * instant-speed card in hand to use it on, or at an opponent's end step where it's about to untap anyway),
     * low life and a late game all make "wait" more expensive. When any applies, using the card is the default.
     */
    static String holdCost(Player ai, SpellAbility sa) {
        List<String> notes = new ArrayList<>();
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        int mana = ComputerUtilMana.getAvailableManaEstimate(ai, false);
        if (mana > 0 && ph.getPhase() != null) {
            boolean ownMain = ph.isPlayerTurn(ai) && ph.getPhase().isMain();
            boolean lastWindow = !ph.isPlayerTurn(ai) && ph.is(PhaseType.END_OF_TURN);
            boolean otherInstants = ai.getCardsIn(ZoneType.Hand).anyMatch(c -> c != sa.getHostCard()
                    && !c.isLand() && (c.isInstant() || c.hasKeyword(Keyword.FLASH)));
            if (ownMain && !otherInstants) {
                notes.add("you have " + mana + " mana available and no instant-speed cards in hand, so mana you don't"
                        + " spend this turn is wasted");
            } else if (lastWindow) {
                notes.add("your " + mana + " untapped mana is wasted if not used before your untap step");
            }
        }
        if (ai.getLife() <= ai.getStartingLife() / LOW_LIFE_DIVISOR) {
            notes.add("you are at " + ai.getLife() + " life and under real pressure");
        }
        int round = StateSerializer.round(game);
        if (round >= LATE_ROUND) {
            notes.add("it is round " + round + ", late in the game, so a card held for a better moment may never be cast");
        }
        return notes.isEmpty() ? "" : " Holding has a cost: " + String.join("; ", notes) + ".";
    }

    private static final int LOYALTY_NONE = Integer.MAX_VALUE, LOYALTY_AFTER_COMBAT = Integer.MAX_VALUE - 1;

    /**
     * A routed planeswalker's loyalty abilities are one question per main phase ("which ability, if any?"), since
     * only one can be used per turn: asked one by one, whichever came first would win.
     */
    private static SpellAbility playLoyaltyAbility(AiController aic, Player ai, List<SpellAbility> routed, boolean main1) {
        Map<Card, List<SpellAbility>> byWalker = new LinkedHashMap<>();
        for (SpellAbility sa : routed) {
            if (!sa.isPwAbility()) {
                continue;
            }
            sa.setActivatingPlayer(ai);
            if (sa.canPlay() && sa.canCastTiming(ai) && ComputerUtilCost.canPayCost(sa, ai, false)) {
                List<SpellAbility> list = byWalker.computeIfAbsent(sa.getHostCard(), c -> new ArrayList<>());
                if (!list.contains(sa)) {
                    list.add(sa);
                }
            }
        }
        for (Map.Entry<Card, List<SpellAbility>> e : byWalker.entrySet()) {
            Card walker = e.getKey();
            List<SpellAbility> abilities = e.getValue();
            String key = "loyalty:" + walker.getId() + ":" + (main1 ? "main1" : "main2");
            int decided = LlmGate.cached(ai, key);
            if (decided == LOYALTY_NONE || decided == LOYALTY_AFTER_COMBAT) {
                continue;
            }
            if (decided < 0) {
                List<String> options = new ArrayList<>();
                for (SpellAbility sa : abilities) {
                    options.add(sa.toString().trim());
                }
                if (main1) {
                    options.add("Wait until after combat");
                }
                options.add("None this turn");
                String question = "Which loyalty ability of " + walker.getName() + " (loyalty "
                        + walker.getCounters(CounterEnumType.LOYALTY) + ") should you activate? This card needs judgment: "
                        + RoutedCards.guidance(ai, walker) + ".";
                int pick = LlmGate.askRouted(ai, "routed", "choosing a loyalty ability of " + walker.getName(), question,
                        options, List.of(walker), -1, key + ":q");
                if (pick < 0) {
                    continue;
                }
                decided = pick < abilities.size() ? abilities.get(pick).getId()
                        : main1 && pick == abilities.size() ? LOYALTY_AFTER_COMBAT : LOYALTY_NONE;
                LlmGate.remember(ai, key, decided);
                if (decided == LOYALTY_NONE || decided == LOYALTY_AFTER_COMBAT) {
                    continue;
                }
            }
            for (SpellAbility sa : abilities) {
                if (sa.getId() == decided) {
                    LlmGate.remember(ai, key, LOYALTY_NONE); // one try: a failed activation isn't retried
                    if (prepareRoutedTargets(aic, ai, sa, -1)) {
                        return sa;
                    }
                }
            }
        }
        return null;
    }

    /**
     * "cast X" / "activate X (...)", naming the way it is cast when there are several (Dash, Evoke, Flashback, the
     * adventure half...), since the model is asked about each of them separately.
     */
    static String playLabel(SpellAbility sa) {
        Card host = sa.getHostCard();
        if (!sa.isSpell()) {
            return "activate " + host.getName() + " (" + sa + ")";
        }
        String name = sa.getCardState() == null ? host.getName() : sa.getCardState().getName();
        StringBuilder s = new StringBuilder("cast ").append(name);
        if (!name.equals(host.getName())) {
            s.append(sa.isAdventure() ? " (the adventure of " : " (the other half of ").append(host.getName()).append(')');
        }
        if (sa.getAlternativeCost() != null) {
            s.append(" for its ").append(sa.getAlternativeCost()).append(" cost");
        }
        if (host.getZone() != null && !host.isInZone(ZoneType.Hand) && !host.isInZone(ZoneType.Command)) {
            s.append(" from your ").append(host.getZone().getZoneType().name().toLowerCase());
        }
        return s.toString();
    }

    /**
     * Reactive routed cards come up at every opportunity; questions per ability per turn are capped at this. Counted
     * per ability, not per card: a planeswalker's held -2 must not answer for its +2.
     */
    private static final int MAX_ASKS_PER_ABILITY_TURN = 2;

    private static String askKey(SpellAbility sa) {
        return sa.getHostCard().getId() + ":" + sa.getId();
    }

    private static boolean askedEnough(Player ai, SpellAbility sa) {
        return LlmGate.cached(ai, "asks:" + askKey(sa)) >= MAX_ASKS_PER_ABILITY_TURN;
    }

    private static int lastAnswer(Player ai, SpellAbility sa) {
        return LlmGate.cached(ai, "last:" + askKey(sa));
    }

    private static void countAsk(Player ai, SpellAbility sa, boolean useNow) {
        LlmGate.remember(ai, "asks:" + askKey(sa), Math.max(0, LlmGate.cached(ai, "asks:" + askKey(sa))) + 1);
        LlmGate.remember(ai, "last:" + askKey(sa), useNow ? USE_NOW : LATER);
        recordDecline(ai, sa, useNow);
    }

    /**
     * Reactive abilities the standard AI proposes at every step (Jarad's "sacrifice a Swamp and a Forest") but the
     * model keeps turning down: after declines in {@value #DECLINE_TURNS_BEFORE_BACKOFF} different turns, the ability
     * isn't asked about (and isn't used) for a round per further decline, up to {@value #MAX_BACKOFF_ROUNDS} rounds.
     */
    private static final int DECLINE_TURNS_BEFORE_BACKOFF = 2, MAX_BACKOFF_ROUNDS = 3;

    /** Per game and ability: {turns declined in, last turn declined, first turn it may be asked again}. */
    private static final Map<Game, Map<String, int[]>> declines = Collections.synchronizedMap(new WeakHashMap<>());

    private static void recordDecline(Player ai, SpellAbility sa, boolean useNow) {
        Game game = ai.getGame();
        Map<String, int[]> perAbility = declines.computeIfAbsent(game, g -> new ConcurrentHashMap<>());
        if (useNow) {
            perAbility.remove(askKey(sa));
            return;
        }
        int turn = game.getPhaseHandler().getTurn();
        int[] d = perAbility.computeIfAbsent(askKey(sa), k -> new int[] {0, -1, 0});
        if (d[1] == turn) {
            return;
        }
        d[0]++;
        d[1] = turn;
        if (d[0] >= DECLINE_TURNS_BEFORE_BACKOFF) {
            int rounds = Math.min(d[0] - DECLINE_TURNS_BEFORE_BACKOFF + 1, MAX_BACKOFF_ROUNDS);
            d[2] = turn + rounds * game.getPlayers().size();
        }
    }

    static boolean backingOff(Player ai, SpellAbility sa) {
        Map<String, int[]> perAbility = declines.get(ai.getGame());
        int[] d = perAbility == null ? null : perAbility.get(askKey(sa));
        return d != null && ai.getGame().getPhaseHandler().getTurn() < d[2];
    }

    /** False for a targeted ability with nothing worth targeting, so the model isn't asked about it. */
    private static boolean hasUsefulTarget(Player ai, SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return true;
        }
        GameEntity attachedTo = sa.getApi() == ApiType.Attach ? sa.getHostCard().getEntityAttachedTo() : null;
        for (Player p : ai.getGame().getPlayers()) {
            if (sa.canTarget(p)) {
                return true;
            }
        }
        for (Card c : ai.getGame().getCardsIn(sa.getTargetRestrictions().getZone())) {
            if (c != attachedTo && sa.canTarget(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Targets for a routed ability. A single target is the model's pick from every legal target, with the
     * standard AI's pick as a hint; multi-target and targeted sub-abilities use the standard AI's targeting.
     */
    private static boolean prepareRoutedTargets(AiController aic, Player ai, SpellAbility sa, int xValue) {
        boolean subTargets = false;
        for (AbilitySub sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            subTargets |= sub.usesTargeting();
        }
        if (!sa.usesTargeting() && !subTargets) {
            return true;
        }
        sa.resetTargets();
        boolean forgeTargeted = aic.canPlaySa(sa) == AiPlayDecision.WillPlay && sa.isTargetNumberValid();
        // the standard AI's check sets X to what it would pay (just enough to kill its target); the model was
        // asked about the X it approved, so that's the X cast
        if (xValue > 0) {
            sa.getRootAbility().setXManaCostPaid(xValue);
        }
        boolean single = sa.usesTargeting() && !subTargets
                && sa.getTargetRestrictions().getMaxTargets(sa.getHostCard(), sa) == 1;
        if (!single) {
            return forgeTargeted;
        }
        GameEntity forgePick = forgeTargeted ? sa.getTargets().getFirstTargetedCard() : null;
        if (forgeTargeted && forgePick == null) {
            forgePick = sa.getTargets().getFirstTargetedPlayer();
        }
        sa.resetTargets();

        List<GameEntity> targets = new ArrayList<>();
        for (Player p : ai.getGame().getPlayers()) {
            if (sa.canTarget(p)) {
                targets.add(p);
            }
        }
        // re-attaching equipment or an aura to what it's already on does nothing
        GameEntity attachedTo = sa.getApi() == ApiType.Attach ? sa.getHostCard().getEntityAttachedTo() : null;
        for (Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c != attachedTo && sa.canTarget(c)) {
                targets.add(c);
            }
        }
        if (forgePick != null && forgePick == attachedTo) {
            forgePick = null;
        }
        if (targets.isEmpty()) {
            return false;
        }
        // opponents' things first, then by value, capped (always keeping the standard AI's pick)
        targets.sort(Comparator.comparingInt((GameEntity e) -> owner(e) == ai ? 1 : 0)
                .thenComparing(Comparator.comparingInt(LlmAssist::value).reversed()));
        int max = Math.max(2, AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_OPTIONS) * 2);
        if (targets.size() > max) {
            List<GameEntity> top = new ArrayList<>(targets.subList(0, max));
            if (forgePick != null && !top.contains(forgePick) && targets.contains(forgePick)) {
                top.set(top.size() - 1, forgePick);
            }
            targets = top;
        }

        GameEntity chosen;
        if (targets.size() == 1) {
            chosen = targets.get(0);
        } else {
            String name = sa.getHostCard().getName();
            int pick = LlmGate.askRouted(ai, "routed_target", "choosing a target for " + name,
                    "Choose the target for " + name + (xValue > 0 ? " with X=" + xValue : "") + ".", describe(targets),
                    List.of(sa.getHostCard()),
                    forgePick == null ? -1 : targets.indexOf(forgePick), "routed_target:" + sa.getHostCard().getId());
            if (pick < 0) {
                if (forgePick == null) {
                    return false;
                }
                chosen = forgePick;
            } else {
                chosen = targets.get(pick);
            }
        }
        sa.getTargets().add(chosen);
        if (!sa.isTargetNumberValid() || !ComputerUtilCost.canPayCost(sa, ai, false)) {
            sa.resetTargets();
            return false;
        }
        return true;
    }

    /**
     * A choice made while a routed card resolves (copy which creature, which card, which player).
     * The model picks from all options; "none" is offered when the choice is optional.
     *
     * @return the model's pick (null = choose nothing), or {@code forgePick} if no answer
     */
    public static <T extends GameEntity> T routedChoice(Player ai, SpellAbility sa, Iterable<T> options, T forgePick,
                                                        boolean isOptional) {
        if (sa == null || sa.getHostCard() == null || !RoutedCards.routesChoices(ai, sa.getHostCard())
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgePick;
        }
        Card host = sa.getHostCard();
        // "choose nothing" is only the model's call when the card is routed for an optional choice, or the standard
        // AI itself chose nothing; otherwise the model picks which, not whether (e.g. Genesis Hydra's free permanent)
        isOptional &= forgePick == null || RoutedCards.staticTags(host).contains(RoutedCards.Tag.OPTIONAL_CHOICE);
        List<T> pool = new ArrayList<>();
        options.forEach(pool::add);
        if (pool.isEmpty() || (pool.size() == 1 && !isOptional)) {
            return forgePick;
        }
        pool.sort(Comparator.comparingInt((T e) -> value(e)).reversed());
        int max = Math.max(2, AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_OPTIONS) * 2);
        if (pool.size() > max) {
            List<T> top = new ArrayList<>(pool.subList(0, max));
            if (forgePick != null && !top.contains(forgePick)) {
                top.set(top.size() - 1, forgePick);
            }
            pool = top;
        }
        List<GameEntity> asEntities = new ArrayList<>(pool);
        List<String> labels = describe(asEntities);
        if (isOptional) {
            labels.add("None: choose nothing");
        }
        int forgeIndex = forgePick == null ? (isOptional ? labels.size() - 1 : -1) : pool.indexOf(forgePick);
        int pick = LlmGate.askRouted(ai, "routed_choice", "making a choice for " + host.getName(),
                choiceQuestion(ai, sa, host),
                labels, List.of(host), forgeIndex, "routed_choice:" + host.getId() + ":" + sa.getId());
        if (pick < 0) {
            return forgePick;
        }
        return pick >= pool.size() ? null : pool.get(pick);
    }

    /**
     * Quotes the effect being resolved, since the card's other abilities decide what the choice means (Eye of Doom's
     * doom counter marks the permanent for destruction). Someone else's card gets no plan notes or tags: they are
     * about the card's controller, and the chooser must pick for its own benefit.
     */
    private static String choiceQuestion(Player ai, SpellAbility sa, Card host) {
        SpellAbility root = sa.getRootAbility();
        String effect = root.isTrigger() && root.getTrigger() != null ? root.getTrigger().toString() : root.getDescription();
        String text = effect == null || effect.isBlank() ? "" : " The effect: \"" + effect.trim() + "\"";
        Player controller = host.getController();
        if (controller == null || controller == ai) {
            return "You are resolving your " + host.getName() + "." + text + " " + RoutedCards.guidance(ai, host)
                    + ". What do you choose?";
        }
        return controller.getName() + "'s " + host.getName() + " is resolving and you must make a choice for it."
                + text + " Read the card's full text: the chosen permanent may be harmed later. Choose what is best "
                + "for you, not for " + controller.getName() + ". What do you choose?";
    }

    /**
     * A yes/no question while a routed card resolves: optional replacement effects ("you may have it enter
     * as a copy...") and "you may" effects.
     */
    public static boolean routedConfirm(Player ai, Card host, String question, boolean forgeAnswer) {
        if (host == null || !RoutedCards.routesChoices(ai, host) || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgeAnswer;
        }
        String q = question == null || question.isBlank() ? "Do you want to use the optional effect?" : question;
        int pick = LlmGate.askRouted(ai, "routed_confirm", "deciding an optional effect of " + host.getName(),
                host.getName() + ": " + q + " " + RoutedCards.guidance(ai, host) + ".",
                List.of("Yes", "No"), List.of(host), forgeAnswer ? 0 : 1,
                "routed_confirm:" + host.getId() + ":" + q.hashCode());
        return pick < 0 ? forgeAnswer : pick == 0;
    }

    /**
     * Mode choice for routed modal cards; targets for the model's modes are re-chosen. Spells with several modes
     * (Fiery Confluence: three, repeats allowed) are picked one mode at a time: the standard AI often finds no set
     * of modes for those and the spell would be cast doing nothing.
     */
    public static List<AbilitySub> routedModes(AiController aic, Player ai, SpellAbility sa, List<AbilitySub> possible,
                                               int min, int num, boolean allowRepeat, List<AbilitySub> forgeModes) {
        if (possible.size() < (num == 1 ? 2 : 1) || sa.getHostCard() == null
                || !RoutedCards.routesChoices(ai, sa.getHostCard()) || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgeModes;
        }
        return num == 1 ? routedMode(aic, ai, sa, possible, forgeModes)
                : routedModeList(aic, ai, sa, possible, min, num, allowRepeat, forgeModes);
    }

    private static String modeLabel(AbilitySub mode) {
        return mode.getDescription() == null || mode.getDescription().isBlank() ? mode.toString() : mode.getDescription();
    }

    private static List<AbilitySub> routedMode(AiController aic, Player ai, SpellAbility sa, List<AbilitySub> possible,
                                               List<AbilitySub> forgeModes) {
        List<String> labels = new ArrayList<>();
        for (AbilitySub mode : possible) {
            labels.add(modeLabel(mode));
        }
        AbilitySub forgeMode = forgeModes == null || forgeModes.isEmpty() ? null : forgeModes.get(0);
        Card host = sa.getHostCard();
        int pick = LlmGate.askRouted(ai, "routed_mode", "choosing a mode for " + host.getName(),
                "Choose the mode for " + host.getName() + ".", labels, List.of(host),
                forgeMode == null ? -1 : possible.indexOf(forgeMode), "routed_mode:" + host.getId() + ":" + sa.getId());
        if (pick < 0 || possible.get(pick) == forgeMode) {
            return forgeModes;
        }
        AbilitySub chosen = possible.get(pick);
        if (!targetMode(aic, ai, chosen)) {
            return forgeModes; // the model's mode has no usable target
        }
        List<AbilitySub> result = new ArrayList<>();
        result.add(chosen);
        return result; // the engine sorts this list, so it must be mutable
    }

    private static List<AbilitySub> routedModeList(AiController aic, Player ai, SpellAbility sa, List<AbilitySub> possible,
                                                   int min, int num, boolean allowRepeat, List<AbilitySub> forgeModes) {
        Card host = sa.getHostCard();
        boolean forgeComplete = forgeModes != null && !forgeModes.isEmpty() && forgeModes.size() >= min;
        List<AbilitySub> chosen = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            List<AbilitySub> options = new ArrayList<>(possible);
            if (!allowRepeat) {
                options.removeAll(chosen);
            }
            if (options.isEmpty()) {
                break;
            }
            List<String> labels = new ArrayList<>();
            for (AbilitySub mode : options) {
                labels.add(modeLabel(mode));
            }
            boolean optional = i >= min;
            if (optional) {
                labels.add("No more modes");
            }
            List<String> sofar = new ArrayList<>();
            for (AbilitySub mode : chosen) {
                sofar.add(modeLabel(mode));
            }
            int hint = forgeModes != null && i < forgeModes.size() ? options.indexOf(forgeModes.get(i)) : -1;
            String question = "Choose mode " + (i + 1) + " of " + (min == num ? String.valueOf(num) : "up to " + num)
                    + " for " + host.getName() + (allowRepeat ? " (the same mode can be chosen more than once)" : "")
                    + (sofar.isEmpty() ? "" : ". Chosen so far: " + String.join("; ", sofar)) + ".";
            int pick = LlmGate.askRouted(ai, "routed_mode", "choosing modes for " + host.getName(), question, labels,
                    List.of(host), hint, "routed_mode:" + host.getId() + ":" + sa.getId() + ":" + i);
            if (pick < 0) {
                return forgeComplete ? forgeModes : chosen.size() >= min ? chosen : forgeModes;
            }
            if (pick >= options.size()) {
                break; // no more modes
            }
            chosen.add(options.get(pick));
        }
        for (AbilitySub mode : new HashSet<>(chosen)) {
            if (!targetMode(aic, ai, mode)) {
                chosen.removeIf(m -> m == mode);
            }
        }
        return chosen.size() >= Math.max(1, min) || !forgeComplete ? chosen : forgeModes;
    }

    /** Targets for a mode the model chose; false if it has no usable target. */
    private static boolean targetMode(AiController aic, Player ai, AbilitySub mode) {
        mode.setActivatingPlayer(ai);
        if (!mode.usesTargeting()) {
            return true;
        }
        mode.resetTargets();
        return aic.doTrigger(mode, true) && mode.isTargetNumberValid();
    }

    // ------------------------------------------------------------------
    // Hook 2: which opponent, when several are about equally threatening
    // ------------------------------------------------------------------

    /**
     * @param tied opponents whose threat scores are close enough that the standard AI would pick randomly
     * @return the chosen opponent, or null to let the standard AI pick
     */
    public static Player chooseOpponent(Player ai, Collection<Player> tied) {
        if (tied.size() < 2 || !LlmGate.hookEnabled(ai, AiProps.LLM_HOOK_DEFENDER)) {
            return null;
        }
        List<Player> opps = new ArrayList<>(tied);
        opps.sort(Comparator.comparingInt(Player::getId));
        StringBuilder key = new StringBuilder("opponent");
        for (Player p : opps) {
            key.append(':').append(p.getId());
        }
        // answers are reused for the whole turn; the timed evaluation thread can only use those
        int cached = LlmGate.cached(ai, key.toString());
        if (cached >= 0 && cached < opps.size()) {
            return opps.get(cached);
        }
        if (LlmGate.isEvalThread()) {
            return null;
        }
        List<String> options = new ArrayList<>();
        for (Player p : opps) {
            options.add(p.getName() + " (life " + p.getLife() + ", " + p.getCreaturesInPlay().size() + " creatures, "
                    + p.getCardsIn(ZoneType.Hand).size() + " cards in hand)");
        }
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_DEFENDER, "opponent", "choosing which opponent to focus",
                "Several opponents look about equally threatening. Which one should you focus your attacks "
                        + "and disruption on this turn?", options, List.of(), -1, key.toString());
        return pick < 0 ? null : opps.get(pick);
    }

    // ------------------------------------------------------------------
    // Hook 3: close calls between targets / chosen objects belonging to opponents
    // ------------------------------------------------------------------

    private static final Set<ApiType> REFINED_APIS = Set.of(ApiType.Destroy, ApiType.ChangeZone, ApiType.GainControl,
            ApiType.Sacrifice, ApiType.Tap);

    /**
     * After the standard AI picked a spell and its target, reconsider the target when it's a close call
     * between opponents' permanents or spans several opponents. Only single-target removal-like APIs.
     */
    public static void refineTarget(Player ai, SpellAbility sa) {
        if (sa == null || !sa.usesTargeting() || sa.getApi() == null || !REFINED_APIS.contains(sa.getApi())
                || sa.getTargets().size() != 1 || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_TARGETS)) {
            return;
        }
        if (sa.getTargetRestrictions().getMaxTargets(sa.getHostCard(), sa) != 1) {
            return;
        }
        GameEntity current = sa.getTargets().getFirstTargetedCard();
        if (current == null) {
            current = sa.getTargets().getFirstTargetedPlayer();
        }
        if (current == null || owner(current) == ai || !ai.isOpponentOf(owner(current))) {
            return;
        }

        List<GameEntity> pool = new ArrayList<>();
        if (current instanceof Card cur) {
            for (Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (c.getController().isOpponentOf(ai) && c.isCreature() == cur.isCreature() && sa.canTarget(c)) {
                    pool.add(c);
                }
            }
        } else {
            for (Player p : ai.getOpponents()) {
                if (sa.canTarget(p)) {
                    pool.add(p);
                }
            }
        }
        GameEntity better = closeCall(ai, sa, pool, current, "target", "choosing a target for " + sa.getHostCard().getName(),
                "Choose the best target for " + sa.getHostCard().getName() + ".");
        if (better == null || better == current) {
            return;
        }
        sa.resetTargets();
        sa.getTargets().add(better);
        if (!sa.isTargetNumberValid() || !ComputerUtilCost.canPayCost(sa, ai, false)) {
            sa.resetTargets();
            sa.getTargets().add(current);
        }
    }

    /** Same idea for choices made while an effect resolves ("choose a creature an opponent controls"). */
    public static <T extends GameEntity> T refineEntity(Player ai, SpellAbility sa, Iterable<T> options, T forgePick) {
        if (forgePick == null || sa == null || owner(forgePick) == ai || !ai.isOpponentOf(owner(forgePick))
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_TARGETS)) {
            return forgePick;
        }
        List<GameEntity> pool = new ArrayList<>();
        for (T o : options) {
            Player p = owner(o);
            if (p != null && p != ai && ai.isOpponentOf(p)) {
                pool.add(o);
            }
        }
        String name = sa.getHostCard() == null ? "an effect" : sa.getHostCard().getName();
        GameEntity better = closeCall(ai, sa, pool, forgePick, "choice", "making a choice for " + name,
                "Choose for " + name + ".");
        if (better == null) {
            return forgePick;
        }
        @SuppressWarnings("unchecked")
        T result = (T) better;
        return result;
    }

    /** Asks only when the top candidates score close together or belong to different opponents. */
    private static GameEntity closeCall(Player ai, SpellAbility sa, List<GameEntity> pool, GameEntity forgePick,
                                        String hook, String activity, String question) {
        if (pool.size() < 2 || !pool.contains(forgePick)) {
            return null;
        }
        pool.sort(Comparator.comparingInt(LlmAssist::value).reversed());
        int maxOptions = Math.max(2, AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_OPTIONS));
        List<GameEntity> top = new ArrayList<>(pool.subList(0, Math.min(maxOptions, pool.size())));
        if (!top.contains(forgePick)) {
            top.set(top.size() - 1, forgePick);
        }

        Set<Player> owners = new HashSet<>();
        for (GameEntity e : top) {
            owners.add(owner(e));
        }
        int best = value(top.get(0));
        int second = value(top.get(1));
        int closeness = AiProfileUtil.getIntProperty(ai, AiProps.LLM_CLOSENESS_PCT);
        boolean close = best <= 0 || (best - second) * 100 <= best * closeness;
        if (!close && owners.size() < 2) {
            return null;
        }

        List<Card> focus = new ArrayList<>();
        if (sa.getHostCard() != null) {
            focus.add(sa.getHostCard());
        }
        StringBuilder key = new StringBuilder(hook).append(':').append(sa.getHostCard() == null ? 0 : sa.getHostCard().getId());
        for (GameEntity e : top) {
            key.append(':').append(e.getId());
        }
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, hook, activity, question, describe(top), focus,
                top.indexOf(forgePick), key.toString());
        return pick < 0 ? null : top.get(pick);
    }

    // ------------------------------------------------------------------
    // Hook 4: how hard to attack this turn
    // ------------------------------------------------------------------

    /** Three broad stances; Forge's 0-5 levels are grouped into them (0-1, 2-3, 4-5). */
    private static final List<String> AGGRESSION_OPTIONS = List.of(
            "Hold back: keep blockers home, only attack where it is free (evasive or unblockable damage)",
            "Normal: attack where you expect good trades or to get damage through",
            "Push hard: attack expecting to trade, put real pressure on this player");
    /** the Forge level used when the model picks a stance different from Forge's */
    private static final int[] STANCE_LEVEL = {1, 3, 5};

    private static int stanceOf(int forgeLevel) {
        return forgeLevel <= 1 ? 0 : forgeLevel <= 3 ? 1 : 2;
    }

    /** Called once the standard AI settled on an aggression level for the real declare-attackers step. */
    public static int adjustAggression(Player ai, Player defender, List<Card> attackers, int forgeLevel) {
        // a single possible attacker or an already all-out attack aren't worth a call
        if (attackers.size() < 2 || defender == null || forgeLevel < 0 || forgeLevel >= 5) {
            return forgeLevel;
        }
        PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (!ph.isPlayerTurn(ai) || !ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS) || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_AGGRESSION)) {
            return forgeLevel;
        }
        List<String> names = new ArrayList<>();
        for (Card c : attackers) {
            names.add(StateSerializer.creatureLabel(c));
        }
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_AGGRESSION, "aggression", "deciding how hard to attack " + defender.getName(),
                "You are attacking " + defender.getName() + ". Possible attackers: " + String.join("; ", names)
                        + ". How aggressively should you attack this turn?",
                AGGRESSION_OPTIONS, List.of(), stanceOf(forgeLevel), "aggression:" + defender.getId());
        // same stance as the standard AI: keep its finer-grained level
        return pick < 0 || pick == stanceOf(forgeLevel) ? forgeLevel : STANCE_LEVEL[pick];
    }

    // ------------------------------------------------------------------
    // Hook 5: which card to tutor for
    // ------------------------------------------------------------------

    /** Library searches for non-land cards; land searches (ramp, fetches) stay with the standard AI. */
    public static Card refineTutor(Player ai, SpellAbility sa, List<ZoneType> origin, ZoneType destination,
                                   Collection<Card> fetchList, Card forgePick, Player decider) {
        if (forgePick == null || forgePick.isLand() || sa == null || (decider != null && decider != ai)
                || origin == null || origin.size() != 1 || origin.get(0) != ZoneType.Library) {
            return forgePick;
        }
        // the game plan's key cards beat the generic "most valuable card" pick, even without a model call
        GamePlan plan = GamePlanner.current(ai);
        Card planPick = null;
        if (plan != null && !plan.isKeyCard(forgePick.getName())) {
            Set<String> haveAccess = new HashSet<>();
            for (Card c : ai.getCardsIn(ZoneType.Hand)) {
                haveAccess.add(c.getName());
            }
            for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                haveAccess.add(c.getName());
            }
            for (Card c : fetchList) {
                if (plan.isKeyCard(c.getName()) && !haveAccess.contains(c.getName())) {
                    planPick = c;
                    break;
                }
            }
        }
        if (planPick != null) {
            forgePick = planPick;
        }
        if (!LlmGate.mayAsk(ai, AiProps.LLM_HOOK_TUTOR)) {
            return forgePick;
        }
        List<Card> pool = new ArrayList<>();
        for (Card c : fetchList) {
            if (!c.isLand()) {
                pool.add(c);
            }
        }
        if (pool.size() < 2) {
            return forgePick;
        }
        pool.sort(Comparator.comparingInt((Card c) -> value(c)).reversed());
        int max = Math.max(2, AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_OPTIONS) + 2);
        List<Card> top = new ArrayList<>(pool.subList(0, Math.min(max, pool.size())));
        if (!top.contains(forgePick)) {
            top.set(top.size() - 1, forgePick);
        }
        List<String> options = new ArrayList<>();
        for (Card c : top) {
            options.add(c.getName() + " (" + c.getType() + ", mana value " + c.getCMC() + ")");
        }
        List<Card> focus = new ArrayList<>(top);
        if (sa.getHostCard() != null) {
            focus.add(0, sa.getHostCard());
        }
        String source = sa.getHostCard() == null ? "an effect" : sa.getHostCard().getName();
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TUTOR, "tutor", "choosing a card to search for with " + source,
                "You are searching your library with " + source + " (the card goes to your " + destination
                        + "). Which card should you get?",
                options, focus, top.indexOf(forgePick), "tutor:" + (sa.getHostCard() == null ? 0 : sa.getHostCard().getId()));
        return pick < 0 ? forgePick : top.get(pick);
    }

    // ------------------------------------------------------------------
    // Hook 6: board wipe timing
    // ------------------------------------------------------------------

    private static final Set<ApiType> WIPE_APIS = Set.of(ApiType.DestroyAll, ApiType.ChangeZoneAll,
            ApiType.DamageAll, ApiType.SacrificeAll);
    private static final List<String> CONFIRM_OPTIONS = List.of("Yes, do it now", "No, wait");

    /**
     * Mass removal of permanents, including as a rider on a targeted spell (Blight Grenade: destroy target creature,
     * then all creatures get -3/-3). ChangeZoneAll also covers graveyard hate, which isn't a wipe.
     */
    static boolean isWipe(SpellAbility sa) {
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (isWipePart(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWipePart(SpellAbility sa) {
        ApiType api = sa.getApi();
        String which = sa.getParamOrDefault("ValidCards", "") + sa.getParamOrDefault("ChangeType", "");
        if (which.contains("Remembered") || which.contains("Targeted") || which.contains("Imprinted")) {
            return false; // the cards chosen earlier in the spell (Disorienting Choice), not everything
        }
        if (api == ApiType.PumpAll) {
            // shrinking every creature, not just the opponents'
            String valid = sa.getParamOrDefault("ValidCards", "");
            return sa.getParamOrDefault("NumDef", "").startsWith("-") && !valid.contains("OppCtrl")
                    && !valid.contains("YouDontCtrl");
        }
        if (api == null || !WIPE_APIS.contains(api)) {
            return false;
        }
        return api != ApiType.ChangeZoneAll || sa.getParamOrDefault("Origin", "").contains("Battlefield");
    }

    /**
     * Second opinion on a spell the standard AI decided to cast: board wipes, and payoffs the game plan
     * says to hold for the right moment. False means hold it this turn.
     */
    public static boolean confirmPlay(Player ai, SpellAbility sa) {
        Card host = sa.getHostCard();
        if (host == null) {
            return true;
        }
        if (RoutedCards.isRouted(ai, sa)) {
            // an instant-speed routed card the standard AI proposed: the model has the final say
            if (!LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
                return true;
            }
            if (RoutedCards.isInstantSpeed(sa) && backingOff(ai, sa)) {
                return false;
            }
            if (askedEnough(ai, sa)) {
                return lastAnswer(ai, sa) == USE_NOW;
            }
            Game game = ai.getGame();
            SpellAbility top = game.getStack().isEmpty() ? null : game.getStack().peekAbility();
            String what = playLabel(sa);
            String context = top == null || top.getHostCard() == null ? "" : " in response to " + top.getHostCard().getName();
            // the standard AI's "use it" stands unless the model is clearly more confident it should wait
            int pick = LlmGate.askRoutedWithDefault(ai, "routed", "deciding whether to " + what + context,
                    "The standard AI wants to " + what + " now" + context + ". This card needs judgment: "
                            + RoutedCards.guidance(ai, host) + "." + holdCost(ai, sa) + " Should you?",
                    NOW_OPTIONS, List.of(host), 0,
                    // once per card per turn, plus once per spell it could respond to (the standard AI proposes
                    // instant-speed cards at every step, so a per-step key would ask over and over)
                    "routed_now:" + host.getId() + ":" + sa.getId() + ":" + (top == null ? 0 : top.getId()));
            if (pick >= 0) {
                countAsk(ai, sa, pick == 0);
            }
            return pick != 1;
        }
        if (isWipe(sa) && LlmGate.mayAsk(ai, AiProps.LLM_HOOK_WIPE)) {
            int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_WIPE, "wipe", "deciding whether to " + playLabel(sa) + " now",
                    "The standard AI wants to " + playLabel(sa) + " now, which removes creatures or permanents en masse."
                            + " Considering everyone's board (including yours), should you do it now or wait?" + holdCost(ai, sa),
                    CONFIRM_OPTIONS, List.of(host), 0, "wipe:" + host.getId());
            return pick != 1;
        }
        GamePlan plan = sa.isSpell() ? GamePlanner.current(ai) : null;
        String castWhen = plan == null ? null : plan.holdCondition(host.getName());
        if (castWhen == null) {
            return true;
        }
        int alreadyHeld = holdTurns(ai, host);
        if (alreadyHeld >= AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_HOLD_TURNS)
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_HOLD)) {
            return true;
        }
        String key = "hold:" + host.getId();
        boolean askedThisTurn = LlmGate.cached(ai, key) >= 0;
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_HOLD, "hold", "deciding whether to cast " + host.getName() + " now",
                "The standard AI wants to cast " + host.getName() + " now. Your game plan says to hold it until: "
                        + castWhen + "." + (alreadyHeld > 0 ? " You have already held it for " + alreadyHeld
                        + (alreadyHeld == 1 ? " turn" : " turns") + "; a payoff that is never cast does nothing." : "")
                        + holdCost(ai, sa) + " Is now the right moment?",
                CONFIRM_OPTIONS, List.of(host), 0, key);
        if (pick == 1 && !askedThisTurn) {
            holdCounts.computeIfAbsent(ai.getGame(), g -> new HashMap<>()).merge(host.getId(), 1, Integer::sum);
        }
        return pick != 1;
    }

    /** Turns each held card has been held so far, per game (card ids are unique within a game). */
    private static final Map<Game, Map<Integer, Integer>> holdCounts = Collections.synchronizedMap(new WeakHashMap<>());

    private static int holdTurns(Player ai, Card host) {
        Map<Integer, Integer> counts = holdCounts.get(ai.getGame());
        return counts == null ? 0 : counts.getOrDefault(host.getId(), 0);
    }

    // ------------------------------------------------------------------
    // Hook 7: counterspells, only against spells that matter
    // ------------------------------------------------------------------

    /** Second opinion on countering a big spell. False means let it resolve. */
    public static boolean confirmCounter(Player ai, SpellAbility counter) {
        Game game = ai.getGame();
        SpellAbility top = game.getStack().isEmpty() ? null : game.getStack().peekAbility();
        if (top == null || top.getHostCard() == null || top.getActivatingPlayer() == ai) {
            return true;
        }
        Card target = top.getHostCard();
        boolean tutor = top.getApi() == ApiType.ChangeZone && "Library".equals(top.getParam("Origin"));
        boolean significant = target.getCMC() >= 4 || target.isCommander() || tutor || isWipe(top);
        if (!significant || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_COUNTER)) {
            return true;
        }
        Card with = counter.getHostCard();
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_COUNTER, "counter", "deciding whether to counter " + target.getName(),
                top.getActivatingPlayer().getName() + " is casting " + target.getName() + ". Should you counter it with "
                        + with.getName() + ", or save the counter for something more dangerous?",
                CONFIRM_OPTIONS, List.of(target, with), 0, "counter:" + target.getId() + ":" + with.getId());
        return pick != 1;
    }

    // ------------------------------------------------------------------
    // Hook 8: mulligan, decided by rule from the hand's facts (no model call)
    // ------------------------------------------------------------------

    /** An opening hand, worked out in code. */
    record HandFacts(int size, int finalSize, int lands, int landColors, int ramp, int earlyPlays, int offColorPlays,
                     boolean freeMulligan, int commanderMv) {
    }

    static HandFacts handFacts(Player ai, int cardsToReturn, boolean free) {
        List<Card> hand = new ArrayList<>(ai.getCardsIn(ZoneType.Hand));
        int lands = 0, ramp = 0, early = 0, offColor = 0;
        byte colors = 0;
        for (Card c : hand) {
            if (c.isLand()) {
                lands++;
                for (SpellAbility m : c.getManaAbilities()) {
                    for (byte col : MagicColor.WUBRG) {
                        if (m.canProduce(MagicColor.toLongString(col))) {
                            colors |= col;
                        }
                    }
                }
            }
        }
        for (Card c : hand) {
            if (c.isLand()) {
                continue;
            }
            boolean cheap = c.getCMC() <= 3;
            if (cheap && isRamp(c)) {
                ramp++;
            }
            if (cheap) {
                byte needs = c.getManaCost().getColorProfile();
                if ((needs & ~colors) == 0) {
                    early++;
                } else {
                    offColor++;
                }
            }
        }
        int commanderMv = 0;
        for (Card cmd : ai.getCommanders()) {
            commanderMv = Math.max(commanderMv, cmd.getCMC());
        }
        return new HandFacts(hand.size(), hand.size() - cardsToReturn, lands, Integer.bitCount(colors & 0xFF), ramp,
                early, offColor, free, commanderMv);
    }

    /** Mana rocks and dorks, and spells that put lands onto the battlefield. */
    private static boolean isRamp(Card c) {
        if (!c.getManaAbilities().isEmpty()) {
            return true;
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
                if (part.getApi() == ApiType.ChangeZone && "Library".equals(part.getParam("Origin"))
                        && part.getParamOrDefault("ChangeType", "").contains("Land")
                        && part.getParamOrDefault("Destination", "").contains("Battlefield")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return 1 keep, 0 mulligan. Deliberately conservative and rule-only: in testing, sending borderline hands
     * to the model made the AI mulligan far too often and end up with fewer lands, not more.
     */
    static int mulliganRule(HandFacts h) {
        if (h.finalSize() <= 5) {
            return h.lands() == 0 && h.finalSize() == 5 ? 0 : 1; // going lower is worse than almost any hand
        }
        if (h.lands() <= 1) {
            return 0;
        }
        if (h.lands() == 2) {
            return h.ramp() == 0 && h.freeMulligan() ? 0 : 1;
        }
        if (h.lands() >= 6) {
            return h.freeMulligan() || h.lands() >= 7 ? 0 : 1;
        }
        return 1; // 3 to 5 lands
    }

    /** Every keep/mulligan decision, by {@link #mulliganRule}. Also starts the game plan for the rest of the game. */
    public static boolean keepHand(Player ai, boolean forgeKeep, int cardsToReturn) {
        if (!LlmGate.hookEnabled(ai, AiProps.LLM_HOOK_MULLIGAN) || LlmGate.isEvalThread()) {
            return forgeKeep;
        }
        GamePlanner.start(ai);
        int decision = LlmGate.gameMemory(ai, "mulligan-decisions");
        LlmGate.setGameMemory(ai, "mulligan-decisions", decision + 1);
        if (decision == 0) {
            // the first decision of the game: give a new deck's plan up to LLM_PLAN_WAIT_MS to arrive
            GamePlanner.await(ai);
        }
        // the first mulligan is free in multiplayer Commander
        boolean free = decision == 0 && ai.getGame().getPlayers().size() > 2;
        HandFacts facts = handFacts(ai, cardsToReturn, free);
        int rule = mulliganRule(facts);
        ai.getGame().getGameLog().add(GameLogEntryType.STACK_ADD, "[AI assist] " + ai.getName() + " mulligan rule: "
                + (rule == 1 ? "keep" : "mulligan") + " (" + facts.lands() + " lands, " + facts.ramp() + " ramp, "
                + facts.earlyPlays() + " early plays, " + facts.finalSize() + " cards" + (free ? ", free mulligan" : "") + ")");
        return rule == 1;
    }

    // ------------------------------------------------------------------

    private static Player owner(GameEntity e) {
        if (e instanceof Card c) {
            return c.getController();
        }
        return e instanceof Player p ? p : null;
    }

    /** Creature evaluation for creatures, mana-value based on the same scale otherwise; life for players. */
    static int value(GameEntity e) {
        if (e instanceof Card c) {
            return c.isCreature() ? ComputerUtilCard.evaluateCreature(c) : 100 + 30 * c.getCMC();
        }
        return e instanceof Player p ? 40 - Math.min(40, p.getLife()) + 100 : 0;
    }

    private static List<String> describe(List<GameEntity> entities) {
        List<String> out = new ArrayList<>();
        for (GameEntity e : entities) {
            if (e instanceof Card c) {
                out.add(StateSerializer.label(c));
            } else if (e instanceof Player p) {
                out.add("player " + p.getName() + " (life " + p.getLife() + ")");
            } else {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }
}
