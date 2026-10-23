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

## Setup

1. Set an API key as a user environment variable:
   - Jev: `setx TYPESAFE_API_KEY "..."`
   - DeepSeek: `setx DEEPSEEK_API_KEY "..."`
2. Build with JDK 17: `mvn -pl forge-gui-desktop -am package -DskipTests`.
3. Use `forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar` in a Forge install.
4. Copy `forge-gui/res/ai/JevAssisted.ai` and `forge-gui/res/ai/LlmAssisted.ai` into the install's `res/ai`.
5. In Forge's AI settings, give opponents the **JevAssisted** (Jev) or **LlmAssisted** (DeepSeek) profile.

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
  - `LlmStatus`: tells the GUI when an AI is waiting on the model
- Hook call sites in `AiController`, `AiAttackController` and `PlayerControllerAi`.
- `forge-gui-desktop/.../match/LlmStatusIndicator.java`: the on-screen status bar.
- Tests:
  - `forge-ai/src/test/java/forge/ai/llm/`
  - `forge-gui-desktop/src/test/java/forge/ai/llm/`

Headless comparison: `forge sim -D <deckdir> -d a.dck b.dck c.dck d.dck -f Commander -n 10 -a JevAssisted Default JevAssisted Default`
