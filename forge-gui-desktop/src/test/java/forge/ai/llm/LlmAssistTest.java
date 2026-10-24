package forge.ai.llm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class LlmAssistTest extends AITest {
    static {
        try {
            File log = File.createTempFile("llm-assist-test", ".jsonl");
            log.deleteOnExit();
            System.setProperty("forge.llm.log", log.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Records every call and answers with a fixed option, or fails if answer < 0. */
    private static final class FakeProvider implements LlmProvider {
        final List<String> questions = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        int answer;
        double confidence = 0.9;
        double[] probabilities;
        /** when set, picks the answer from the question instead of {@link #answer} */
        java.util.function.ToIntFunction<String> chooser;

        FakeProvider(int answer) {
            this.answer = answer;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public LlmAnswer choose(String state, String question, List<String> options, long timeoutMs) throws LlmException {
            questions.add(question);
            states.add(state);
            if (answer < 0) {
                throw new LlmException("simulated timeout");
            }
            int pick = chooser == null ? answer : chooser.applyAsInt(question);
            return new LlmAnswer(Math.min(pick, options.size() - 1), confidence, "test", 100, 5, probabilities);
        }
    }

    private Player opponentA;
    private Player ai;
    private Player opponentB;

    /** 3 player free-for-all, AI on the LlmAssisted profile, in round 5. */
    private Game setUpGame(String profile, int turn, FakeProvider provider) {
        Game game = initAndCreateThreePlayerGame();
        opponentA = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        opponentB = game.getPlayers().get(2);
        opponentA.setTeam(0);
        ai.setTeam(1);
        opponentB.setTeam(2);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile(profile);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, turn);
        LlmGate.setProviderOverride(provider);
        return game;
    }

    @AfterMethod
    public void clearProvider() {
        LlmGate.setProviderOverride(null);
    }

    @Test
    public void choosesOpponentAndReusesAnswerForTheTurn() {
        FakeProvider provider = new FakeProvider(1);
        Game game = setUpGame("LlmAssisted", 13, provider);
        List<Player> tied = List.of(opponentA, opponentB);

        AssertJUnit.assertEquals(opponentB, LlmAssist.chooseOpponent(ai, tied));
        AssertJUnit.assertEquals(opponentB, LlmAssist.chooseOpponent(ai, tied));
        AssertJUnit.assertEquals("second ask in the same turn should come from the cache", 1, provider.questions.size());
        AssertJUnit.assertTrue(game.getGameLog().getLogEntries(null).stream()
                .anyMatch(e -> e.message().startsWith("[AI assist]")));
    }

    @Test
    public void noCallsBeforeMinimumRound() {
        FakeProvider provider = new FakeProvider(1);
        setUpGame("LlmAssisted", 2, provider);
        AssertJUnit.assertNull(LlmAssist.chooseOpponent(ai, List.of(opponentA, opponentB)));
        AssertJUnit.assertTrue(provider.questions.isEmpty());
    }

    @Test
    public void noCallsWhenProfileDisabled() {
        FakeProvider provider = new FakeProvider(1);
        setUpGame("Default", 13, provider);
        AssertJUnit.assertNull(LlmAssist.chooseOpponent(ai, List.of(opponentA, opponentB)));
        AssertJUnit.assertTrue(provider.questions.isEmpty());
    }

    @Test
    public void noCallsFromTimedEvaluationThread() throws Exception {
        FakeProvider provider = new FakeProvider(1);
        setUpGame("LlmAssisted", 13, provider);
        AtomicReference<Player> result = new AtomicReference<>(opponentA);
        Thread t = new Thread(() -> result.set(LlmAssist.chooseOpponent(ai, List.of(opponentA, opponentB))), "Game AI Eval");
        t.start();
        t.join();
        AssertJUnit.assertNull(result.get());
        AssertJUnit.assertTrue(provider.questions.isEmpty());
    }

    @Test
    public void failureFallsBackToStandardAi() {
        FakeProvider provider = new FakeProvider(-1);
        setUpGame("LlmAssisted", 13, provider);
        AssertJUnit.assertNull(LlmAssist.chooseOpponent(ai, List.of(opponentA, opponentB)));
        AssertJUnit.assertEquals(1, provider.questions.size());
    }

    @Test
    public void perTurnBudgetIsEnforced() {
        FakeProvider provider = new FakeProvider(0);
        setUpGame("LlmAssisted", 13, provider);
        List<String> options = List.of("a", "b");
        for (int i = 0; i < 14; i++) {
            LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q" + i, options, List.of(), -1, "key" + i);
        }
        AssertJUnit.assertEquals(10, provider.questions.size()); // LLM_MAX_CALLS_TURN=10 in LlmAssisted.ai
    }

    @Test
    public void stateHidesOpponentsHands() {
        FakeProvider provider = new FakeProvider(0);
        setUpGame("LlmAssisted", 13, provider);
        addCardToZone("Lightning Bolt", opponentA, ZoneType.Hand);
        addCardToZone("Counterspell", ai, ZoneType.Hand);
        Card bear = addCard("Grizzly Bears", opponentB);

        String state = StateSerializer.describe(ai, List.of(bear));
        AssertJUnit.assertFalse("opponent's hand must stay hidden", state.contains("Lightning Bolt"));
        AssertJUnit.assertTrue(state.contains("Your hand: Counterspell"));
        AssertJUnit.assertTrue(state.contains("Grizzly Bears 2/2"));
        AssertJUnit.assertTrue("focus cards include oracle text", state.contains("Card text for this decision"));
    }

    @Test
    public void lowConfidenceKeepsStandardAiPick() {
        FakeProvider provider = new FakeProvider(1);
        provider.confidence = 0.2; // below LLM_MIN_CONFIDENCE_PCT=35
        setUpGame("LlmAssisted", 13, provider);
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q", List.of("a", "b"), List.of(), 0, "low");
        AssertJUnit.assertEquals("unsure model must not overrule the standard AI", 0, pick);

        provider.confidence = 0.8;
        pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q", List.of("a", "b"), List.of(), 0, "high");
        AssertJUnit.assertEquals(1, pick);

        provider.confidence = 0.1;
        pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q", List.of("a", "b"), List.of(), -1, "nohint");
        AssertJUnit.assertEquals("no standard pick to fall back to", 1, pick);
    }

    @Test
    public void plannerUsesCachedPlanFileWithoutCallingModel() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("llm-plans").toFile();
        System.setProperty("forge.llm.plans", dir.getAbsolutePath());
        try {
            Deck deck = new Deck("Test Dragons");
            for (String name : List.of("Sol Ring", "Shivan Dragon", "Mountain")) {
                PaperCard c = FModel.getMagicDb().getCommonCards().getCard(name);
                deck.getOrCreate(DeckSection.Main).add(c);
            }
            String hash = GamePlanner.hash(deck);
            File file = new File(dir, "Test Dragons-" + hash + ".json");
            java.nio.file.Files.writeString(file.toPath(), "{\"archetype\":\"Dragons\",\"summary\":\"Ramp then dragons.\","
                    + "\"holdCards\":[{\"card\":\"Shivan Dragon\",\"castWhen\":\"an opponent is tapped out\"}],\"contextCards\":[]}");

            // a model call would fail: no DEEPSEEK key is needed when the cached file exists
            GamePlan plan = GamePlanner.load(deck, "Test Dragons", hash, "unused", 1000);
            AssertJUnit.assertNotNull(plan);
            AssertJUnit.assertEquals("an opponent is tapped out", plan.holdCondition("Shivan Dragon"));
            AssertJUnit.assertTrue(GamePlanner.describeDeck(deck, "Test Dragons").contains("Shivan Dragon |"));
        } finally {
            System.clearProperty("forge.llm.plans");
        }
    }

    @Test
    public void probabilityMarginDecidesCloseCalls() {
        FakeProvider provider = new FakeProvider(1);
        setUpGame("LlmAssisted", 13, provider);
        // LLM_MIN_MARGIN_PCT=10: option 2 at 0.45 vs standard pick at 0.40 is not clearly better
        provider.probabilities = new double[] {0.40, 0.45, 0.15};
        provider.confidence = 0.9;
        int pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q", List.of("a", "b", "c"), List.of(), 0, "m1");
        AssertJUnit.assertEquals(0, pick);
        // 0.30 vs 0.20 with six options: a clear preference even at low absolute probability
        provider.probabilities = new double[] {0.20, 0.30, 0.15, 0.15, 0.10, 0.10};
        provider.confidence = 0.05;
        pick = LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q", List.of("a", "b", "c", "d", "e", "f"), List.of(), 0, "m2");
        AssertJUnit.assertEquals(1, pick);
    }

    @Test
    public void routedQuestionsIgnoreMarginAndPerTurnLimit() {
        FakeProvider provider = new FakeProvider(1);
        provider.probabilities = new double[] {0.49, 0.51};
        setUpGame("LlmAssisted", 2, provider); // before LLM_MIN_ROUND too
        for (int i = 0; i < 12; i++) {
            int pick = LlmGate.askRouted(ai, "test", "testing", "q", List.of("a", "b"), List.of(), 0, "r" + i);
            AssertJUnit.assertEquals("routed answers stand", 1, pick);
        }
        AssertJUnit.assertEquals(12, provider.questions.size());
    }

    @Test
    public void classifiesCardsTheStandardAiMisplays() {
        setUpGame("LlmAssisted", 13, new FakeProvider(0));
        java.util.Set<RoutedCards.Tag> mirror = RoutedCards.classify(addCard("Cursed Mirror", ai));
        AssertJUnit.assertTrue(mirror.toString(), mirror.contains(RoutedCards.Tag.TEMPORARY));
        AssertJUnit.assertTrue(mirror.toString(), mirror.contains(RoutedCards.Tag.OPTIONAL_CHOICE));
        AssertJUnit.assertTrue(RoutedCards.classify(addCard("Ghoulcaller Gisa", ai)).contains(RoutedCards.Tag.UNSUPPORTED));
        AssertJUnit.assertTrue(RoutedCards.classify(addCardToZone("Exsanguinate", ai, ZoneType.Hand)).contains(RoutedCards.Tag.X_COST));
        AssertJUnit.assertTrue(RoutedCards.classify(addCard("Hellkite Charger", ai)).contains(RoutedCards.Tag.EXTRA_COMBAT));
        AssertJUnit.assertTrue("plain mana rock", RoutedCards.classify(addCard("Sol Ring", ai)).isEmpty());
        AssertJUnit.assertTrue("plain land", RoutedCards.classify(addCard("Mountain", ai)).isEmpty());
        // cascade's "you may cast it" is a keyword rule, not a judgement call on the card
        AssertJUnit.assertTrue("cascade", RoutedCards.classify(addCardToZone("Bituminous Blast", ai, ZoneType.Hand)).isEmpty());
        AssertJUnit.assertTrue(LlmAssist.isRouted(ai, addCard("Cursed Mirror", ai).getFirstSpellAbility()));
    }

    /**
     * Only timing signals take a card's timing from the standard AI; modes and optional choices are made by the
     * model as the card resolves. Activated abilities are routed one by one.
     */
    @Test
    public void routesTimingOnlyForTimingSignals() {
        setUpGame("LlmAssisted", 13, new FakeProvider(0));

        Card channeler = addCardToZone("Aether Channeler", ai, ZoneType.Hand);
        AssertJUnit.assertTrue("modal ETB: the model picks the mode", RoutedCards.routesChoices(ai, channeler));
        AssertJUnit.assertFalse("modal ETB: the standard AI still casts it", LlmAssist.isRouted(ai, channeler.getFirstSpellAbility()));

        // "you may cast them without paying their mana cost" is nearly always right; no routing
        AssertJUnit.assertTrue(RoutedCards.classify(addCardToZone("Collected Conjuring", ai, ZoneType.Hand)).isEmpty());

        Card oko = addCard("Oko, the Trickster", ai);
        SpellAbility counters = null, copy = null;
        for (SpellAbility sa : oko.getSpellAbilities()) {
            if (sa.getApi() == ApiType.PutCounter) {
                counters = sa;
            } else if (sa.getApi() == ApiType.Clone) {
                copy = sa;
            }
        }
        AssertJUnit.assertTrue("until-end-of-turn copy ability", LlmAssist.isRouted(ai, copy));
        AssertJUnit.assertFalse("plain +1 stays with the standard AI", LlmAssist.isRouted(ai, counters));
    }

    /**
     * A flagged planeswalker's loyalty abilities are one question, and the model can pick any of them. In a test
     * match the game plan flagged Freyalise; holding her -2 dropped all her abilities, so her +2 was never used.
     */
    @Test
    public void modelPicksAmongLoyaltyAbilities() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("llm-plans").toFile();
        System.setProperty("forge.llm.plans", dir.getAbsolutePath());
        try {
            FakeProvider provider = new FakeProvider(0);
            provider.chooser = q -> q.startsWith("Which loyalty ability") ? 1 : 0; // the -2
            Game game = setUpGame("DSeekPlan+JevAI", 13, provider);
            Deck deck = ai.getRegisteredPlayer().getDeck();
            deck.getOrCreate(DeckSection.Main).add(FModel.getMagicDb().getCommonCards().getCard("Freyalise, Llanowar's Fury"));
            java.nio.file.Files.writeString(new File(dir, "deck-" + GamePlanner.hash(deck) + ".json").toPath(),
                    "{\"archetype\":\"Elves\",\"summary\":\"Go wide.\",\"contextCards\":[{\"card\":\"Freyalise, Llanowar's Fury\","
                            + "\"risk\":\"modes\",\"guidance\":\"Use +2 unless a must-kill artifact justifies -2.\"}]}");
            AssertJUnit.assertNotNull(GamePlanner.await(ai));

            Card freyalise = addCard("Freyalise, Llanowar's Fury", ai);
            freyalise.setCounters(CounterEnumType.LOYALTY, 3);
            Card ring = addCard("Sol Ring", opponentA);

            playUntilPhase(game, PhaseType.END_OF_TURN);

            AssertJUnit.assertEquals("one question for the walker: " + provider.questions, 1,
                    provider.questions.stream().filter(q -> q.startsWith("Which loyalty ability")).count());
            AssertJUnit.assertEquals("the -2 was used", 1, freyalise.getCounters(CounterEnumType.LOYALTY));
            AssertJUnit.assertTrue("on the Sol Ring", ring.isInZone(ZoneType.Graveyard));
        } finally {
            System.clearProperty("forge.llm.plans");
        }
    }

    /**
     * A planeswalker the standard AI can't play (AI:RemoveDeck:All) has its loyalty abilities offered to the model.
     * In a test match Daretti, Scrap Savant was cast five times and never activated.
     */
    @Test
    public void unsupportedPlaneswalkerAbilitiesGoToTheModel() {
        FakeProvider provider = new FakeProvider(0); // use whatever is offered
        Game game = setUpGame("LlmAssisted", 13, provider);
        Card daretti = addCard("Daretti, Scrap Savant", ai);
        daretti.setCounters(CounterEnumType.LOYALTY, 3);
        addCardToZone("Mountain", ai, ZoneType.Hand);
        addCardToZone("Island", ai, ZoneType.Hand);
        playUntilPhase(game, PhaseType.END_OF_TURN);

        AssertJUnit.assertTrue("Daretti's abilities went to the model: " + provider.questions,
                provider.questions.stream().anyMatch(q -> q.contains("Daretti")));
        AssertJUnit.assertEquals(5, daretti.getCounters(CounterEnumType.LOYALTY));
    }

    /**
     * A routed spell with several modes gets them from the model, one at a time. In a test match a routed Fiery
     * Confluence (three modes, repeats allowed) was cast with no modes: the standard AI found no set it liked.
     */
    @Test
    public void modelPicksSeveralModes() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("llm-plans").toFile();
        System.setProperty("forge.llm.plans", dir.getAbsolutePath());
        try {
            FakeProvider provider = new FakeProvider(1); // cast it after combat; then "2 damage to each opponent" each time
            Game game = setUpGame("DSeekPlan+JevAI", 13, provider);
            Deck deck = ai.getRegisteredPlayer().getDeck();
            deck.getOrCreate(DeckSection.Main).add(FModel.getMagicDb().getCommonCards().getCard("Fiery Confluence"));
            java.nio.file.Files.writeString(new File(dir, "deck-" + GamePlanner.hash(deck) + ".json").toPath(),
                    "{\"archetype\":\"Burn\",\"summary\":\"Burn.\",\"contextCards\":[{\"card\":\"Fiery Confluence\","
                            + "\"risk\":\"modes\",\"guidance\":\"Pick modes for the board.\"}]}");
            AssertJUnit.assertNotNull(GamePlanner.await(ai));

            addCardToZone("Fiery Confluence", ai, ZoneType.Hand);
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", ai);
            }
            int lifeA = opponentA.getLife(), lifeB = opponentB.getLife();

            playUntilPhase(game, PhaseType.END_OF_TURN);

            AssertJUnit.assertTrue("modes went to the model: " + provider.questions,
                    provider.questions.stream().anyMatch(q -> q.startsWith("Choose mode 3 of 3 for Fiery Confluence")));
            AssertJUnit.assertEquals(lifeA - 6, opponentA.getLife());
            AssertJUnit.assertEquals(lifeB - 6, opponentB.getLife());
        } finally {
            System.clearProperty("forge.llm.plans");
        }
    }

    /** The model picks which free permanent Genesis Hydra puts onto the battlefield, not whether to take one. */
    @Test
    public void noChooseNothingUnlessTheChoiceIsTheJudgmentCall() {
        setUpGame("LlmAssisted", 13, new FakeProvider(99)); // always the last option
        List<Card> options = List.of(addCardToZone("Perennial Behemoth", ai, ZoneType.Library),
                addCardToZone("Sol Ring", ai, ZoneType.Library));

        SpellAbility hydra = addCardToZone("Genesis Hydra", ai, ZoneType.Hand).getFirstSpellAbility();
        AssertJUnit.assertNotNull(LlmAssist.routedChoice(ai, hydra, options, options.get(0), true));

        // Cursed Mirror's copy is optional by design: there "nothing" stays on offer
        SpellAbility mirror = addCard("Cursed Mirror", ai).getFirstSpellAbility();
        AssertJUnit.assertNull(LlmAssist.routedChoice(ai, mirror, options, options.get(0), true));
    }

    /** Blight Grenade's "all creatures get -3/-3" killed its caster's board in a test match; it is a wipe. */
    @Test
    public void wipesIncludeShrinkAllRiders() {
        setUpGame("LlmAssisted", 13, new FakeProvider(0));
        AssertJUnit.assertTrue(LlmAssist.isWipe(addCardToZone("Blight Grenade", ai, ZoneType.Hand).getFirstSpellAbility()));
        AssertJUnit.assertTrue(LlmAssist.isWipe(addCardToZone("Infest", ai, ZoneType.Hand).getFirstSpellAbility()));
        AssertJUnit.assertFalse("one-sided", LlmAssist.isWipe(addCardToZone("Cower in Fear", ai, ZoneType.Hand).getFirstSpellAbility()));
        AssertJUnit.assertFalse(LlmAssist.isWipe(addCardToZone("Doom Blade", ai, ZoneType.Hand).getFirstSpellAbility()));
        AssertJUnit.assertFalse("exiles only the chosen permanents",
                LlmAssist.isWipe(addCardToZone("Disorienting Choice", ai, ZoneType.Hand).getFirstSpellAbility()));
    }

    /**
     * Each player chooses for an opponent's Eye of Doom. The question must say whose card it is and what the choice
     * does, without the controller's routing notes; in a test match a player put a doom counter on its own commander.
     */
    @Test
    public void choiceForSomeoneElsesCardSaysWhose() {
        FakeProvider provider = new FakeProvider(0);
        setUpGame("LlmAssisted", 13, provider);
        Card eye = addCard("Eye of Doom", opponentA);
        SpellAbility trigger = eye.getTriggers().get(0).ensureAbility();
        List<Card> options = List.of(addCard("Grizzly Bears", ai), addCard("Sol Ring", opponentA));

        LlmAssist.routedChoice(ai, trigger, options, options.get(1), false);
        String q = provider.questions.get(0);
        AssertJUnit.assertTrue(q, q.startsWith(opponentA.getName() + "'s Eye of Doom"));
        AssertJUnit.assertTrue(q, q.contains("doom counter"));
        AssertJUnit.assertFalse("routing notes are about the controller: " + q, q.contains("can't play"));
    }

    /** The model turned down Jarad's return-to-hand ability 37 times in one game; repeated declines back off. */
    @Test
    public void repeatedDeclinesBackOff() {
        FakeProvider provider = new FakeProvider(1); // always "hold it"
        Game game = setUpGame("LlmAssisted", 13, provider);
        Card gisa = addCard("Ghoulcaller Gisa", ai);
        addCard("Grizzly Bears", ai);
        SpellAbility ability = gisa.getSpellAbilities().stream().filter(sa -> !sa.isSpell()).findFirst().orElseThrow();
        ability.setActivatingPlayer(ai);
        AssertJUnit.assertTrue("instant-speed routed ability", LlmAssist.isRouted(ai, ability));

        int[] asked = new int[7];
        for (int turn = 13; turn <= 19; turn++) {
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, turn);
            int before = provider.questions.size();
            AssertJUnit.assertFalse(LlmAssist.confirmPlay(ai, ability));
            asked[turn - 13] = provider.questions.size() - before;
        }
        // declined on turns 13 and 14, then one round (3 players) off, asked on 17, then two rounds off
        AssertJUnit.assertEquals("[1, 1, 0, 0, 1, 0, 0]", java.util.Arrays.toString(asked));
    }

    @Test
    public void mulliganRules() {
        // size, finalSize, lands, colours, ramp, early plays, off-colour, free, commander mv
        AssertJUnit.assertEquals("2 lands, no ramp, free", 0, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 2, 1, 0, 3, 0, true, 5)));
        AssertJUnit.assertEquals("2 lands, no ramp, not free: keep", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 6, 2, 1, 0, 3, 0, false, 5)));
        AssertJUnit.assertEquals("2 lands + ramp: keep", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 2, 2, 1, 3, 0, true, 5)));
        AssertJUnit.assertEquals("1 land", 0, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 1, 1, 1, 2, 0, false, 5)));
        AssertJUnit.assertEquals("3 lands with plays", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 3, 2, 0, 2, 0, true, 5)));
        AssertJUnit.assertEquals("4 lands, nothing castable: keep", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 4, 1, 0, 0, 3, true, 5)));
        AssertJUnit.assertEquals("6 lands, free", 0, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 7, 6, 2, 0, 1, 0, true, 5)));
        AssertJUnit.assertEquals("down to 4: keep", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 4, 1, 1, 0, 0, 0, false, 5)));
        AssertJUnit.assertEquals("no lands at 5: mulligan", 0, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 5, 0, 0, 0, 0, 0, false, 5)));
        AssertJUnit.assertEquals("6 lands, not free: keep", 1, LlmAssist.mulliganRule(new LlmAssist.HandFacts(7, 6, 6, 2, 0, 1, 0, false, 5)));
    }
}
