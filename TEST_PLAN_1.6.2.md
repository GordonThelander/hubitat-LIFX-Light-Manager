# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06), 1.5.18/1.5.19
(SCROLL-01/02/03, CLEANUP-01/02/03), and 1.6.1's Batch 1 GPT fixes (GPT01-01/02, GPT04-01/02, GPT11-01)
are all confirmed live and tracked in `BACKLOG.md` - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.2`.
2. App-file-only - no driver changes since 1.5.17.

## Batch 2 of the external ChatGPT review fixes (1.6.2) - confirmed live 2026-07-22

GPT03-01/02 pass live; GPT08-01 confirmed via code inspection/compile-check (no live device exercises
that path). Moved to `BACKLOG.md` under "Fixed and confirmed live (1.6.1-1.6.2)" - not yet backported
to `main`.
