package forge.ai.controller;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class CommanderMulliganTest extends AITest {

    private Player commanderPlayer(Game game) {
        Player ai = game.getPlayers().get(1);
        ai.addCommander(createCard("Isamaru, Hound of Konda", ai));
        return ai;
    }

    private void hand(Player p, int lands, String... spells) {
        for (int i = 0; i < lands; i++) {
            addCardToZone("Plains", p, ZoneType.Hand);
        }
        for (String s : spells) {
            addCardToZone(s, p, ZoneType.Hand);
        }
    }

    @Test
    public void mulligansAOneLander() {
        Game game = initAndCreateThreePlayerGame();
        Player ai = commanderPlayer(game);
        hand(ai, 1, "Savannah Lions", "Savannah Lions", "Glorious Anthem", "Serra Angel", "Serra Angel", "Wrath of God");
        AssertJUnit.assertTrue(ComputerUtil.wantMulligan(ai, 0));
    }

    @Test
    public void usesTheFreeMulliganOnTwoLandsWithoutRamp() {
        Game game = initAndCreateThreePlayerGame();
        Player ai = commanderPlayer(game);
        hand(ai, 2, "Savannah Lions", "Glorious Anthem", "Serra Angel", "Serra Angel", "Wrath of God");
        AssertJUnit.assertTrue(ComputerUtil.wantMulligan(ai, 0));
    }

    @Test
    public void keepsTwoLandsWithRamp() {
        Game game = initAndCreateThreePlayerGame();
        Player ai = commanderPlayer(game);
        hand(ai, 2, "Mind Stone", "Glorious Anthem", "Serra Angel", "Serra Angel", "Wrath of God");
        AssertJUnit.assertFalse(ComputerUtil.wantMulligan(ai, 0));
    }

    @Test
    public void keepsThreeLands() {
        Game game = initAndCreateThreePlayerGame();
        Player ai = commanderPlayer(game);
        hand(ai, 3, "Savannah Lions", "Serra Angel", "Serra Angel", "Wrath of God");
        AssertJUnit.assertFalse(ComputerUtil.wantMulligan(ai, 0));
    }

    @Test
    public void keepsAnythingWithLandsAtFiveCards() {
        Game game = initAndCreateThreePlayerGame();
        Player ai = commanderPlayer(game);
        hand(ai, 1, "Serra Angel", "Serra Angel", "Wrath of God", "Wrath of God", "Wrath of God", "Wrath of God");
        AssertJUnit.assertFalse(ComputerUtil.wantMulligan(ai, 2));
    }
}
