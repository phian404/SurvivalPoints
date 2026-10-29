# Git Workflow Rules

- Do not create Git commits automatically.
- Make a commit only when explicitly requested by the user.
- Do not repeatedly inspect Git status or diffs unless needed to verify the result.
- Use Conventional Commits when creating commits (e.g., `feat: add combat state`, `fix: resolve rendering bug`).
- Never push without my explicit confirmation.
- Grouping of related edits and verification: see `.roo/rules/anti-loop.md` (General Rules).
- Perform each necessary verification once after completing related changes. Repeat it only when the results are inconclusive or further changes could affect the outcome.