package forge.ai.llm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets a GUI show that an AI player is waiting on the model, so the pause isn't a mystery.
 * Listeners are called on the game thread; GUI code must hop to its own thread.
 */
public final class LlmStatus {
    public interface Listener {
        /** @param description e.g. "Gisa's AI is choosing a target for Swords to Plowshares" */
        void started(String playerName, String description);

        /** @param outcome short text such as "chose X" or "timed out, using standard AI" */
        void finished(String playerName, String outcome, long elapsedMs);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private LlmStatus() {
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void fireStarted(String playerName, String description) {
        for (Listener l : listeners) {
            try {
                l.started(playerName, description);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    static void fireFinished(String playerName, String outcome, long elapsedMs) {
        for (Listener l : listeners) {
            try {
                l.finished(playerName, outcome, elapsedMs);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }
}
