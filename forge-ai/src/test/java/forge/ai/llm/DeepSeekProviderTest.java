package forge.ai.llm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public class DeepSeekProviderTest {
    private static final List<String> OPTIONS = List.of("Alice", "Bob", "Carol");

    private static String completion(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", msg);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 1234);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        root.add("usage", usage);
        return root.toString();
    }

    @Test
    public void parsesOneBasedChoice() throws Exception {
        LlmAnswer a = DeepSeekProvider.parseResponse(
                completion("{\"choice\": 2, \"confidence\": 0.8, \"reason\": \"Bob is closest to winning\"}"), 3, 50);
        assertEquals(a.index(), 1);
        assertEquals(a.confidence(), 0.8, 1e-9);
        assertEquals(a.reason(), "Bob is closest to winning");
        assertEquals(a.inputTokens(), 1234);
    }

    @Test
    public void toleratesCodeFences() throws Exception {
        LlmAnswer a = DeepSeekProvider.parseResponse(completion("```json\n{\"choice\": 3}\n```"), 3, 0);
        assertEquals(a.index(), 2);
        assertEquals(a.reason(), "");
    }

    @Test
    public void rejectsOutOfRange() {
        expectThrows(LlmException.class, () -> DeepSeekProvider.parseResponse(completion("{\"choice\": 4}"), 3, 0));
        expectThrows(LlmException.class, () -> DeepSeekProvider.parseResponse(completion("{\"choice\": 0}"), 3, 0));
    }

    @Test
    public void rejectsGarbage() {
        expectThrows(LlmException.class, () -> DeepSeekProvider.parseResponse(completion("I pick Bob"), 3, 0));
        expectThrows(LlmException.class, () -> DeepSeekProvider.parseResponse("not json", 3, 0));
        expectThrows(LlmException.class, () -> DeepSeekProvider.parseResponse(completion("{\"pick\": 1}"), 3, 0));
    }

    @Test
    public void requestListsOptionsOneBased() {
        DeepSeekProvider p = new DeepSeekProvider("http://localhost", "k", "deepseek-flash");
        JsonObject req = JsonParser.parseString(p.buildRequest("STATE", "Who?", OPTIONS)).getAsJsonObject();
        assertEquals(req.get("model").getAsString(), "deepseek-flash");
        assertEquals(req.getAsJsonObject("response_format").get("type").getAsString(), "json_object");
        String user = req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.contains("1. Alice\n2. Bob\n3. Carol"), user);
        assertTrue(user.contains("STATE"));
    }

    @Test
    public void roundTripAgainstStubServer() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, completion("{\"choice\": 1, \"confidence\": 0.6, \"reason\": \"ok\"}"));
        });
        server.createContext("/slow/chat/completions", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            respond(ex, 200, completion("{\"choice\": 1}"));
        });
        server.createContext("/err/chat/completions", ex -> respond(ex, 401, "{\"error\":\"bad key\"}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            LlmAnswer a = new DeepSeekProvider(base, "secret", "m").choose("s", "q", OPTIONS, 2000);
            assertEquals(a.index(), 0);
            assertEquals(auth.get(), "Bearer secret");

            LlmException timeout = expectThrows(LlmException.class,
                    () -> new DeepSeekProvider(base + "/slow", "k", "m").choose("s", "q", OPTIONS, 300));
            assertTrue(timeout.getMessage().contains("timed out"), timeout.getMessage());

            LlmException http = expectThrows(LlmException.class,
                    () -> new DeepSeekProvider(base + "/err", "k", "m").choose("s", "q", OPTIONS, 2000));
            assertTrue(http.getMessage().startsWith("HTTP 401"), http.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
