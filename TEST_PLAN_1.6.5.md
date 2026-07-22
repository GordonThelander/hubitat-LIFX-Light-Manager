# Outstanding live-hub tests

Everything through 1.6.4 (all F-xx/GPT-xx findings, SCROLL/CLEANUP, WIFIKIND) is confirmed live and
tracked in `BACKLOG.md` - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.5`.
2. App-file-only - no driver changes since 1.6.3.

## Three requests rolled into one release (1.6.5, not yet confirmed)

- **Friendlier subnet prefix override example.** The Advanced > Network discovery field's example
  text now reads `192.168.1.` instead of `10.0.1.` - a more universally recognized private-network
  example. Mechanism unchanged (still a network prefix with a trailing dot, not a subnet mask).
- **Visible red validation error for a bad subnet override.** Previously a malformed override only
  logged a warning (easy to miss). Now the Advanced page itself shows a red error message right under
  the field - e.g. entering `notanip` or an out-of-range value like `999.999.999.` shows
  `'<value>' is not a valid subnet prefix - expected three numbers 0-255 separated by dots, e.g.
  192.168.1. Falling back to automatic detection until this is corrected.` The validation logic is now
  shared between this display and the actual `hubSubnet()` runtime fallback (`normalisedSubnetPrefix()`),
  so they can't drift apart - and it now also catches out-of-range octets (e.g. `999`), not just
  malformed strings, which the old regex-only check didn't reject.
- **Firmware check and WiFi signal check moved from hourly to once daily.** Discovery stays hourly
  (unchanged - it's the most time-sensitive of the three, detecting IP changes and new devices).
  Firmware check now runs once a day at 04:20, WiFi signal check once a day at 05:20. The
  "Background maintenance" section's description and the enabled/disabled status text were updated
  to describe the new mixed cadence accurately.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| SUBNET-01 | Friendlier example text | Open Advanced > Network discovery | The field's description shows `192.168.1.` as the example, not `10.0.1.` |
| SUBNET-02 | Invalid override shows a visible red error | Enter something that isn't a valid subnet prefix (e.g. `notanip`) in the override field, let the page refresh | A red error message appears directly under the field explaining it's invalid and that automatic detection is being used instead |
| SUBNET-03 | Out-of-range octets are also caught | Enter something IP-shaped but out of range (e.g. `999.999.999.`) | Same red error as SUBNET-02 - this used to silently pass the old validation and only fail later, now it's caught immediately |
| SUBNET-04 | A valid override shows no error | Enter your hub's actual subnet prefix (e.g. `192.168.1.`) | No red error appears; Discovery behaves identically to before |
| SUBNET-05 | Blank still behaves identically to before | Clear the override field entirely | No error shown, Discovery uses automatic detection exactly as before this release |
| SCHED-01 | Background maintenance status text reflects the new cadence | Enable background maintenance (or click "Apply background maintenance settings" if already enabled) | Status message reads something like "Background maintenance enabled: Discovery hourly at :05 past each hour, firmware check daily at 04:20, WiFi signal check daily at 05:20." |
| SCHED-02 | Firmware/WiFi checks no longer fire every hour | Leave background maintenance enabled and observe over a couple of hours (or check Hubitat's own scheduled-jobs list if accessible) | Discovery still fires hourly; firmware/WiFi checks do not fire again until their next daily time |
| SCHED-03 | Discovery is unaffected | Same observation window as SCHED-02 | Discovery still runs hourly, no change from before this release |
