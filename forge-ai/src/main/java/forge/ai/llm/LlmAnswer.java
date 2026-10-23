package forge.ai.llm;

/**
 * A validated answer: the option index plus whatever the model said about it.
 *
 * @param probabilities the model's probability for each option (same order as the options), or null when
 *                      the provider doesn't report them
 */
public record LlmAnswer(int index, double confidence, String reason, int inputTokens, long latencyMs,
                        double[] probabilities) {

    public LlmAnswer(int index, double confidence, String reason, int inputTokens, long latencyMs) {
        this(index, confidence, reason, inputTokens, latencyMs, null);
    }
}
