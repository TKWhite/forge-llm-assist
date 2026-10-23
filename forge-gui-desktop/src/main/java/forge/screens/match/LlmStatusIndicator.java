package forge.screens.match;

import java.awt.Color;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import forge.ai.llm.LlmStatus;

/**
 * Shows "X's AI is thinking..." with a running timer in the prompt panel while an AI player
 * waits on the LLM assist, so the pause doesn't look like a frozen game.
 */
public class LlmStatusIndicator implements LlmStatus.Listener {
    private static final Color THINKING_BG = new Color(72, 52, 128);
    private static final Color DONE_BG = new Color(40, 90, 60);
    private static final Color FALLBACK_BG = new Color(120, 70, 30);
    private static final int LINGER_MS = 2500;

    private final JLabel label;
    private final Timer ticker;
    private final Timer hider;
    private String activity = "";
    private long startedAt;

    public LlmStatusIndicator(JLabel label) {
        this.label = label;
        label.setOpaque(true);
        label.setForeground(Color.WHITE);
        label.setVisible(false);
        ticker = new Timer(200, e -> render());
        hider = new Timer(LINGER_MS, e -> label.setVisible(false));
        hider.setRepeats(false);
    }

    @Override
    public void started(String playerName, String description) {
        SwingUtilities.invokeLater(() -> {
            hider.stop();
            activity = description;
            startedAt = System.currentTimeMillis();
            label.setBackground(THINKING_BG);
            render();
            label.setVisible(true);
            ticker.start();
        });
    }

    @Override
    public void finished(String playerName, String outcome, long elapsedMs) {
        SwingUtilities.invokeLater(() -> {
            ticker.stop();
            boolean fallback = outcome.startsWith("AI assist unavailable");
            label.setBackground(fallback ? FALLBACK_BG : DONE_BG);
            String text = playerName + ": " + outcome + String.format(" (%.1fs)", elapsedMs / 1000.0);
            label.setText(" " + text);
            label.setToolTipText(text);
            hider.restart();
        });
    }

    private void render() {
        double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
        String text = "AI assist: " + activity + String.format("... (%.1fs)", secs);
        label.setText(" " + text);
        label.setToolTipText(text);
    }

    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            ticker.stop();
            hider.stop();
            label.setVisible(false);
        });
    }
}
