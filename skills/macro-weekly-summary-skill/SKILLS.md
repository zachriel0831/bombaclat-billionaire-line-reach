---
name: weekly-macro-line-brief
description: Shared prompt guidance for LINE-readable weekly macro summaries and market-analysis briefs. Use only as a prompt-format reference; data-collecting owns generation and line-relay-service owns delivery.
---

# Weekly Macro Line Brief

## Purpose

Format market-analysis or weekly-summary text so it is readable in LINE and consistent with the financial news platform.

## Service Boundary

- `data-collecting` generates and stores analyses.
- `line-relay-service` delivers selected stored analyses through LINE.
- This file is a formatting reference, not an analysis pipeline owner.

## Output Rules

- Use compact sections and short paragraphs.
- Connect evidence, market mechanism, and Taiwan implication.
- Label data gaps explicitly.
- Do not invent arbitrary Taiwan ticker recommendations.
- Do not produce broker order instructions.

## Weekly Shape

1. Weekly macro
2. Next-week Taiwan allocation
3. Next-week watchlist

## Daily Shape

1. Macro regime
2. Rates and liquidity
3. Cycle and sentiment
4. Taiwan allocation
5. Risks and data gaps
6. Fixed-watchlist observations when available
