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
            return new LlmAnswer(Math.min(answer, options.size() - 1), 0.9, "test", 100, 5);
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
        for (int i = 0; i < 6; i++) {
            LlmGate.ask(ai, AiProps.LLM_HOOK_TARGETS, "test", "testing", "q" + i, options, List.of(), -1, "key" + i);
        }
        AssertJUnit.assertEquals(3, provider.questions.size()); // LLM_MAX_CALLS_TURN=3 in LlmAssisted.ai
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
}
