package forge.ai.attacking;

import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class BasicAttackTests extends SimulationTest {

    @Test
    public void assaultForLethal() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        defender.setLife(10, null);

        String bears = "Grizzly Bears";

        // Add 5 bears and make sure they can attack
        Card bear1 = addCard(bears, attacker);
        Card bear2 = addCard(bears, attacker);
        Card bear3 = addCard(bears, attacker);
        Card bear4 = addCard(bears, attacker);
        Card bear5 = addCard(bears, attacker);

        // Make sure the bears can attack
        bear1.setSickness(false);
        bear2.setSickness(false);
        bear3.setSickness(false);
        bear4.setSickness(false);
        bear5.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        // Get the simulated game state after AI has declared attackers
        Combat combat = ((PlayerControllerAi)attacker.getController()).getAi().getPredictedCombat();

        // Check how many creatures are attacking
        int attackingCreatures = combat.getAttackers().size();
        AssertJUnit.assertEquals("AI should attack with all 5 bears for lethal", 5, attackingCreatures);
    }

    /**
     * Lethal on any opponent beats the threat-based choice of whom to attack. In a test match a flyer with enough
     * power to kill a player at 5 life attacked a healthier opponent instead, twice.
     */
    @Test
    public void assaultsTheOpponentItCanKill() {
        Game game = initAndCreateThreePlayerGame();
        Player weak = game.getPlayers().get(0);
        Player attacker = game.getPlayers().get(1);
        Player threat = game.getPlayers().get(2);
        weak.setTeam(0);
        attacker.setTeam(1);
        threat.setTeam(2);

        weak.setLife(3, null);
        // a much bigger threat, so the usual pick is the other opponent
        for (int i = 0; i < 6; i++) {
            addCard("Craw Wurm", threat).setSickness(false);
        }
        Card angel = addCard("Serra Angel", attacker);
        angel.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        Combat combat = ((PlayerControllerAi) attacker.getController()).getAi().getPredictedCombat();
        AssertJUnit.assertEquals("the flyer goes for the kill", weak, combat.getDefenderByAttacker(angel));
    }

    /**
     * A goaded creature must attack someone other than the goading player. When the AI's preferred defender is the
     * goader, its declaration must still be legal (the engine otherwise throws the whole plan away and declares only
     * the forced attackers).
     */
    @Test
    public void goadedCreatureAttacksSomeoneElse() {
        Game game = initAndCreateThreePlayerGame();
        Player goader = game.getPlayers().get(0);
        Player attacker = game.getPlayers().get(1);
        Player other = game.getPlayers().get(2);
        goader.setTeam(0);
        attacker.setTeam(1);
        other.setTeam(2);

        goader.setLife(4, null); // the obvious target
        Card bear = addCard("Grizzly Bears", attacker);
        bear.setSickness(false);
        bear.addGoad(game.getNextTimestamp(), goader);
        Card angel = addCard("Serra Angel", attacker);
        angel.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        playUntilPhase(game, PhaseType.COMBAT_DECLARE_BLOCKERS);

        Combat combat = game.getCombat();
        AssertJUnit.assertNotNull(combat);
        AssertJUnit.assertEquals(other, combat.getDefenderByAttacker(bear));
        AssertJUnit.assertEquals("the angel still goes for the kill", goader, combat.getDefenderByAttacker(angel));
    }

    /**
     * Encore tokens must each attack their own opponent; the AI's other attacks still stand. In test games the
     * tokens were sent at the AI's chosen defender, the declaration was illegal and the engine dropped the plan.
     */
    @Test
    public void encoreTokensAttackTheirOwnOpponent() {
        Game game = initAndCreateThreePlayerGame();
        Player weak = game.getPlayers().get(0);
        Player attacker = game.getPlayers().get(1);
        Player other = game.getPlayers().get(2);
        weak.setTeam(0);
        attacker.setTeam(1);
        other.setTeam(2);
        weak.setLife(3, null);
        Card angel = addCard("Serra Angel", attacker);
        angel.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        Card tyrant = addCardToZone("Broodmate Tyrant", attacker, forge.game.zone.ZoneType.Graveyard);
        forge.game.spellability.SpellAbility encore = tyrant.getAllPossibleAbilities(attacker, true).stream()
                .filter(sa -> sa.isKeyword(forge.game.keyword.Keyword.ENCORE)).findFirst().orElseThrow();
        encore.setActivatingPlayer(attacker);
        forge.game.ability.AbilityUtils.resolve(encore);
        playUntilStackClear(game);
        AssertJUnit.assertEquals("a token per opponent", 2, game.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                .filter(c -> c.isToken() && c.getName().equals("Broodmate Tyrant")).count());

        playUntilPhase(game, PhaseType.COMBAT_DECLARE_BLOCKERS);

        Combat combat = game.getCombat();
        AssertJUnit.assertNotNull(combat);
        java.util.Set<Object> tokenDefenders = new java.util.HashSet<>();
        for (Card c : combat.getAttackers()) {
            if (c.isToken() && c.getName().equals("Broodmate Tyrant")) {
                tokenDefenders.add(combat.getDefenderByAttacker(c));
            }
        }
        AssertJUnit.assertEquals("one encore token at each opponent", java.util.Set.of(weak, other), tokenDefenders);
        AssertJUnit.assertEquals("the angel still goes for the kill", weak, combat.getDefenderByAttacker(angel));
    }

    @Test
    public void assaultWhenAheadIncludesTrade() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        defender.setLife(10, null);

        String bears = "Grizzly Bears";

        Card bear1 = addCard(bears, attacker);
        Card bear2 = addCard(bears, attacker);
        addCard(bears, defender);

        // Make sure the bears can attack
        bear1.setSickness(false);
        bear2.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        // Get the simulated game state after AI has declared attackers
        Combat combat = ((PlayerControllerAi)attacker.getController()).getAi().getPredictedCombat();

        // Check how many creatures are attacking
        int attackingCreatures = combat.getAttackers().size();
        AssertJUnit.assertEquals("AI should attack with all 2 bears", 2, attackingCreatures);
    }

    @Test
    public void assaultWhenAheadAgainstDeathtouch() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        defender.setLife(10, null);

        String bears = "Grizzly Bears";
        String deathtouch = "Ankle Biter";

        Card bear1 = addCard(bears, attacker);
        Card bear2 = addCard(bears, attacker);
        Card bear3 = addCard(bears, attacker);
        addCard(deathtouch, defender);

        // Make sure the bears can attack
        bear1.setSickness(false);
        bear2.setSickness(false);
        bear3.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        // Get the simulated game state after AI has declared attackers
        Combat combat = ((PlayerControllerAi)attacker.getController()).getAi().getPredictedCombat();

        // Check how many creatures are attacking
        int attackingCreatures = combat.getAttackers().size();
        AssertJUnit.assertEquals("AI should attack with all 3 bears", 3, attackingCreatures);
    }

    @Test
    public void noAttackAgainstDeathtouchTrade() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        defender.setLife(10, null);

        String bears = "Grizzly Bears";
        String deathtouch = "Ankle Biter";

        Card bear1 = addCard(bears, attacker);
        addCard(deathtouch, defender);

        // Make sure the bears can attack
        bear1.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        // Get the simulated game state after AI has declared attackers
        Combat combat = ((PlayerControllerAi)attacker.getController()).getAi().getPredictedCombat();

        // Check how many creatures are attacking
        int attackingCreatures = combat.getAttackers().size();
        AssertJUnit.assertEquals("AI should not attack", 0, attackingCreatures);
    }
}
