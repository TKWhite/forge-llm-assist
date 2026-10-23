package forge.ai.llm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GamePlanTest {
    private static final List<String> DECK = List.of("Smaug, the Magnificent Trickster", "Sol Ring", "Cursed Mirror",
            "Blasphemous Act", "Old Gnawbone", "Mountain");

    private static GamePlan parse(String json) {
        return GamePlan.fromJson(JsonParser.parseString(json).getAsJsonObject(), DECK);
    }

    @Test
    public void parsesAndDropsUnknownCards() {
        GamePlan plan = parse("{\"archetype\":\"Dragon ramp\",\"summary\":\"Ramp into dragons.\","
                + "\"winConditions\":[{\"cards\":[\"old gnawbone\",\"Shivan Dragon\"],\"how\":\"Big flyers\"}],"
                + "\"keyCards\":[\"Sol Ring\",\"Black Lotus\"],"
                + "\"holdCards\":[{\"card\":\"Blasphemous Act\",\"castWhen\":\"opponents have more creatures\"},"
                + "{\"card\":\"Imaginary Card\",\"castWhen\":\"never\"}],"
                + "\"rampPriority\":[\"Sol Ring\",\"Cursed Mirror\"],"
                + "\"mulligan\":{\"minLands\":3,\"maxLands\":5,\"wantAny\":[\"Sol Ring\"],\"keepNotes\":\"need ramp\"},"
                + "\"watchFor\":\"flyers\","
                + "\"contextCards\":[{\"card\":\"Cursed Mirror\",\"risk\":\"dual-use\",\"guidance\":\"copy only before combat\"},"
                + "{\"card\":\"Fake Card\",\"risk\":\"timing\",\"guidance\":\"x\"}]}");
        assertEquals(plan.archetype(), "Dragon ramp");
        assertEquals(plan.winConditions().get(0).cards(), List.of("Old Gnawbone"), "deck spelling kept, unknown dropped");
        assertEquals(plan.keyCards(), List.of("Sol Ring"));
        assertEquals(plan.holdCards().size(), 1);
        assertEquals(plan.holdCondition("blasphemous act"), "opponents have more creatures");
        assertNull(plan.holdCondition("Sol Ring"));
        assertTrue(plan.isKeyCard("Old Gnawbone"), "win-condition cards count as key cards");
        assertFalse(plan.isKeyCard("Mountain"));
        assertEquals(plan.mulligan().minLands(), 3);
        assertTrue(plan.toPromptBlock().contains("Blasphemous Act (opponents have more creatures)"));
        assertEquals(plan.contextCards().size(), 1, "unknown context card dropped");
        assertEquals(plan.contextGuidance("cursed mirror"), "copy only before combat");
        assertNull(plan.contextGuidance("Sol Ring"));
    }

    @Test
    public void missingFieldsGetDefaults() {
        GamePlan plan = parse("{\"archetype\":\"x\"}");
        assertTrue(plan.winConditions().isEmpty());
        assertTrue(plan.holdCards().isEmpty());
        assertEquals(plan.mulligan().minLands(), 2);
        assertEquals(plan.mulligan().maxLands(), 5);
        assertEquals(plan.summary(), "");
    }

    @Test
    public void jsonRoundTrip() {
        GamePlan plan = parse("{\"archetype\":\"a\",\"summary\":\"s\",\"keyCards\":[\"Sol Ring\"],"
                + "\"holdCards\":[{\"card\":\"Old Gnawbone\",\"castWhen\":\"late\"}],\"mulligan\":{\"minLands\":3,\"maxLands\":4}}");
        JsonObject json = plan.toJson();
        assertEquals(GamePlan.fromJson(json, DECK), plan);
    }

    @Test
    public void stripsThinkBlocksAndFences() throws Exception {
        JsonObject o = DeepSeekProvider.extractJsonObject("<think>maybe {\"a\":0}?</think>```json\n{\"archetype\":\"x\"}\n```");
        assertEquals(o.get("archetype").getAsString(), "x");
    }
}
