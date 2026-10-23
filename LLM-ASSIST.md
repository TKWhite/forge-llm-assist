# LLM assist for Forge AI opponents

A fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (based on release 2.0.15) where AI opponents hand a few hard decisions to an external model. Forge's own AI still does everything else. Supported models are TypeSafe's Jev and DeepSeek.

The model is only asked about decisions the standard AI handles badly:

| Hook | When it asks |
|---|---|
| Unsupported cards | Cards Forge flags as "AI can't play these cards well" (`AI:RemoveDeck:All`) or that have no AI logic: whether to use one now, and its target |
| Opponent focus | Several opponents look about equally threatening (Forge picks randomly) |
| Close targets | Removal-style single targets that score close together or belong to different opponents |
| Attack aggression | Once per AI turn, with 2+ possible attackers |
| Tutors | Non-land library searches |
| Board wipes | Second opinion before casting mass removal |
| Counterspells | Only against mana value 4+, commanders, wipes and tutors |
| Mulligan | First decision only, off by default |

Limits per AI player:
- nothing before round 4
- at most 3 calls per turn and 40 per game
- 4 s timeout
- answers cached for the turn

Any failure falls back to the standard AI, so games never stall. Calls never happen on Forge's timed AI evaluation thread.

## Game plans (`game-plan` branch): DeepSeek plans, Jev executes

The **DSeekPlan+JevAI** profile adds a planning step. DeepSeek reads the whole decklist once (commander plus 99 cards, each with condensed rules text) and writes a game plan:
- archetype and a short summary
- win conditions
- key cards
- cards to **hold** for the right moment, each with a condition for casting it
- early ramp priorities
- mulligan guidance
- which opposing threats to watch

Jev then makes the fast in-game decisions with that plan in mind:
- **Every prompt includes the plan:** a short "YOUR GAME PLAN" block.
- **Hold hook:** when the standard AI wants to cast a card on the hold list, Jev checks the card's condition ("is now the right moment?").
  - This applies from turn 1.
  - After `LLM_MAX_HOLD_TURNS` holds (default 3), it stops asking, so payoffs don't rot in hand.
  - If the model says hold, the AI looks for something else to do.
- **Mulligan:** the first keep decision uses the plan's land range and wanted cards.
- **Tutors:** prefer the plan's key cards even when no model call is made.
- **Confidence floor:** Jev only overrules the standard AI's own pick when it is at least `LLM_MIN_CONFIDENCE_PCT` (35%) confident.

Plans are cached as readable JSON in `%APPDATA%\Forge\llm-plans\<deck>-<hash>.json`:
- Only the first game with a deck makes the planner call (about 30–45 s with `deepseek-v4-pro`; the first mulligan waits up to `LLM_PLAN_WAIT_MS` for it).
- You can edit a plan by hand. Delete the file to regenerate it.
- A changed decklist gets a new plan.

The planner needs `DEEPSEEK_API_KEY`; the executor needs `TYPESAFE_API_KEY`.

## Setup

1. Set an API key as a user environment variable:
   - Jev: `setx TYPESAFE_API_KEY "..."`
   - DeepSeek: `setx DEEPSEEK_API_KEY "..."`
2. Build with JDK 17: `mvn -pl forge-gui-desktop -am package -DskipTests`.
3. Use `forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar` in a Forge install.
4. Copy `forge-gui/res/ai/DSeekPlan+JevAI.ai`, `JevAssisted.ai` and `LlmAssisted.ai` into the install's `res/ai`.
5. In Forge's AI settings, give opponents the **DSeekPlan+JevAI** (DeepSeek plan + Jev), **JevAssisted** (Jev) or **LlmAssisted** (DeepSeek) profile.

All settings live in those profile files (`LLM_*` keys, see `AiProps`).

## What you see

- **Status bar:** while an AI player waits on the model, a bar above the prompt shows what it's deciding, with a live timer.
- **Game log:** every decision is added as an `[AI assist]` line.
- **Call log:** `%APPDATA%\Forge\llm-assist.jsonl` records each call with Forge's pick, the model's pick, its confidence/reason, latency and token count.

## Code

- `forge-ai/src/main/java/forge/ai/llm/`:
  - `LlmGate`: budget, cache and fallback
  - `LlmAssist`: the hooks
  - `JevProvider` and `DeepSeekProvider`
  - `StateSerializer`: the game summary; opponents' hands stay hidden
  - `GamePlan` and `GamePlanner`: the per-deck plan and its disk cache
  - `LlmStatus`: tells the GUI when an AI is waiting on the model
- Hook call sites in `AiController`, `AiAttackController` and `PlayerControllerAi`.
- `forge-gui-desktop/.../match/LlmStatusIndicator.java`: the on-screen status bar.
- Tests:
  - `forge-ai/src/test/java/forge/ai/llm/`
  - `forge-gui-desktop/src/test/java/forge/ai/llm/`

Headless comparison: `forge sim -D <deckdir> -d a.dck b.dck c.dck d.dck -f Commander -n 10 -a JevAssisted Default JevAssisted Default`
