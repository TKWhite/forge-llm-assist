package forge.ai.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.player.Player;
import forge.item.PaperCard;

/**
 * Works out a deck's game plan with the planner model (DeepSeek), once per deck. Plans are cached as
 * readable JSON in %APPDATA%/Forge/llm-plans (or the forge.llm.plans system property) so later games
 * with the same deck cost nothing, and a plan can be hand-edited. Generation runs on a background
 * thread; callers never block the game for long and carry on without a plan if it isn't ready.
 */
public final class GamePlanner {
    private static final String SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering Commander deck pilot. Given a decklist, work out the deck's "
            + "game plan for a 4-player game. Reply ONLY with a JSON object with these keys: "
            + "\"archetype\" (short label), "
            + "\"summary\" (2-3 sentences: how the deck wins and how it should play early, mid and late game), "
            + "\"winConditions\" (array of {\"cards\": [card names], \"how\": short description}, most important first, at most 4), "
            + "\"keyCards\" (array of up to 8 card names: engines and payoffs worth tutoring for), "
            + "\"holdCards\" (array of {\"card\": name, \"castWhen\": short condition} for up to 8 payoffs, finishers, "
            + "board wipes or one-shot effects that should NOT be cast as soon as they are affordable; do not list "
            + "ramp, card draw or cheap creatures that should simply be cast on curve; castWhen is a board state that "
            + "can come about on a later turn, e.g. \"an opponent has 3+ creatures\"; a card that is only better later in "
            + "the same turn, e.g. after combat, is not a hold card, give it a contextCards entry instead; do not hold the "
            + "commander unless casting it early is actively harmful, since its ability only works on the battlefield), "
            + "\"rampPriority\" (array of up to 6 card names to cast early), "
            + "\"mulligan\" ({\"minLands\": n, \"maxLands\": n, \"wantAny\": [card names that make a hand good], "
            + "\"keepNotes\": short guidance}), "
            + "\"watchFor\" (one sentence on what opposing threats matter most to this deck), "
            + "\"contextCards\" (array of up to 15 {\"card\": name, \"risk\": \"dual-use\"|\"timing\"|\"modes\"|\"targets\", "
            + "\"guidance\": one sentence} for cards a simple rules-based AI would misplay because the right use depends on "
            + "context: cards with two different uses (e.g. a mana rock that can also become a hasted copy of a creature, "
            + "which only helps when cast before combat with a worthwhile creature to copy), effects that only last until "
            + "end of turn, extra combats, modal spells, X spells, and cards whose value depends on what the opponents have). "
            + "Use exact card names from the decklist. Read each card's rules text exactly: an X in an ability may be a "
            + "count (e.g. the mana spent to cast it) rather than a cost you choose. Only list win conditions this "
            + "decklist can realistically assemble.";

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LLM planner");
        t.setDaemon(true);
        return t;
    });

    /** keyed by deck content hash, shared across the games of a match */
    private static final Map<String, CompletableFuture<GamePlan>> plans = new HashMap<>();
    private static final Map<Deck, String> deckHashes = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Game, Set<String>> announced = Collections.synchronizedMap(new WeakHashMap<>());

    private GamePlanner() {
    }

    public static boolean enabledFor(Player ai) {
        return ai.getController().isAI() && AiProfileUtil.getBoolProperty(ai, AiProps.LLM_ENABLED)
                && AiProfileUtil.getBoolProperty(ai, AiProps.LLM_PLANNER_ENABLED);
    }

    /** Start (or reuse) the plan for this player's deck without waiting for it. */
    public static void start(Player ai) {
        if (!enabledFor(ai) || ai.getRegisteredPlayer() == null || ai.getRegisteredPlayer().getDeck() == null) {
            return;
        }
        future(ai);
    }

    /** The plan if it is ready, else null. Never blocks. */
    public static GamePlan current(Player ai) {
        if (!enabledFor(ai) || ai.getRegisteredPlayer() == null) {
            return null;
        }
        CompletableFuture<GamePlan> f = future(ai);
        GamePlan plan = f == null ? null : f.getNow(null);
        if (plan != null && !LlmGate.isEvalThread()) {
            announce(ai, plan);
        }
        return plan;
    }

    /** Wait up to LLM_PLAN_WAIT_MS for the plan (used at the mulligan, the first decision of a game). */
    public static GamePlan await(Player ai) {
        if (!enabledFor(ai) || ai.getRegisteredPlayer() == null) {
            return null;
        }
        CompletableFuture<GamePlan> f = future(ai);
        if (f == null) {
            return null;
        }
        if (!f.isDone()) {
            LlmStatus.fireStarted(ai.getName(), ai.getName() + "'s AI is studying its decklist (first game with this deck)");
            long start = System.currentTimeMillis();
            try {
                f.get(AiProfileUtil.getIntProperty(ai, AiProps.LLM_PLAN_WAIT_MS), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // keeps generating in the background; later hooks pick it up
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // reported by the planner thread
            }
            GamePlan plan = f.getNow(null);
            LlmStatus.fireFinished(ai.getName(), plan != null ? "game plan ready: " + plan.archetype()
                    : "game plan not ready yet, playing on", System.currentTimeMillis() - start);
        }
        return current(ai);
    }

    private static CompletableFuture<GamePlan> future(Player ai) {
        Deck deck = ai.getRegisteredPlayer().getDeck();
        if (deck == null) {
            return null;
        }
        String hash = deckHashes.computeIfAbsent(deck, GamePlanner::hash);
        String name = deck.getName() == null || deck.getName().isBlank() ? "deck" : deck.getName();
        String model = AiProfileUtil.getProperty(ai, AiProps.LLM_PLANNER_MODEL).trim();
        long timeout = AiProfileUtil.getIntProperty(ai, AiProps.LLM_PLANNER_TIMEOUT_MS);
        synchronized (plans) {
            return plans.computeIfAbsent(hash, h -> CompletableFuture.supplyAsync(() -> load(deck, name, h, model, timeout), POOL));
        }
    }

    /** Called on the planner thread: cached file if present, else ask the model and save. */
    static GamePlan load(Deck deck, String name, String hash, String model, long timeoutMs) {
        List<String> cardNames = cardNames(deck);
        File file = new File(planDir(), safe(name) + "-" + hash + ".json");
        if (file.isFile()) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                // plans written before contextCards existed are regenerated once
                if (o.has("contextCards")) {
                    return GamePlan.fromJson(o, cardNames);
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("LLM planner: can't read " + file + " (" + e.getMessage() + "), regenerating");
            }
        }

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("LLM planner: DEEPSEEK_API_KEY not set, no game plan for " + name);
            return null;
        }
        String base = System.getenv("DEEPSEEK_BASE_URL");
        DeepSeekProvider provider = new DeepSeekProvider(base == null || base.isBlank() ? DeepSeekProvider.DEFAULT_BASE_URL : base,
                apiKey.trim(), model);
        long start = System.currentTimeMillis();
        try {
            JsonObject raw = provider.completeJson(SYSTEM_PROMPT, describeDeck(deck, name), 16000, timeoutMs);
            GamePlan plan = GamePlan.fromJson(raw, cardNames);
            File dir = file.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                JsonObject out = plan.toJson();
                out.addProperty("_deck", name);
                out.addProperty("_model", model);
                out.addProperty("_note", "Generated by the LLM planner. Edit freely; delete the file to regenerate.");
                Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
            }
            LlmLog.write(0, 0, name, "plan", provider.name(), "game plan for " + name, List.of(plan.toLogLine()),
                    -1, null, null, System.currentTimeMillis() - start);
            return plan;
        } catch (LlmException | IOException | RuntimeException e) {
            LlmLog.write(0, 0, name, "plan", provider.name(), "game plan for " + name, List.of(), -1, null,
                    e.getMessage(), System.currentTimeMillis() - start);
            System.err.println("LLM planner: no game plan for " + name + ": " + e.getMessage());
            return null;
        }
    }

    private static void announce(Player ai, GamePlan plan) {
        Set<String> seen = announced.computeIfAbsent(ai.getGame(), g -> Collections.synchronizedSet(new HashSet<>()));
        if (seen.add(ai.getName())) {
            ai.getGame().getGameLog().add(GameLogEntryType.STACK_ADD, "[AI plan] " + ai.getName() + ": " + plan.toLogLine());
        }
    }

    static String describeDeck(Deck deck, String name) {
        StringBuilder sb = new StringBuilder("Deck: ").append(name).append('\n');
        for (PaperCard c : deck.getCommanders()) {
            sb.append("Commander: ").append(cardLine(c)).append('\n');
        }
        sb.append("\nDecklist:\n");
        for (Entry<PaperCard, Integer> e : deck.getMain()) {
            sb.append(e.getValue() > 1 ? e.getValue() + "x " : "").append(cardLine(e.getKey())).append('\n');
        }
        return sb.toString();
    }

    private static String cardLine(PaperCard c) {
        CardRules r = c.getRules();
        String text = r.getOracleText() == null ? "" : r.getOracleText().replace("\\n", " ").replace('\n', ' ');
        if (text.length() > 160) {
            text = text.substring(0, 157) + "...";
        }
        return c.getName() + " | " + r.getManaCost() + " | " + r.getType() + " | " + text;
    }

    static List<String> cardNames(Deck deck) {
        List<String> names = new ArrayList<>();
        for (PaperCard c : deck.getCommanders()) {
            names.add(c.getName());
        }
        for (Entry<PaperCard, Integer> e : deck.getMain()) {
            names.add(e.getKey().getName());
        }
        return names;
    }

    static String hash(Deck deck) {
        List<String> names = cardNames(deck);
        Collections.sort(names);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(String.join("\n", names).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(names.hashCode());
        }
    }

    private static File planDir() {
        String override = System.getProperty("forge.llm.plans");
        if (override != null) {
            return new File(override);
        }
        return new File(LlmLog.getFile().getParentFile(), "llm-plans");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
    }
}
