package forge.ai.ability;

import java.util.Map;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;

/**
 * GameAction.becomeMonarch returns immediately when the chosen player is already the monarch, so
 * an ability that would only crown the current monarch again does nothing at all. Cards such as
 * Throne of the High City and King Solomon's Frogs sacrifice or exile themselves as part of the
 * cost, so activating one redundantly throws a permanent away for no effect.
 */
public class BecomeMonarchAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        if (changesNothing(aiPlayer, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactGame);
        }
        if (sa.usesTargeting() && !chooseTarget(aiPlayer, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if (sa.usesTargeting() && !chooseTarget(aiPlayer, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message,
            Map<String, Object> params) {
        return !changesNothing(player, sa);
    }

    /**
     * Without this the targeted versions (Denethor, Stone Seer's "target player becomes the monarch") went on
     * the stack with no target after their costs were paid, and were thrown away. Crown yourself when allowed;
     * cards that can only crown an opponent (Jared Carthalion, Garland) give it to the opponent with the
     * weakest board, who is the easiest to take it back from.
     */
    private static boolean chooseTarget(Player ai, SpellAbility sa) {
        sa.resetTargets();
        if (sa.canTarget(ai)) {
            sa.getTargets().add(ai);
            return true;
        }
        Player best = null;
        int bestPower = Integer.MAX_VALUE;
        for (Player opp : ai.getOpponents()) {
            if (!sa.canTarget(opp)) {
                continue;
            }
            int power = 0;
            for (Card c : opp.getCreaturesInPlay()) {
                power += c.getNetCombatDamage();
            }
            if (power < bestPower) {
                best = opp;
                bestPower = power;
            }
        }
        if (best == null) {
            return false;
        }
        sa.getTargets().add(best);
        return true;
    }

    /**
     * True only when every player the ability would crown is already the monarch. Targeted
     * abilities are left alone, since some of them deliberately hand the crown to an opponent to
     * enable another effect on the same card.
     */
    private static boolean changesNothing(Player aiPlayer, SpellAbility sa) {
        if (sa.usesTargeting()) {
            return false;
        }
        final Player monarch = aiPlayer.getGame().getMonarch();
        if (monarch == null) {
            return false;
        }
        boolean anyAffected = false;
        for (Player p : getTargetPlayers(sa)) {
            anyAffected = true;
            if (!monarch.equals(p)) {
                return false;
            }
        }
        return anyAffected;
    }
}
