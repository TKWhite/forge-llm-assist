# Standard AI improvements

A fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (release 2.0.15) that fixes flaws in Forge's own AI. These flaws were found while testing the LLM-assisted AI on the `game-plan` branch. Nothing here calls an external model: every fix is plain rules code in the standard AI, so every AI profile gets it.

| Area | Flaw | Fix |
|---|---|---|
| Mulligans (Commander) | The stock hand score counts zero-cost artifacts as lands and keeps most two-landers. | Commander hands are judged by lands and ramp. 0–1 lands: mulligan. 2 lands with no cheap ramp: take the free mulligan. 6+ lands: take the free mulligan, and always mulligan 7. Never go below 5 cards unless the 5-card hand has no lands. In test games this cut "under 4 land drops by turn 4" from about 21% to 8%. |
| Attacks | Only the threat-based opponent was checked for lethal. | Every opponent is checked, and an opponent who can be killed this combat is attacked. |
| Attacks | Encore tokens and creatures goaded by the chosen defender were sent at the wrong player. The declaration was illegal, so the engine threw the whole attack plan away. | Attack requirements are honoured first, and forced attackers go where their requirements allow. |
| Blocks | Against token swarms (Scute Swarm, hundreds of attackers), each block prediction ran out of time. One sim game took 45 minutes. | The lure check runs before the expensive block-cost scan. Chump and trade blocks are laid out once, then binary-searched for the shortest safe prefix. |
| Monarch | "Target player becomes the monarch" (Denethor, Stone Seer) never chose a target, so the costs were paid and the ability fizzled. | The AI crowns itself, or the opponent with the weakest board when only opponents can be targeted. |
| X damage spells | X was lowered to "just enough to kill the target" even when X also scales another effect. Worldsoul's Rage was cast at X=3 instead of 21. | X is only lowered when nothing else in the spell uses it. |
| Confluences | Charms that allow repeated modes needed three different useful modes, so Fiery Confluence was never cast against a board with no creatures or artifacts. | A safe mode is repeated (2 damage to each opponent ×3). Mass modes that also hit the AI's side are not repeated. |

## Testing

- Unit tests:
  - `forge-gui-desktop/src/test/java/forge/ai/` (`XAndModesAiTest`, `CommanderMulliganTest`, `BecomeMonarchAiTest`, `BasicAttackTests`, `BasicBlockTests`)
  - Run them with `mvn -pl forge-gui-desktop test -Dtest='forge.ai.**'`.
- Headless games:
  - Command: `forge sim -f Commander -D <deckdir> -d a.dck b.dck c.dck d.dck -n 1 -s <seed>`.
  - `-r` logs every player's hand at each first main phase. The game log never shows hidden cards.
