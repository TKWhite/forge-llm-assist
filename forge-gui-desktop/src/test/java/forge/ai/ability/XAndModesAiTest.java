package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class XAndModesAiTest extends AITest {

    /**
     * Worldsoul's Rage also puts up to X lands onto the battlefield, so the AI must not shrink X to just enough to
     * kill its target: in a test match a Rage approved at X=21 was cast at X=3 on a 3/3.
     */
    @Test
    public void keepsXWhenItScalesAnotherEffect() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        Card rage = addCardToZone("Worldsoul's Rage", ai, ZoneType.Hand);
        addCards("Mountain", 4, ai);
        addCards("Forest", 3, ai);
        for (int i = 0; i < 5; i++) {
            addCardToZone("Forest", ai, ZoneType.Hand);
        }
        Card bear = addCard("Runeclaw Bear", opponent);
        bear.setSickness(false);
        opponent.setLife(40, null);
        // late enough that no profile holds an X damage spell back for more damage
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 12);

        SpellAbility sa = rage.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        // the damage AI's target choice has random elements
        Random saved = MyRandom.getRandom();
        MyRandom.setRandom(new Random(1));
        AiPlayDecision decision;
        try {
            decision = ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa);
        } finally {
            MyRandom.setRandom(saved);
        }

        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, decision);
        AssertJUnit.assertEquals("X should stay at the 5 the lands allow", Integer.valueOf(5), sa.getXManaCostPaid());
    }

    /**
     * Fiery Confluence needs three modes. With nothing to hit but opponents, the stock AI found no set of three
     * different useful modes and never cast it; repeating "2 damage to each opponent" is the obvious play.
     */
    @Test
    public void repeatsASafeConfluenceMode() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        addCardToZone("Fiery Confluence", ai, ZoneType.Hand);
        addCards("Mountain", 4, ai);

        playUntilStackClear(game);

        AssertJUnit.assertEquals("Fiery Confluence should deal 6 to the opponent", 14, opponent.getLife());
        AssertJUnit.assertEquals("AI should take no damage", 20, ai.getLife());
    }
}
