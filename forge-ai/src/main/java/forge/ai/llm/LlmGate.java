package forge.ai.llm;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Budget and policy for model calls. Every hook goes through {@link #ask} or {@link #askRouted}, which
 * return -1 whenever the standard AI should decide instead: disabled, no API key, too early in the game,
 * budget spent, on the timed AI evaluation thread, or the call failed.
 * <p>
 * Two kinds of call:
 * <ul>
 * <li>close-call hooks ({@link #ask}): the model may overrule the standard AI's own pick only when it
 * clearly prefers its answer; limited per turn and from LLM_MIN_ROUND.</li>
 * <li>routed cards ({@link #askRouted}): cards the standard AI can't be trusted with. The model's answer
 * stands, from turn 1, with no per-turn limit (a generous per-game safety cap only).</li>
 * </ul>
 */
public final class LlmGate {
    /** Name of the worker thread AiController runs its timed spell evaluation on. */
    private static final String EVAL_THREAD = "Game AI Eval";

    private static final Map<Game, Budget> budgets = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, LlmProvider> providers = new HashMap<>();
    private static volatile LlmProvider providerOverride;

    private static final class Budget {
        /** per AI player, for the whole game */
        final Map<String, Integer> calls = new HashMap<>();
        final Map<String, Integer> routedCalls = new HashMap<>();
        int turn = -1;
        final Map<String, Integer> turnCalls = new HashMap<>();
        final Map<String, Integer> cache = new HashMap<>();
        /** survives turn changes */
        final Map<String, Integer> gameMemory = new HashMap<>();
    }

    private LlmGate() {
    }

    /** For tests: use this provider for every player instead of the configured one. */
    public static void setProviderOverride(LlmProvider provider) {
        providerOverride = provider;
    }

    public static boolean isEvalThread() {
        return EVAL_THREAD.equals(Thread.currentThread().getName());
    }

    public static boolean hookEnabled(Player ai, AiProps hook) {
        return ai.getController().isAI()
                && AiProfileUtil.getBoolProperty(ai, AiProps.LLM_ENABLED)
                && AiProfileUtil.getBoolProperty(ai, hook)
                && provider(ai) != null;
    }

    /** Cheap pre-check hooks use before doing any work to build options. */
    public static boolean mayAsk(Player ai, AiProps hook) {
        if (isEvalThread() || !hookEnabled(ai, hook)) {
            return false;
        }
        Game game = ai.getGame();
        Budget b = budget(game);
        if (hook == AiProps.LLM_HOOK_ROUTED) {
            synchronized (b) {
                return b.routedCalls.getOrDefault(ai.getName(), 0) < AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_ROUTED_CALLS_GAME);
            }
        }
        // mulligans and holding back payoffs matter from the very start, the rest only from LLM_MIN_ROUND
        if (hook != AiProps.LLM_HOOK_MULLIGAN && hook != AiProps.LLM_HOOK_HOLD
                && StateSerializer.round(game) < AiProfileUtil.getIntProperty(ai, AiProps.LLM_MIN_ROUND)) {
            return false;
        }
        synchronized (b) {
            rollTurn(b, game);
            return b.calls.getOrDefault(ai.getName(), 0) < AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_CALLS_GAME)
                    && b.turnCalls.getOrDefault(ai.getName(), 0) < AiProfileUtil.getIntProperty(ai, AiProps.LLM_MAX_CALLS_TURN);
        }
    }

    /** A cached answer for this turn, usable from any thread; -1 if none. */
    public static int cached(Player ai, String cacheKey) {
        Budget b = budget(ai.getGame());
        synchronized (b) {
            rollTurn(b, ai.getGame());
            return b.cache.getOrDefault(ai.getName() + "|" + cacheKey, -1);
        }
    }

    /** Record an answer the hook derived itself, so the same question isn't asked again this turn. */
    public static void remember(Player ai, String cacheKey, int value) {
        Budget b = budget(ai.getGame());
        synchronized (b) {
            rollTurn(b, ai.getGame());
            b.cache.put(ai.getName() + "|" + cacheKey, value);
        }
    }

    /** Per-game memory that survives turn changes (e.g. how many mulligan decisions so far). */
    public static int gameMemory(Player ai, String key) {
        Budget b = budget(ai.getGame());
        synchronized (b) {
            return b.gameMemory.getOrDefault(ai.getName() + "|" + key, 0);
        }
    }

    public static void setGameMemory(Player ai, String key, int value) {
        Budget b = budget(ai.getGame());
        synchronized (b) {
            b.gameMemory.put(ai.getName() + "|" + key, value);
        }
    }

    /**
     * Close-call question: the model may overrule the standard AI's pick ({@code forgeIndex}) only if it
     * clearly prefers its own answer (see {@link #overrule}).
     *
     * @param hook        short hook name for the log
     * @param activity    what the AI is doing, for the on-screen indicator ("choosing a target for X")
     * @param focusCards  cards whose oracle text the model should see
     * @param forgeIndex  what the standard AI would pick (shown to the model as a hint, -1 if none)
     * @param cacheKey    answers are reused for the rest of the turn under this key
     * @return the chosen index, or -1 to fall back to the standard AI
     */
    public static int ask(Player ai, AiProps hookProp, String hook, String activity, String question,
                          List<String> options, Collection<Card> focusCards, int forgeIndex, String cacheKey) {
        return askInternal(ai, hookProp, hook, activity, question, options, focusCards, forgeIndex, cacheKey, false);
    }

    /** Routed-card question: the model's answer stands (no margin check), no per-turn limit. */
    public static int askRouted(Player ai, String hook, String activity, String question,
                                List<String> options, Collection<Card> focusCards, int forgeIndex, String cacheKey) {
        return askInternal(ai, AiProps.LLM_HOOK_ROUTED, hook, activity, question, options, focusCards, forgeIndex, cacheKey, true);
    }

    private static int askInternal(Player ai, AiProps hookProp, String hook, String activity, String question,
                                   List<String> options, Collection<Card> focusCards, int forgeIndex, String cacheKey,
                                   boolean authoritative) {
        int hit = cached(ai, cacheKey);
        if (hit >= 0 && hit < options.size()) {
            return hit;
        }
        if (options.size() < 2 || !mayAsk(ai, hookProp)) {
            return -1;
        }
        Game game = ai.getGame();
        Budget b = budget(game);
        synchronized (b) {
            if (authoritative) {
                b.routedCalls.merge(ai.getName(), 1, Integer::sum);
            } else {
                b.calls.merge(ai.getName(), 1, Integer::sum);
                b.turnCalls.merge(ai.getName(), 1, Integer::sum);
            }
        }

        String fullQuestion = forgeIndex >= 0
                ? question + "\n(The standard AI would pick option " + (forgeIndex + 1) + ".)"
                : question;
        String state = StateSerializer.describe(ai, focusCards);
        LlmProvider provider = provider(ai);
        long timeout = AiProfileUtil.getIntProperty(ai, AiProps.LLM_TIMEOUT_MS);
        String who = ai.getName();

        LlmStatus.fireStarted(who, who + "'s AI is " + activity);
        long start = System.currentTimeMillis();
        LlmAnswer answer = null;
        String error = null;
        try {
            answer = provider.choose(state, fullQuestion, options, timeout);
        } catch (LlmException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = e.toString();
        }
        long elapsed = System.currentTimeMillis() - start;

        LlmLog.write(game.getId(), game.getPhaseHandler().getTurn(), who, hook, provider.name(), question,
                options, forgeIndex, answer, error, elapsed);

        if (answer == null) {
            LlmStatus.fireFinished(who, "AI assist unavailable (" + error + "), using standard AI", elapsed);
            return -1;
        }
        int chosen = answer.index();
        boolean overruled = !authoritative && forgeIndex >= 0 && chosen != forgeIndex && !overrule(ai, answer, forgeIndex);
        if (overruled) {
            chosen = forgeIndex;
        }
        synchronized (b) {
            b.cache.put(who + "|" + cacheKey, chosen);
        }
        String picked = options.get(chosen);
        String note = overruled
                ? " (model leaned to " + options.get(answer.index()) + ", not clearly enough; keeping standard AI choice)"
                : (answer.reason().isEmpty() ? "" : " (" + answer.reason() + ")");
        LlmStatus.fireFinished(who, "chose " + picked, elapsed);
        // STACK_ADD rather than INFORMATION so it shows at the default (medium) log verbosity
        game.getGameLog().add(GameLogEntryType.STACK_ADD, "[AI assist] " + who + " " + activity + ": " + picked + note);
        return chosen;
    }

    /**
     * Should the model's pick replace the standard AI's? With per-option probabilities (Jev), the model's
     * pick must beat the standard pick by LLM_MIN_MARGIN_PCT points; that works the same for two options
     * or six. Without them (DeepSeek), fall back to the model's self-reported confidence.
     */
    static boolean overrule(Player ai, LlmAnswer answer, int forgeIndex) {
        double[] p = answer.probabilities();
        if (p != null && forgeIndex < p.length && answer.index() < p.length) {
            double margin = AiProfileUtil.getIntProperty(ai, AiProps.LLM_MIN_MARGIN_PCT) / 100.0;
            return p[answer.index()] - p[forgeIndex] >= margin - 1e-9;
        }
        return answer.confidence() >= AiProfileUtil.getIntProperty(ai, AiProps.LLM_MIN_CONFIDENCE_PCT) / 100.0;
    }

    private static Budget budget(Game game) {
        return budgets.computeIfAbsent(game, g -> new Budget());
    }

    private static void rollTurn(Budget b, Game game) {
        int turn = game.getPhaseHandler().getTurn();
        if (b.turn != turn) {
            b.turn = turn;
            b.turnCalls.clear();
            b.cache.clear();
        }
    }

    static LlmProvider provider(Player ai) {
        if (providerOverride != null) {
            return providerOverride;
        }
        String kind = AiProfileUtil.getProperty(ai, AiProps.LLM_PROVIDER).trim().toLowerCase();
        String model = AiProfileUtil.getProperty(ai, AiProps.LLM_MODEL).trim();
        String key = kind + ":" + model;
        synchronized (providers) {
            if (providers.containsKey(key)) {
                return providers.get(key);
            }
            LlmProvider p = null;
            if ("deepseek".equals(kind)) {
                String apiKey = System.getenv("DEEPSEEK_API_KEY");
                if (apiKey != null && !apiKey.isBlank()) {
                    String base = System.getenv("DEEPSEEK_BASE_URL");
                    p = new DeepSeekProvider(base == null || base.isBlank() ? DeepSeekProvider.DEFAULT_BASE_URL : base, apiKey.trim(), model);
                } else {
                    System.err.println("LLM assist: DEEPSEEK_API_KEY not set, using the standard AI only");
                }
            } else if ("jev".equals(kind)) {
                String apiKey = System.getenv("TYPESAFE_API_KEY");
                if (apiKey != null && !apiKey.isBlank()) {
                    String base = System.getenv("TYPESAFE_BASE_URL");
                    p = new JevProvider(base == null || base.isBlank() ? JevProvider.DEFAULT_BASE_URL : base, apiKey.trim(), model);
                } else {
                    System.err.println("LLM assist: TYPESAFE_API_KEY not set, using the standard AI only");
                }
            } else {
                System.err.println("LLM assist: unknown LLM_PROVIDER " + kind);
            }
            providers.put(key, p);
            return p;
        }
    }
}
