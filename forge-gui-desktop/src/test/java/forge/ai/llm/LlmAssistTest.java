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
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
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
            return new LlmAnswer(Math.min(answer, options.size() - 1), confidence, "test", 100, 5, probabilities);
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
        AssertJUnit.assertTrue(LlmAssist.isRouted(ai, addCard("Cursed Mirror", ai).getFirstSpellAbility()));
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
