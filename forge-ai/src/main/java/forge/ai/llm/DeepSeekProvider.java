package forge.ai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * DeepSeek chat completions (OpenAI-compatible) asked to answer with a JSON object
 * <code>{"choice": n, "confidence": 0..1, "reason": "..."}</code>.
 */
public class DeepSeekProvider implements LlmProvider {
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering Commander player advising an AI opponent in a 4-player game. "
            + "You get the game state and one decision with numbered options. Think about threat assessment, "
            + "commander damage, card advantage and what each opponent can do next turn. "
            + "Reply ONLY with a JSON object: {\"choice\": <option number>, \"confidence\": <0 to 1>, "
            + "\"reason\": \"<one short sentence>\"}";

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DeepSeekProvider(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return "deepseek:" + model;
    }

    @Override
    public LlmAnswer choose(String state, String question, List<String> options, long timeoutMs) throws LlmException {
        String body = buildRequest(state, question, options);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException("timed out after " + timeoutMs + " ms", e);
        } catch (IOException e) {
            throw new LlmException("request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
        long latency = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            String text = response.body();
            throw new LlmException("HTTP " + response.statusCode() + ": " + (text.length() > 200 ? text.substring(0, 200) : text));
        }
        return parseResponse(response.body(), options.size(), latency);
    }

    String buildRequest(String state, String question, List<String> options) {
        StringBuilder user = new StringBuilder();
        user.append("GAME STATE\n").append(state).append("\n\nDECISION\n").append(question).append("\n\nOPTIONS\n");
        for (int i = 0; i < options.size(); i++) {
            user.append(i + 1).append(". ").append(options.get(i)).append('\n');
        }

        JsonObject req = new JsonObject();
        req.addProperty("model", model);
        req.addProperty("temperature", 0);
        req.addProperty("max_tokens", 120);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        req.add("response_format", format);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", user.toString()));
        req.add("messages", messages);
        return req.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Options are shown 1-based to the model, the returned index is 0-based. */
    static LlmAnswer parseResponse(String body, int optionCount, long latencyMs) throws LlmException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            int tokens = 0;
            if (root.has("usage") && root.getAsJsonObject("usage").has("prompt_tokens")) {
                tokens = root.getAsJsonObject("usage").get("prompt_tokens").getAsInt();
            }

            // tolerate prose or code fences around the object
            int open = content.indexOf('{');
            int close = content.lastIndexOf('}');
            if (open < 0 || close < open) {
                throw new LlmException("no JSON object in answer: " + content);
            }
            JsonObject answer = JsonParser.parseString(content.substring(open, close + 1)).getAsJsonObject();
            JsonElement choice = answer.get("choice");
            if (choice == null || !choice.isJsonPrimitive()) {
                throw new LlmException("answer has no choice: " + content);
            }
            int picked = (int) Math.round(choice.getAsDouble());
            if (picked < 1 || picked > optionCount) {
                throw new LlmException("choice " + picked + " out of range 1-" + optionCount);
            }
            double confidence = answer.has("confidence") ? answer.get("confidence").getAsDouble() : 0.5;
            String reason = answer.has("reason") ? answer.get("reason").getAsString() : "";
            return new LlmAnswer(picked - 1, confidence, reason, tokens, latencyMs);
        } catch (JsonParseException | IllegalStateException | NullPointerException | IndexOutOfBoundsException
                 | NumberFormatException | UnsupportedOperationException e) {
            throw new LlmException("unreadable answer: " + e.getMessage(), e);
        }
    }
}
