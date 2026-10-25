# Code Style & Editing Rules

## Java Standards
- Use 4 spaces for indentation.
- Use explicit imports (e.g., `import net.minecraft.world.item.Item;`), never fully qualified names in code.
- Follow standard Java naming conventions.
- Keep existing comments and `@Override` annotations intact.
# Tool Call Rule: update_todo_list
When calling the `update_todo_list` tool, you MUST provide exactly ONE argument named `todos`.
The value must be a single Markdown-formatted string.
Example:
"todos": "- [ ] First task\n- [-] Second task\n- [x] Third task"

CRITICAL: DO NOT use a `tasks` array. DO NOT output a JSON array. Use only the `todos` string.