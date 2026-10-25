# Minecraft Forge 1.20.1 Rules

## Context

- Project: SurvivalPoints (Forge 1.20.1, Java 17).
- Key File: `SurvivalPointsMod.java` (Main class / Registration).
- Project vision: `../../PROJECT_VISION.md`.
- Current project context: `../../AI_CONTEXT.md`.
- Project roadmap: `../../ROADMAP.md`.
- Task plans: `../plans/`.

Before planning or implementing a task, check the relevant project documentation and existing plans. Do not assume a planned feature is already implemented; verify the source code.

## Strict API Rules

1. Be concise. No apologies.
2. **MCP Usage:** Before implementing or recommending an unfamiliar Minecraft/Forge API, verify it using the `minecraft-modding` MCP server (`inspect-minecraft` or `analyze-symbol`) when available. Never guess API signatures.
3. **Version Lock:** Use only APIs compatible with Minecraft Forge 1.20.1. Never use methods or classes introduced in Minecraft 1.21+.
4. If the required MCP tools are unavailable, state the limitation instead of pretending verification succeeded.