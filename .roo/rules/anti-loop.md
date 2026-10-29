# Anti-Loop Rules

## General Rules

- Do not repeat the same tool call, command, or action without a clear reason.
- Before retrying, inspect the previous result and identify what will be different.
- If two consecutive attempts produce the same error or no progress, stop and reassess the approach.
- Never repeat a failed action without changing the relevant input or strategy.
- If blocked, summarize what was tried, what failed, and the next recommended step.
- Preserve successful changes when stopping. Never claim completion without verification.
- Avoid unnecessary tool calls and repeated context inspection.
- Reuse information already available in the current context.
- Group related file edits, tool calls, and verification into one workflow.
- Do not restart analysis after every file edit or Git operation.
- Do not create extra agent turns or repeat analysis unless additional work is necessary.
- Stop when the task is complete and verified.

## Tool Call Rule: update_todo_list

- When calling the `update_todo_list` tool, provide exactly ONE argument named `todos`.
- The value must be a single Markdown-formatted string: `"- [ ] First task\n- [-] Second task\n- [x] Third task"`.
- CRITICAL: DO NOT use a `tasks` array. DO NOT output a JSON array. Use only the `todos` string.

## Provider Failure Handling

- If a model request fails because of a provider or API error, do not repeatedly retry the same request with the same provider without identifying a likely cause.
- After two consecutive provider failures, stop automatic retries and report the error, provider, and attempted recovery steps. The user may choose to switch providers and continue.
- Before switching providers, consider whether the new provider supports the current model, tools, context size, and request format.
- Do not automatically resend a large conversation to another provider after a failure. Inform the user that switching providers may resend the full context and increase token usage.
- Preserve the current task state and successful file changes when stopping after provider failures.