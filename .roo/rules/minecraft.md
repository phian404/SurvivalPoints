# Minecraft Forge 1.20.1 Rules

## Context

- Project: SurvivalPoints (Forge 1.20.1, Java 17).
- Key File: `SurvivalPointsMod.java` (Main class / Registration).
- Project vision: `.roo/plans/Architecture/PROJECT_VISION.md`.
- Current project context: `src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md`.
- Project roadmap: `.roo/plans/Roadmap/ROADMAP.md` (canonical wave order and priorities).
- NPC architecture (canonical, implemented): `.roo/plans/Entities/plan.md`.
- NPC and camp simulation target model: `.roo/plans/Entities/NPC_CAMP_SIMULATION_DESIGN.md`.
- Task plans: `.roo/plans/` (topic subfolders: `Camp/`, `Entities/`, `Combat/`, `Items/`, `Roadmap/`, `Architecture/`, `Testing/`).
- Documentation entry point: `.roo/plans/README.md` (pick the single document matching the task type; do not read all files in a row).
- Source assets (not loaded by the game): `assets-source/ReadyToUseSkins/` (14 regular NPC skins in `BasicNPCs/`, 4 leader skins in `Leaders/`, leader lore in `SurvivalPoints_Leaders.md`). Runtime copies live in `src/main/resources/assets/survivalpoints/textures/entity/skin/`.

Before planning or implementing a task, check the relevant project documentation and existing plans. Do not assume a planned feature is already implemented; verify the source code.

## Response Style

1. Be concise. No apologies.

## API Version Lock

1. Use only APIs compatible with Minecraft Forge 1.20.1. Never use methods or classes introduced in Minecraft 1.21+.

## Verification Policy

1. **Vanilla Minecraft API:** before implementing or recommending an unfamiliar vanilla API, verify it using the `minecraft-modding` MCP server (`inspect-minecraft` or `analyze-symbol`) when available.
2. **Forge API:** the `minecraft-modding` MCP server does not index Forge classes. Verify Forge signatures against the Forge sources in the Gradle cache instead.
3. Never guess API signatures. If neither source can be checked, state the limitation explicitly instead of pretending verification succeeded.