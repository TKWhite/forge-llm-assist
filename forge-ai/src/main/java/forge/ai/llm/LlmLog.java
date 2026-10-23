package forge.ai.llm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One JSON line per model call, for tuning thresholds and judging whether the assist helps.
 * Location: system property forge.llm.log, else %APPDATA%/Forge/llm-assist.jsonl, else ~/.forge/llm-assist.jsonl.
 */
final class LlmLog {
    private static File file;

    private LlmLog() {
    }

    static synchronized File getFile() {
        if (file == null) {
            String override = System.getProperty("forge.llm.log");
            if (override != null) {
                file = new File(override);
            } else if (System.getenv("APPDATA") != null) {
                file = new File(System.getenv("APPDATA"), "Forge" + File.separator + "llm-assist.jsonl");
            } else {
                file = new File(System.getProperty("user.home"), ".forge" + File.separator + "llm-assist.jsonl");
            }
        }
        return file;
    }

    static void write(int gameId, int turn, String player, String hook, String provider, String question,
                      List<String> options, int forgeIndex, LlmAnswer answer, String error, long elapsedMs) {
        JsonObject line = new JsonObject();
        line.addProperty("time", Instant.now().toString());
        line.addProperty("game", gameId);
        line.addProperty("turn", turn);
        line.addProperty("player", player);
        line.addProperty("hook", hook);
        line.addProperty("provider", provider);
        line.addProperty("question", question);
        JsonArray opts = new JsonArray();
        options.forEach(opts::add);
        line.add("options", opts);
        line.addProperty("forge_pick", forgeIndex);
        if (answer != null) {
            line.addProperty("llm_pick", answer.index());
            line.addProperty("confidence", answer.confidence());
            line.addProperty("reason", answer.reason());
            line.addProperty("input_tokens", answer.inputTokens());
        }
        if (error != null) {
            line.addProperty("error", error);
        }
        line.addProperty("ms", elapsedMs);
        append(line.toString());
    }

    private static synchronized void append(String text) {
        File f = getFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(text);
            w.write('\n');
        } catch (IOException e) {
            System.err.println("LLM assist log write failed: " + e.getMessage());
        }
    }
}
