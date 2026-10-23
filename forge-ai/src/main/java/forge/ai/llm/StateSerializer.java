package forge.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Compact text description of the game from one player's point of view.
 * Opponents' hands are only counted, never shown. Oracle text is added only for the cards
 * involved in the current decision to keep each call at a couple of thousand tokens.
 */
public final class StateSerializer {
    private static final Keyword[] SHOWN_KEYWORDS = {
            Keyword.FLYING, Keyword.TRAMPLE, Keyword.DEATHTOUCH, Keyword.LIFELINK, Keyword.DOUBLE_STRIKE,
            Keyword.FIRST_STRIKE, Keyword.MENACE, Keyword.HEXPROOF, Keyword.SHROUD, Keyword.WARD,
            Keyword.INDESTRUCTIBLE, Keyword.INFECT, Keyword.HASTE, Keyword.VIGILANCE, Keyword.REACH
    };

    private StateSerializer() {
    }

    public static String describe(Player me, Collection<Card> focusCards) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        StringBuilder sb = new StringBuilder();
        sb.append("Turn ").append(ph.getTurn()).append(" (round ").append(round(game)).append("), phase ")
                .append(ph.getPhase()).append(", active player: ")
                .append(ph.getPlayerTurn() == null ? "-" : ph.getPlayerTurn().getName()).append('\n');
        sb.append("You are ").append(me.getName()).append(".\n");
        GamePlan plan = GamePlanner.current(me);
        if (plan != null) {
            sb.append(plan.toPromptBlock());
        }
        sb.append('\n');

        for (Player p : game.getPlayers()) {
            describePlayer(sb, p, p == me);
        }

        sb.append("Your hand: ").append(names(me.getCardsIn(ZoneType.Hand))).append('\n');

        if (!game.getStack().isEmpty()) {
            sb.append("Stack (top first):\n");
            for (SpellAbilityStackInstance si : game.getStack()) {
                sb.append("- ").append(si.getActivatingPlayer().getName()).append(": ")
                        .append(si.getStackDescription()).append('\n');
            }
        }

        if (focusCards != null && !focusCards.isEmpty()) {
            sb.append("\nCard text for this decision:\n");
            for (Card c : focusCards) {
                sb.append("- ").append(c.getName()).append(": ").append(oneLine(c.getOracleText())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Which time around the table this is, so "turn 4" means the same thing in 2 and 4 player games. */
    public static int round(Game game) {
        int players = Math.max(1, game.getRegisteredPlayers().size());
        return (game.getPhaseHandler().getTurn() - 1) / players + 1;
    }

    private static void describePlayer(StringBuilder sb, Player p, boolean isMe) {
        sb.append("== ").append(p.getName()).append(isMe ? " (you)" : "")
                .append(": life ").append(p.getLife())
                .append(", hand ").append(p.getCardsIn(ZoneType.Hand).size())
                .append(", library ").append(p.getCardsIn(ZoneType.Library).size())
                .append(", graveyard ").append(p.getCardsIn(ZoneType.Graveyard).size());
        List<Card> commanders = p.getCommanders();
        if (commanders != null && !commanders.isEmpty()) {
            sb.append(", commander ").append(names(commanders));
            for (Card cmd : commanders) {
                if (!cmd.isInZone(ZoneType.Battlefield)) {
                    sb.append(" [").append(cmd.getName()).append(" in ").append(cmd.getZone() == null ? "?" : cmd.getZone().getZoneType()).append(']');
                }
            }
        }
        sb.append('\n');

        List<String> damage = new ArrayList<>();
        for (Entry<Card, Integer> e : p.getCommanderDamage()) {
            if (e.getValue() > 0) {
                damage.add(e.getKey().getName() + " " + e.getValue());
            }
        }
        if (!damage.isEmpty()) {
            sb.append("  commander damage taken: ").append(String.join(", ", damage)).append('\n');
        }

        int lands = 0, untappedLands = 0;
        Map<String, Integer> creatures = new LinkedHashMap<>();
        Map<String, Integer> others = new LinkedHashMap<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isCreature()) {
                lands++;
                if (!c.isTapped()) {
                    untappedLands++;
                }
            } else if (c.isCreature()) {
                creatures.merge(creatureLabel(c), 1, Integer::sum);
            } else {
                others.merge(permanentLabel(c), 1, Integer::sum);
            }
        }
        sb.append("  lands: ").append(lands).append(" (").append(untappedLands).append(" untapped)\n");
        if (!creatures.isEmpty()) {
            sb.append("  creatures: ").append(counted(creatures)).append('\n');
        }
        if (!others.isEmpty()) {
            sb.append("  other permanents: ").append(counted(others)).append('\n');
        }
    }

    public static String creatureLabel(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
        List<String> kws = new ArrayList<>();
        for (Keyword k : SHOWN_KEYWORDS) {
            if (c.hasKeyword(k)) {
                kws.add(k.toString().toLowerCase());
            }
        }
        if (!kws.isEmpty()) {
            sb.append(" (").append(String.join(", ", kws)).append(')');
        }
        if (c.isCommander()) {
            sb.append(" [commander]");
        }
        if (c.isToken()) {
            sb.append(" [token]");
        }
        if (c.isTapped()) {
            sb.append(" [tapped]");
        }
        return sb.toString();
    }

    public static String permanentLabel(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        if (c.isPlaneswalker()) {
            sb.append(" (loyalty ").append(c.getCurrentLoyalty()).append(')');
        }
        if (c.isCommander()) {
            sb.append(" [commander]");
        }
        if (c.isTapped()) {
            sb.append(" [tapped]");
        }
        return sb.toString();
    }

    /** Short description of a card for use as an option. */
    public static String label(Card c) {
        String body = c.isCreature() ? creatureLabel(c) : permanentLabel(c);
        return body + ", controlled by " + c.getController().getName();
    }

    private static String counted(Map<String, Integer> items) {
        List<String> parts = new ArrayList<>();
        for (Entry<String, Integer> e : items.entrySet()) {
            parts.add(e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey());
        }
        return String.join("; ", parts);
    }

    private static String names(Iterable<Card> cards) {
        List<String> parts = new ArrayList<>();
        for (Card c : cards) {
            parts.add(c.getName());
        }
        return parts.isEmpty() ? "(none)" : String.join(", ", parts);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\r", "").replace('\n', ' ');
    }
}
