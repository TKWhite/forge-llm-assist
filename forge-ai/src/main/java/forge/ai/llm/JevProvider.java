package forge.ai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * TypeSafe's Jev System One model: POST /v1/systemone with one "choice" question whose criteria
 * are the numbered options. Jev returns the chosen option name plus a confidence, no prose.
 */
public class JevProvider implements LlmProvider {
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    static final String QUESTION = "decision";

    private static final String CONTEXT =
            "You are an expert Magic: The Gathering Commander player advising an AI opponent in a 4-player game. "
            + "The state describes the game from that player's point of view. Weigh threat assessment, "
            + "commander damage, card advantage and what each opponent can do next turn. ";

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public JevProvider(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return "jev:" + model;
    }

    @Override
    public LlmAnswer choose(String state, String question, List<String> options, long timeoutMs) throws LlmException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/systemone"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequest(state, question, options)))
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

    /** Options become choice names "1".."n" so the answer maps straight back to an index. */
    String buildRequest(String state, String question, List<String> options) {
        JsonObject criteria = new JsonObject();
        for (int i = 0; i < options.size(); i++) {
            criteria.addProperty(String.valueOf(i + 1), options.get(i));
        }
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "choice");
        decision.addProperty("instructions", CONTEXT + question);
        decision.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add(QUESTION, decision);

        JsonObject req = new JsonObject();
        req.addProperty("model", model);
        req.addProperty("state", state);
        req.add("questions", questions);
        return req.toString();
    }

    static LlmAnswer parseResponse(String body, int optionCount, long latencyMs) throws LlmException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject answer = root.getAsJsonObject("answers").getAsJsonObject(QUESTION);
            if (answer == null) {
                throw new LlmException("no answer in response: " + body);
            }
            JsonElement choice = answer.get("choice");
            if (choice == null || !choice.isJsonPrimitive()) {
                throw new LlmException("answer has no choice: " + answer);
            }
            int picked = Integer.parseInt(choice.getAsString().trim());
            if (picked < 1 || picked > optionCount) {
                throw new LlmException("choice " + picked + " out of range 1-" + optionCount);
            }
            double confidence = answer.has("confidence") && !answer.get("confidence").isJsonNull()
                    ? answer.get("confidence").getAsDouble() : 0.5;
            int tokens = 0;
            if (root.has("usage") && root.getAsJsonObject("usage").has("input_tokens")) {
                tokens = root.getAsJsonObject("usage").get("input_tokens").getAsInt();
            }
            // Jev gives no prose, so the confidence stands in for the reason
            String reason = String.format("confidence %.0f%%", confidence * 100);
            return new LlmAnswer(picked - 1, confidence, reason, tokens, latencyMs);
        } catch (JsonParseException | IllegalStateException | ClassCastException | NullPointerException
                 | NumberFormatException | UnsupportedOperationException e) {
            throw new LlmException("unreadable answer: " + e.getMessage(), e);
        }
    }
}
