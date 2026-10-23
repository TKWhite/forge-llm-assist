package forge.ai.llm;

import java.util.List;

/**
 * A model that picks one option from a list given a text description of the game.
 * Shaped after TypeSafe's Jev "Choice" question so a Jev backend can drop in later.
 */
public interface LlmProvider {

    String name();

    /**
     * @param state    compact text description of the game from the deciding player's point of view
     * @param question what is being decided
     * @param options  the legal options, the answer refers to them by index
     * @param timeoutMs give up after this long
     */
    LlmAnswer choose(String state, String question, List<String> options, long timeoutMs) throws LlmException;
}
