# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06), 1.5.18/1.5.19
(SCROLL-01/02/03, CLEANUP-01/02/03), and 1.6.1-1.6.2's Batch 1/2 GPT fixes (GPT01-01/02, GPT03-01/02,
GPT04-01/02, GPT08-01, GPT11-01) are all confirmed live and tracked in `BACKLOG.md` - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.4`.
2. App-file-only - no driver changes since 1.6.3 (which already needed all four local drivers
   re-uploaded; if you did that for 1.6.3, nothing further to re-upload for drivers here).

## WiFi Signal column mislabelling cached dBm readings as "(signal quality)" - confirmed live 2026-07-22

WIFIKIND-01/02 both pass. Moved to `BACKLOG.md` under "Fixed and confirmed live (1.6.1-1.6.4)".

## Batch 3 of the external ChatGPT review fixes (1.6.3) - confirmed live 2026-07-22

GPT05-01, GPT07-01, GPT10-01/02/03 all pass live; GPT09-01 confirmed via code inspection only (no
easy way to trigger it live without actually removing a device from the LIFX account). Moved to
`BACKLOG.md` under "Fixed and confirmed live (1.6.1-1.6.4)" - not yet backported to `main`.

All GPT-01 through GPT-11 external review findings are now either fixed-and-confirmed or
documented-as-accepted-limitations (GPT-02, GPT-06). Nothing outstanding from that review.

Three new requests logged to `BACKLOG.md` under "Open — enhancement ideas" (not yet implemented,
held per Gordon's instruction not to auto-ship releases): a friendlier subnet override example
(192.168.1. instead of 10.0.1.), a visible red validation error next to the subnet override field
for a malformed entry, and moving the scheduled firmware/WiFi checks from hourly to once-daily
(04:20/05:20) while leaving Discovery hourly.
