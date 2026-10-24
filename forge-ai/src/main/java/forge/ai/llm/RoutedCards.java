package forge.ai.llm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellApiToAi;
import forge.ai.ability.CannotPlayAi;
import forge.game.CardTraitBase;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/**
 * Decides which cards the standard AI can't be trusted with. Those cards are "routed": the model
 * makes their choices, and for timing-sensitive ones also decides when to use them, instead of the standard AI.
 * <p>
 * Two sources, merged:
 * <ul>
 * <li>signals in the card's Forge script (no model needed), see {@link Tag};</li>
 * <li>the game plan's {@code contextCards}: cards the planner flagged as dual-use or timing-sensitive,
 * each with one line of guidance.</li>
 * </ul>
 * Signals from named keywords (cascade, suspend, madness, dredge...) are rules the standard AI already
 * handles and never route a card.
 */
public final class RoutedCards {

    public enum Tag {
        UNSUPPORTED(true, "the standard AI can't play this card at all"),
        AI_UNSURE(false, "the standard AI is known to play this card poorly"),
        TEMPORARY(true, "part of its effect only lasts until end of turn, so timing matters (e.g. a hasted copy should be made before combat)"),
        OPTIONAL_CHOICE(false, "it has an optional choice that depends on the board (what to copy, take, sacrifice or discard)"),
        MODAL(false, "it has several modes; pick the one that fits the board"),
        X_COST(true, "it has an X cost; casting it too early wastes it"),
        EXTRA_COMBAT(true, "it adds a combat phase; best when attackers are ready");

        /**
         * True when the signal is about when to use the card, so the model also takes over its timing. The others
         * are about choices made as it resolves: the standard AI still decides when to cast it.
         */
        public final boolean timing;
        public final String description;

        Tag(boolean timing, String description) {
            this.timing = timing;
            this.description = description;
        }
    }

    /**
     * Optional effects whose "yes or no" depends on the board. Other optional effects (free casts, "look at the
     * top cards and take one", "you may put it onto the battlefield", mill, scry) are nearly always taken, and the
     * standard AI decides them well.
     */
    private static final EnumSet<ApiType> JUDGMENT_OPTIONAL = EnumSet.of(ApiType.Clone, ApiType.Animate,
            ApiType.GainControl, ApiType.ExchangeControl, ApiType.ChangeTargets, ApiType.CopySpellAbility,
            ApiType.Sacrifice, ApiType.Discard);

    /** script signals are the same for every copy of a card, so cache by name */
    private static final Map<String, EnumSet<Tag>> tagCache = new ConcurrentHashMap<>();

    private RoutedCards() {
    }

    public static boolean enabled(Player ai) {
        return LlmGate.hookEnabled(ai, AiProps.LLM_HOOK_ROUTED);
    }

    /**
     * True if the model, not the standard AI, should decide when to use this spell or ability. Spells go by the
     * whole card (what it does on entering counts); activated abilities go by their own text, so a card with one
     * tricky ability doesn't take its equip, crew or other abilities away from the standard AI.
     */
    public static boolean isRouted(Player ai, SpellAbility sa) {
        if (sa == null || sa.isManaAbility() || sa.isLandAbility() || sa.getHostCard() == null) {
            return false;
        }
        Card card = sa.getHostCard();
        if (!enabled(ai) || card.isLand() && !card.isCreature() && staticTags(card).isEmpty()) {
            return false;
        }
        if (planFlagged(ai, card)) {
            return true;
        }
        EnumSet<Tag> tags = sa.isSpell() ? staticTags(card) : abilityTags(sa);
        for (Tag t : tags) {
            if (t.timing) {
                return true;
            }
        }
        return false;
    }

    /** True if the model makes the choices while this card resolves (modes, what to copy, optional effects). */
    public static boolean routesChoices(Player ai, Card card) {
        if (card == null || card.isLand() && !card.isCreature() && staticTags(card).isEmpty()) {
            return false;
        }
        if (!enabled(ai)) {
            return false;
        }
        EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
        tags.addAll(staticTags(card));
        // "AI unsure" cards are still played by the standard AI, just not always well; on their own they only
        // add guidance, since routing them would take away the standard AI's responses (protection spells etc.)
        tags.remove(Tag.AI_UNSURE);
        return !tags.isEmpty() || planFlagged(ai, card);
    }

    private static boolean planFlagged(Player ai, Card card) {
        GamePlan plan = GamePlanner.current(ai);
        return plan != null && plan.contextGuidance(card.getName()) != null;
    }

    /** Signals in one activated ability's own text, plus the card being unplayable by the standard AI. */
    static EnumSet<Tag> abilityTags(SpellAbility sa) {
        EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
        if (isUnsupported(sa.getHostCard())) {
            tags.add(Tag.UNSUPPORTED);
        }
        if (isRulesKeyword(sa)) {
            return tags;
        }
        if (sa.getApi() != null && SpellApiToAi.Converter.get(sa) instanceof CannotPlayAi) {
            tags.add(Tag.UNSUPPORTED);
        }
        if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana().countX() > 0) {
            tags.add(Tag.X_COST);
        }
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            inspect(part, tags);
        }
        return tags;
    }

    /**
     * Whether a "you may" question while a routed card resolves should go to the model. Free casts and other
     * optional effects that are nearly always taken stay with the standard AI.
     */
    public static boolean isJudgmentConfirm(SpellAbility sa) {
        if (sa == null || sa.getApi() == null || isRulesKeyword(sa)) {
            return false;
        }
        if (sa.getApi() == ApiType.Play && sa.hasParam("WithoutManaCost")) {
            return false;
        }
        return JUDGMENT_OPTIONAL.contains(sa.getApi()) || isUnsupported(sa.getHostCard());
    }

    /** Why this card is routed, for the model's prompt. */
    public static String guidance(Player ai, Card card) {
        List<String> parts = new ArrayList<>();
        GamePlan plan = GamePlanner.current(ai);
        if (plan != null) {
            String g = plan.contextGuidance(card.getName());
            if (g != null) {
                parts.add("Plan note: " + g);
            }
            String hold = plan.holdCondition(card.getName());
            if (hold != null) {
                parts.add("Plan says hold it until: " + hold);
            }
        }
        for (Tag t : staticTags(card)) {
            parts.add(t.description);
        }
        return String.join(". ", parts);
    }

    public static EnumSet<Tag> staticTags(Card card) {
        String name = card.getName();
        if (name == null || name.isBlank() || card.isToken() || card.isFaceDown()) {
            return EnumSet.noneOf(Tag.class);
        }
        return tagCache.computeIfAbsent(name, n -> {
            try {
                return classify(card);
            } catch (RuntimeException e) {
                return EnumSet.noneOf(Tag.class); // unusual card states (no paper card): leave it to the standard AI
            }
        });
    }

    /** Instants, flash spells and activated abilities without a sorcery-speed restriction. */
    public static boolean isInstantSpeed(SpellAbility sa) {
        Card host = sa.getHostCard();
        if (sa.isSpell()) {
            return host.isInstant() || host.hasKeyword(Keyword.FLASH);
        }
        return !sa.getRestrictions().isSorcerySpeed();
    }

    /**
     * True for parts that come from a named keyword such as cascade. Card-specific "K:" lines like
     * ETBReplacement parse as UNDEFINED and still count as the card's own text.
     */
    public static boolean isRulesKeyword(CardTraitBase part) {
        return part.getKeyword() != null && part.getKeyword().getKeyword() != Keyword.UNDEFINED;
    }

    /** Cards the standard AI can't play at all, so it will never propose them itself. */
    public static boolean isUnsupported(Card card) {
        return staticTags(card).contains(Tag.UNSUPPORTED);
    }

    static EnumSet<Tag> classify(Card card) {
        EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
        if (ComputerUtilCard.isCardRemAIDeck(card)) {
            tags.add(Tag.UNSUPPORTED);
        }
        if (card.getRules() != null && card.getRules().getAiHints().getRemRandomDecks()) {
            tags.add(Tag.AI_UNSURE);
        }
        if (card.getManaCost() != null && card.getManaCost().countX() > 0) {
            tags.add(Tag.X_COST);
        }
        for (SpellAbility sa : card.getSpellAbilities()) {
            if (sa.isManaAbility() || isRulesKeyword(sa)) {
                continue;
            }
            if (sa.getApi() != null && SpellApiToAi.Converter.get(sa) instanceof CannotPlayAi) {
                tags.add(Tag.UNSUPPORTED);
            }
            for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
                inspect(part, tags);
            }
        }
        // keyword triggers and replacements (cascade, etc.) are rules the standard AI handles, so they don't tag the card
        for (Trigger t : card.getTriggers()) {
            if (isRulesKeyword(t)) {
                continue;
            }
            for (SpellAbility part = t.getOverridingAbility(); part != null; part = part.getSubAbility()) {
                inspect(part, tags);
            }
        }
        for (ReplacementEffect re : card.getReplacementEffects()) {
            if (isRulesKeyword(re)) {
                continue;
            }
            if (re.hasParam("Optional")) {
                tags.add(Tag.OPTIONAL_CHOICE);
            }
            SpellAbility over = re.getOverridingAbility();
            for (SpellAbility part = over; part != null; part = part.getSubAbility()) {
                inspect(part, tags);
            }
        }
        // abilities referenced only through SVars (e.g. an ETB replacement's DB$ Clone ... Duration$ UntilEndOfTurn)
        for (String svar : card.getSVars().values()) {
            if (svar.contains("Duration$ UntilEndOfTurn") && (svar.startsWith("DB$ Clone") || svar.startsWith("DB$ Animate"))) {
                tags.add(Tag.TEMPORARY);
            }
            if (svar.contains("AddPhase") && svar.contains("ExtraPhase$ Combat")) {
                tags.add(Tag.EXTRA_COMBAT);
            }
        }
        return tags;
    }

    private static void inspect(SpellAbility part, EnumSet<Tag> tags) {
        ApiType api = part.getApi();
        if (api == null) {
            return;
        }
        if ((api == ApiType.Clone || api == ApiType.Animate) && "UntilEndOfTurn".equals(part.getParam("Duration"))) {
            tags.add(Tag.TEMPORARY);
        }
        if (api == ApiType.Charm) {
            tags.add(Tag.MODAL);
        }
        if (api == ApiType.AddPhase && part.getParamOrDefault("ExtraPhase", "").contains("Combat")) {
            tags.add(Tag.EXTRA_COMBAT);
        }
        if (JUDGMENT_OPTIONAL.contains(api)
                && (part.hasParam("ChoiceOptional") || (part instanceof AbilitySub && part.hasParam("Optional")))) {
            tags.add(Tag.OPTIONAL_CHOICE);
        }
    }
}
