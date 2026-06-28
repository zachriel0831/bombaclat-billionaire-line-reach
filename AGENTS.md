# AGENTS.md

## Scope

These instructions apply to `line-relay-service`.

## AI Operating Model

- Codex is the primary implementation and maintenance agent for this repo.
- Claude is a secondary assistant for review, explanation, or narrow follow-up work.
- `AGENTS.md` is the source of truth for agent behavior. `CLAUDE.md` should point here instead of duplicating rules.
- Before coding, read the smallest relevant set: this file, [README.md](README.md), and the specific doc/skill for the task.

## Global CTO Standards

Before implementation or verification work, read and follow `D:\work_space\claude-box\rules\rules\cto-technical-standards.md`. If this repo file and the global CTO standards conflict, follow the higher-priority rule and note the conflict.

## Git Commit And Push Workflow

- After implementation and verification, stage only task-related files, create a local commit, and push the current branch to its configured upstream remote without waiting for an extra prompt.
- If no upstream/remote exists, or push fails, report it clearly.
- Never push secrets, ignored runtime files, or unrelated dirty changes.

## Context Loading

Default:
1. [README.md](README.md)
2. Related source files

Load only when relevant:
- [docs/LINE_RELAY_FLOW.md](docs/LINE_RELAY_FLOW.md): webhook, push, scheduling, runtime commands, ngrok, troubleshooting.
- [skills/line-relay-service/SKILL.md](skills/line-relay-service/SKILL.md): repo workflow and coding guidance.
- [skills/line-relay-service/references/service-map.md](skills/line-relay-service/references/service-map.md): entry points, tables, data flow, schedule rules.
- [docs/SESSION_HANDOFF_2026-05-04.md](docs/SESSION_HANDOFF_2026-05-04.md): historical cross-service handoff context.

Do not preload all docs or skills.

## Hard Rules

1. Never operate outside `D:\work_space`.
2. Never charge, authorize, submit, test, save, or use a credit card or payment method.
3. Do not move LINE delivery, webhook ownership, or LINE push behavior into `data-collecting`, `news-platform-api`, `stock-monitor-service`, or `order-dispatcher-service`.
4. Do not print secrets from `.env`, LINE credentials, Redis credentials, MySQL passwords, or access tokens.
5. Respect local-development secrets as owner-approved local state. Before cloud migration, credentials must be reissued and moved to the chosen secret store.

## Workflow

1. Identify whether the task touches webhook, target sync, stock-query replies, scheduled pushes, Redis quota/cache, or ngrok/local ops.
2. Read the matching docs/skills above.
3. Keep edits narrow and update [README.md](README.md), [docs/LINE_RELAY_FLOW.md](docs/LINE_RELAY_FLOW.md), or the service map when behavior changes.
4. Run focused tests after code changes.

## Verification

Preferred local checks:

```powershell
.\mvnw.cmd test
```

For local runtime checks, use:

```powershell
.\scripts\start_line_relay_webhook_stack.ps1 -UpdateLineWebhook
Invoke-RestMethod http://localhost:8080/health
```

Use the stack script carefully: leave unrelated frontend/API/stock-monitor processes alone unless the user explicitly asks.
