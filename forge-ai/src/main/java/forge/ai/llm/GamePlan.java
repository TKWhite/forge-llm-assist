package forge.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What a deck is trying to do, worked out once per deck by the planner model and cached on disk.
 * Card names are checked against the deck so a hallucinated card can't steer play.
 */
public record GamePlan(String archetype, String summary, List<WinCondition> winConditions, List<String> keyCards,
                       List<HoldCard> holdCards, List<String> rampPriority, Mulligan mulligan, String watchFor) {

    public record WinCondition(List<String> cards, String how) {
    }

    public record HoldCard(String card, String castWhen) {
    }

    public record Mulligan(int minLands, int maxLands, List<String> wantAny, String notes) {
    }

    /** @return the cast-when condition if this card should be held for the right moment, else null */
    public String holdCondition(String cardName) {
        for (HoldCard h : holdCards) {
            if (h.card().equalsIgnoreCase(cardName)) {
                return h.castWhen();
            }
        }
        return null;
    }

    public boolean isKeyCard(String cardName) {
        for (String k : keyCards) {
            if (k.equalsIgnoreCase(cardName)) {
                return true;
            }
        }
        for (WinCondition w : winConditions) {
            for (String c : w.cards()) {
                if (c.equalsIgnoreCase(cardName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Short block added to every in-game prompt (~150-250 tokens). */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder("YOUR GAME PLAN (").append(archetype).append("): ").append(summary).append('\n');
        for (WinCondition w : winConditions) {
            sb.append("- Win condition: ").append(w.how());
            if (!w.cards().isEmpty()) {
                sb.append(" [").append(String.join(", ", w.cards())).append(']');
            }
            sb.append('\n');
        }
        if (!holdCards.isEmpty()) {
            sb.append("- Hold for the right moment: ");
            List<String> parts = new ArrayList<>();
            for (HoldCard h : holdCards) {
                parts.add(h.card() + " (" + h.castWhen() + ")");
            }
            sb.append(String.join("; ", parts)).append('\n');
        }
        if (!watchFor.isEmpty()) {
            sb.append("- Watch for: ").append(watchFor).append('\n');
        }
        return sb.toString();
    }

    /** One line for the game log: archetype, the main win condition, and what it plans to hold. */
    public String toLogLine() {
        String win = winConditions.isEmpty() ? "" : winConditions.get(0).how();
        if (win.length() > 90) {
            win = win.substring(0, 87) + "...";
        }
        List<String> held = new ArrayList<>();
        for (HoldCard h : holdCards) {
            held.add(h.card());
        }
        return archetype + (win.isEmpty() ? "" : "; main plan: " + win)
                + (held.isEmpty() ? "" : "; holding: " + String.join(", ", held));
    }

    /**
     * Parse the planner's JSON. Card names not in {@code deckCards} are dropped (case-insensitive,
     * the deck's own spelling is kept). Missing fields get empty defaults.
     */
    public static GamePlan fromJson(JsonObject o, Collection<String> deckCards) {
        Map<String, String> known = new TreeMap<>();
        for (String c : deckCards) {
            known.put(c.toLowerCase(Locale.ROOT), c);
        }

        List<WinCondition> wins = new ArrayList<>();
        for (JsonElement e : array(o, "winConditions")) {
            if (e.isJsonObject()) {
                JsonObject w = e.getAsJsonObject();
                wins.add(new WinCondition(cards(w, "cards", known), string(w, "how")));
            }
        }
        List<HoldCard> holds = new ArrayList<>();
        for (JsonElement e : array(o, "holdCards")) {
            if (e.isJsonObject()) {
                JsonObject h = e.getAsJsonObject();
                String card = known.get(string(h, "card").toLowerCase(Locale.ROOT));
                if (card != null) {
                    holds.add(new HoldCard(card, string(h, "castWhen")));
                }
            }
        }
        Mulligan mull = new Mulligan(2, 5, List.of(), "");
        if (o.has("mulligan") && o.get("mulligan").isJsonObject()) {
            JsonObject m = o.getAsJsonObject("mulligan");
            mull = new Mulligan(integer(m, "minLands", 2), integer(m, "maxLands", 5), cards(m, "wantAny", known), string(m, "keepNotes"));
        }
        return new GamePlan(string(o, "archetype"), string(o, "summary"), wins, cards(o, "keyCards", known), holds,
                cards(o, "rampPriority", known), mull, string(o, "watchFor"));
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("archetype", archetype);
        o.addProperty("summary", summary);
        JsonArray wins = new JsonArray();
        for (WinCondition w : winConditions) {
            JsonObject j = new JsonObject();
            j.add("cards", toArray(w.cards()));
            j.addProperty("how", w.how());
            wins.add(j);
        }
        o.add("winConditions", wins);
        o.add("keyCards", toArray(keyCards));
        JsonArray holds = new JsonArray();
        for (HoldCard h : holdCards) {
            JsonObject j = new JsonObject();
            j.addProperty("card", h.card());
            j.addProperty("castWhen", h.castWhen());
            holds.add(j);
        }
        o.add("holdCards", holds);
        o.add("rampPriority", toArray(rampPriority));
        JsonObject m = new JsonObject();
        m.addProperty("minLands", mulligan.minLands());
        m.addProperty("maxLands", mulligan.maxLands());
        m.add("wantAny", toArray(mulligan.wantAny()));
        m.addProperty("keepNotes", mulligan.notes());
        o.add("mulligan", m);
        o.addProperty("watchFor", watchFor);
        return o;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }

    private static int integer(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static List<String> cards(JsonObject o, String key, Map<String, String> known) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, key)) {
            if (e.isJsonPrimitive()) {
                String c = known.get(e.getAsString().trim().toLowerCase(Locale.ROOT));
                if (c != null && !out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static JsonArray toArray(List<String> items) {
        JsonArray a = new JsonArray();
        items.forEach(a::add);
        return a;
    }
}
