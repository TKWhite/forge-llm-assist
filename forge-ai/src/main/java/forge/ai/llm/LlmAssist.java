package forge.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.SpellApiToAi;
import forge.ai.ability.CannotPlayAi;
import forge.game.Game;
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
    private static final List<String> CAST_OPTIONS = List.of("Yes, use it now", "No, hold it for later");

    private LlmAssist() {
    }

    // ------------------------------------------------------------------
    // Hook 1: cards the standard AI can't play (AI:RemoveDeck:All, or no AI logic for the API)
    // ------------------------------------------------------------------

    /** Spell abilities the standard AI would never use on its own. */
    public static boolean isUnsupported(SpellAbility sa) {
        if (sa.isLandAbility() || sa.getHostCard() == null) {
            return false;
        }
        if (ComputerUtilCard.isCardRemAIDeck(sa.getHostCard())) {
            return true;
        }
        return sa.getApi() != null && SpellApiToAi.Converter.get(sa) instanceof CannotPlayAi;
    }

    /**
     * Called when the standard AI found nothing it wants to do with priority on its own turn.
     * Asks once per card per turn whether to use one of the unsupported cards now and, if it has
     * a single simple target, which target.
     */
    public static SpellAbility playUnsupported(AiController aic, Player ai, List<SpellAbility> candidates) {
        if (candidates.isEmpty() || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_UNSUPPORTED_CARDS)) {
            return null;
        }
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        if (!game.getStack().isEmpty() || !ph.isPlayerTurn(ai) || !ph.getPhase().isMain()) {
            return null;
        }

        Set<Card> seen = new HashSet<>();
        for (SpellAbility sa : candidates) {
            Card host = sa.getHostCard();
            if (!seen.add(host)) {
                continue;
            }
            String key = "unsupported:" + host.getId() + ":" + ph.getPhase();
            if (LlmGate.cached(ai, key) >= 0) {
                continue; // already asked (or tried) this turn
            }
            sa.setActivatingPlayer(ai);
            if (!isSimpleEnough(sa) || !sa.canPlay() || !sa.canCastTiming(ai)
                    || !ComputerUtilCost.canPayCost(sa, ai, false)) {
                continue;
            }

            String what = sa.isSpell() ? "cast " + host.getName() : "activate " + host.getName() + " (" + sa + ")";
            int answer = LlmGate.ask(ai, AiProps.LLM_HOOK_UNSUPPORTED_CARDS, "unsupported_card",
                    "deciding whether to " + what,
                    "The standard AI can't evaluate this card. Should you " + what + " now?",
                    CAST_OPTIONS, List.of(host), -1, key);
            // whatever happens, don't ask about this card again this phase
            LlmGate.remember(ai, key, 1);
            if (answer != 0) {
                continue;
            }
            if (prepareTargets(aic, ai, sa)) {
                return sa;
            }
        }
        return null;
    }

    /** X costs, multiple targets and targeted sub-abilities are left alone in this version. */
    private static boolean isSimpleEnough(SpellAbility sa) {
        if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana().countX() > 0) {
            return false;
        }
        if (sa.usesTargeting() && sa.getTargetRestrictions().getMaxTargets(sa.getHostCard(), sa) != 1) {
            return false;
        }
        for (AbilitySub sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if (sub.usesTargeting()) {
                return false;
            }
        }
        return true;
    }

    private static boolean prepareTargets(AiController aic, Player ai, SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return true;
        }
        // the API logic may still be able to target even if it wouldn't choose to play the card
        sa.resetTargets();
        if (aic.canPlaySa(sa) == AiPlayDecision.WillPlay && sa.isTargetNumberValid()) {
            return true;
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
        // opponents' things first, then by value, capped
        targets.sort(Comparator.comparingInt((GameEntity e) -> owner(e) == ai ? 1 : 0)
                .thenComparing(Comparator.comparingInt(LlmAssist::value).reversed()));
        int max = Math.max(2, AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_OPTIONS) * 2);
        if (targets.size() > max) {
            targets = new ArrayList<>(targets.subList(0, max));
        }

        GameEntity chosen;
        if (targets.size() == 1) {
            chosen = targets.get(0);
        } else {
            List<String> options = describe(targets);
            String key = "unsupported_target:" + sa.getHostCard().getId();
            int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_UNSUPPORTED_CARDS, "unsupported_target",
                    "choosing a target for " + sa.getHostCard().getName(),
                    "Choose the target for " + sa.getHostCard().getName() + ".", options, List.of(sa.getHostCard()), -1, key);
            if (pick < 0) {
                return false;
            }
            chosen = targets.get(pick);
        }
        sa.getTargets().add(chosen);
        if (!sa.isTargetNumberValid() || !ComputerUtilCost.canPayCost(sa, ai, false)) {
            sa.resetTargets();
            return false;
        }
        return true;
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

    private static final List<String> AGGRESSION_OPTIONS = List.of(
            "0: stay home and block",
            "1: only attack with creatures that can't be blocked profitably / evasive damage",
            "2: attack where you expect to destroy blockers or go unblocked",
            "3: attack expecting good trades or player damage",
            "4: attack expecting to trade or damage the player",
            "5: all-out attack, attack at all costs");

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
                AGGRESSION_OPTIONS, List.of(), forgeLevel, "aggression:" + defender.getId());
        return pick < 0 ? forgeLevel : pick;
    }

    // ------------------------------------------------------------------
    // Hook 5: which card to tutor for
    // ------------------------------------------------------------------

    /** Library searches for non-land cards; land searches (ramp, fetches) stay with the standard AI. */
    public static Card refineTutor(Player ai, SpellAbility sa, List<ZoneType> origin, ZoneType destination,
                                   Collection<Card> fetchList, Card forgePick, Player decider) {
        if (forgePick == null || forgePick.isLand() || sa == null || (decider != null && decider != ai)
                || origin == null || origin.size() != 1 || origin.get(0) != ZoneType.Library
                || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_TUTOR)) {
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

    /** Second opinion on a board wipe the standard AI decided to cast. False means hold it this turn. */
    public static boolean confirmPlay(Player ai, SpellAbility sa) {
        if (!isWipe(sa) || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_WIPE)) {
            return true;
        }
        Card host = sa.getHostCard();
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_WIPE, "wipe", "deciding whether to cast " + host.getName() + " now",
                "The standard AI wants to cast the mass-removal spell " + host.getName()
                        + " now. Considering everyone's board (including yours), should you cast it now or wait?",
                CONFIRM_OPTIONS, List.of(host), 0, "wipe:" + host.getId());
        return pick != 1;
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
    // Hook 8: mulligan (off by default)
    // ------------------------------------------------------------------

    private static final List<String> MULLIGAN_OPTIONS = List.of("Keep this hand", "Mulligan");

    /** Only the first keep/mulligan decision of the game goes to the model; later ones stay standard. */
    public static boolean keepHand(Player ai, boolean forgeKeep) {
        if (LlmGate.cached(ai, "mulligan-asked") >= 0 || !LlmGate.mayAsk(ai, AiProps.LLM_HOOK_MULLIGAN)) {
            return forgeKeep;
        }
        LlmGate.remember(ai, "mulligan-asked", 1);
        List<Card> hand = new ArrayList<>(ai.getCardsIn(ZoneType.Hand));
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_MULLIGAN, "mulligan", "deciding whether to mulligan",
                "Your opening hand has " + hand.size() + " cards. Keep it or mulligan? Your commander is always available.",
                MULLIGAN_OPTIONS, hand, forgeKeep ? 0 : 1, "mulligan");
        return pick < 0 ? forgeKeep : pick == 0;
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
