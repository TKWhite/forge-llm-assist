package forge.ai.llm;

/** A validated answer: the option index plus whatever the model said about it. */
public record LlmAnswer(int index, double confidence, String reason, int inputTokens, long latencyMs) {
}
