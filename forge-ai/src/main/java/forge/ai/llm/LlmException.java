package forge.ai.llm;

/** Any failure talking to the model: network, timeout, HTTP error or an unusable answer. */
public class LlmException extends Exception {
    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
