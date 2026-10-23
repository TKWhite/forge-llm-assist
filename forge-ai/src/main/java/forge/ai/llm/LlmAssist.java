package forge.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
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
    private static final int USE_NOW = 0, AFTER_COMBAT = 1, LATER = 2;

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
                && (!RoutedCards.isInstantSpeed(sa) || RoutedCards.isUnsupported(sa.getHostCard()));
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

        Set<String> seen = new HashSet<>();
        for (SpellAbility sa : routed) {
            Card host = sa.getHostCard();
            String id = host.getId() + ":" + sa.getId();
            if (!seen.add(id)) {
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
            if (decided == LATER || (decided == AFTER_COMBAT && main1)) {
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

            boolean go = decided == AFTER_COMBAT || decided == USE_NOW;
            if (decided < 0 && askedEnough(ai, host)) {
                // reactive cards come up at every opportunity: reuse this turn's last answer
                go = LlmGate.cached(ai, "last:" + host.getId()) == USE_NOW;
                decided = go ? USE_NOW : LATER;
            }
            if (decided < 0) {
                String what = (sa.isSpell() ? "cast " + host.getName() : "activate " + host.getName() + " (" + sa + ")")
                        + (xValue > 0 ? " with X=" + xValue : "");
                int held = holdTurns(ai, host);
                String question = "Should you " + what + "? This card needs judgment: " + RoutedCards.guidance(ai, host) + "."
                        + (held > 0 ? " You have already held it for " + held + (held == 1 ? " turn" : " turns")
                        + "; a card that is never used does nothing." : "");
                List<String> options = main1 ? MAIN1_OPTIONS : NOW_OPTIONS;
                int pick = LlmGate.askRouted(ai, "routed", "deciding whether to " + what, question,
                        options, List.of(host), -1, decisionKey + ":q:" + (main1 ? "main1" : window));
                if (pick < 0) {
                    continue; // no answer: the card stays unused this phase
                }
                int code = main1 ? pick : (pick == 0 ? USE_NOW : LATER);
                LlmGate.remember(ai, decisionKey, code);
                countAsk(ai, host, code == USE_NOW);
                if (code == LATER) {
                    holdCounts.computeIfAbsent(game, g -> new HashMap<>()).merge(host.getId(), 1, Integer::sum);
                }
                go = code == USE_NOW;
            }
            if (!go) {
                continue;
            }
            LlmGate.remember(ai, triedKey, 1);
            if (prepareRoutedTargets(aic, ai, sa)) {
                return sa;
            }
        }
        return null;
    }

    /** Reactive routed cards come up at every opportunity; questions per card per turn are capped at this. */
    private static final int MAX_ASKS_PER_CARD_TURN = 2;

    private static boolean askedEnough(Player ai, Card host) {
        return LlmGate.cached(ai, "asks:" + host.getId()) >= MAX_ASKS_PER_CARD_TURN;
    }

    private static void countAsk(Player ai, Card host, boolean useNow) {
        LlmGate.remember(ai, "asks:" + host.getId(), Math.max(0, LlmGate.cached(ai, "asks:" + host.getId())) + 1);
        LlmGate.remember(ai, "last:" + host.getId(), useNow ? USE_NOW : LATER);
    }

    /**
     * Targets for a routed ability. A single target is the model's pick from every legal target, with the
     * standard AI's pick as a hint; multi-target and targeted sub-abilities use the standard AI's targeting.
     */
    private static boolean prepareRoutedTargets(AiController aic, Player ai, SpellAbility sa) {
        boolean subTargets = false;
        for (AbilitySub sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            subTargets |= sub.usesTargeting();
        }
        if (!sa.usesTargeting() && !subTargets) {
            return true;
        }
        sa.resetTargets();
        boolean forgeTargeted = aic.canPlaySa(sa) == AiPlayDecision.WillPlay && sa.isTargetNumberValid();
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
        for (Card c : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (sa.canTarget(c)) {
                targets.add(c);
            }
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
                    "Choose the target for " + name + ".", describe(targets), List.of(sa.getHostCard()),
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
        if (sa == null || sa.getHostCard() == null || !RoutedCards.isRouted(ai, sa.getHostCard())
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgePick;
        }
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
        Card host = sa.getHostCard();
        int forgeIndex = forgePick == null ? (isOptional ? labels.size() - 1 : -1) : pool.indexOf(forgePick);
        int pick = LlmGate.askRouted(ai, "routed_choice", "making a choice for " + host.getName(),
                "You are resolving " + host.getName() + ". " + RoutedCards.guidance(ai, host) + ". What do you choose?",
                labels, List.of(host), forgeIndex, "routed_choice:" + host.getId() + ":" + sa.getId());
        if (pick < 0) {
            return forgePick;
        }
        return pick >= pool.size() ? null : pool.get(pick);
    }

    /**
     * A yes/no question while a routed card resolves: optional replacement effects ("you may have it enter
     * as a copy...") and "you may" effects.
     */
    public static boolean routedConfirm(Player ai, Card host, String question, boolean forgeAnswer) {
        if (host == null || !RoutedCards.isRouted(ai, host) || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgeAnswer;
        }
        String q = question == null || question.isBlank() ? "Do you want to use the optional effect?" : question;
        int pick = LlmGate.askRouted(ai, "routed_confirm", "deciding an optional effect of " + host.getName(),
                host.getName() + ": " + q + " " + RoutedCards.guidance(ai, host) + ".",
                List.of("Yes", "No"), List.of(host), forgeAnswer ? 0 : 1,
                "routed_confirm:" + host.getId() + ":" + q.hashCode());
        return pick < 0 ? forgeAnswer : pick == 0;
    }

    /** Mode choice for routed modal cards (single-mode choices); targets for a switched mode are re-chosen. */
    public static List<AbilitySub> routedModes(AiController aic, Player ai, SpellAbility sa, List<AbilitySub> possible,
                                               int num, List<AbilitySub> forgeModes) {
        if (num != 1 || possible.size() < 2 || sa.getHostCard() == null || !RoutedCards.isRouted(ai, sa.getHostCard())
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_ROUTED)) {
            return forgeModes;
        }
        List<String> labels = new ArrayList<>();
        for (AbilitySub mode : possible) {
            labels.add(mode.getDescription() == null || mode.getDescription().isBlank() ? mode.toString() : mode.getDescription());
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
        chosen.setActivatingPlayer(ai);
        if (chosen.usesTargeting()) {
            chosen.resetTargets();
            if (!aic.doTrigger(chosen, true) || !chosen.isTargetNumberValid()) {
                return forgeModes; // the model's mode has no usable target
            }
        }
        List<AbilitySub> result = new ArrayList<>();
        result.add(chosen);
        return result; // the engine sorts this list, so it must be mutable
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

    /** Mass removal of permanents; ChangeZoneAll also covers graveyard hate, which isn't a wipe. */
    private static boolean isWipe(SpellAbility sa) {
        if (sa.getApi() == null || !WIPE_APIS.contains(sa.getApi())) {
            return false;
        }
        return sa.getApi() != ApiType.ChangeZoneAll || sa.getParamOrDefault("Origin", "").contains("Battlefield");
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
            if (askedEnough(ai, host)) {
                return LlmGate.cached(ai, "last:" + host.getId()) == USE_NOW;
            }
            Game game = ai.getGame();
            SpellAbility top = game.getStack().isEmpty() ? null : game.getStack().peekAbility();
            String what = sa.isSpell() ? "cast " + host.getName() : "activate " + host.getName() + " (" + sa + ")";
            String context = top == null || top.getHostCard() == null ? "" : " in response to " + top.getHostCard().getName();
            int pick = LlmGate.askRouted(ai, "routed", "deciding whether to " + what + context,
                    "The standard AI wants to " + what + " now" + context + ". This card needs judgment: "
                            + RoutedCards.guidance(ai, host) + ". Should you?",
                    NOW_OPTIONS, List.of(host), 0,
                    // once per card per turn, plus once per spell it could respond to (the standard AI proposes
                    // instant-speed cards at every step, so a per-step key would ask over and over)
                    "routed_now:" + host.getId() + ":" + sa.getId() + ":" + (top == null ? 0 : top.getId()));
            if (pick >= 0) {
                countAsk(ai, host, pick == 0);
            }
            return pick != 1;
        }
        if (isWipe(sa) && LlmGate.mayAsk(ai, AiProps.LLM_HOOK_WIPE)) {
            int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_WIPE, "wipe", "deciding whether to cast " + host.getName() + " now",
                    "The standard AI wants to cast the mass-removal spell " + host.getName()
                            + " now. Considering everyone's board (including yours), should you cast it now or wait?",
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
                        + " Is now the right moment?",
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
