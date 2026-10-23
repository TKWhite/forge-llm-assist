package forge.ai.llm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public class JevProviderTest {
    private static final List<String> OPTIONS = List.of("Alice", "Bob", "Carol");

    private static String response(String choice, double confidence) {
        return "{\"model\":\"jev-1.13.0\",\"answers\":{\"decision\":{\"type\":\"choice\",\"choice\":\"" + choice
                + "\",\"confidence\":" + confidence + ",\"probabilities\":{\"1\":0.1,\"2\":0.8,\"3\":0.1}}},"
                + "\"usage\":{\"input_tokens\":900,\"output_tokens\":1}}";
    }

    @Test
    public void requestUsesNumberedChoiceCriteria() {
        JevProvider p = new JevProvider("http://localhost", "k", "jev-latest");
        JsonObject req = JsonParser.parseString(p.buildRequest("STATE", "Who?", OPTIONS)).getAsJsonObject();
        assertEquals(req.get("model").getAsString(), "jev-latest");
        assertEquals(req.get("state").getAsString(), "STATE");
        JsonObject q = req.getAsJsonObject("questions").getAsJsonObject("decision");
        assertEquals(q.get("type").getAsString(), "choice");
        assertTrue(q.get("instructions").getAsString().endsWith("Who?"));
        assertEquals(q.getAsJsonObject("criteria").get("2").getAsString(), "Bob");
        assertEquals(q.getAsJsonObject("criteria").size(), 3);
    }

    @Test
    public void parsesChoiceName() throws Exception {
        LlmAnswer a = JevProvider.parseResponse(response("2", 0.8), 3, 40);
        assertEquals(a.index(), 1);
        assertEquals(a.confidence(), 0.8, 1e-9);
        assertEquals(a.inputTokens(), 900);
        assertEquals(a.probabilities().length, 3);
        assertEquals(a.probabilities()[1], 0.8, 1e-9);
        assertEquals(a.probabilities()[2], 0.1, 1e-9);
    }

    @Test
    public void rejectsBadAnswers() {
        expectThrows(LlmException.class, () -> JevProvider.parseResponse(response("4", 0.5), 3, 0));
        expectThrows(LlmException.class, () -> JevProvider.parseResponse(response("Bob", 0.5), 3, 0));
        expectThrows(LlmException.class, () -> JevProvider.parseResponse("{\"answers\":{}}", 3, 0));
        expectThrows(LlmException.class, () -> JevProvider.parseResponse("<html>", 3, 0));
    }

    @Test
    public void roundTripAgainstStubServer() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            byte[] bytes = response("3", 0.6).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            LlmAnswer a = new JevProvider(base, "secret", "jev-latest").choose("s", "q", OPTIONS, 2000);
            assertEquals(a.index(), 2);
            assertEquals(auth.get(), "Bearer secret");
        } finally {
            server.stop(0);
        }
    }
}
