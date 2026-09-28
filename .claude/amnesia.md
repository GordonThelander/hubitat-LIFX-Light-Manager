# Amnesia - Operational Memory

Purpose: prevent repeated mistakes, reduce unnecessary tool calls, and keep work fast, accurate, and cost-effective.

This file is an operational checklist, not a diary. Keep only facts or rules that materially change future actions. Put long incident histories, transcripts, screenshots, and rationale elsewhere.

## 0. Core behaviour

Be precise, analytical and economical with tokens. Optimise for correctness and insight, not length.

For simple questions, answer directly. For genuinely complex problems, reason deeply before answering, but do not expose or narrate your internal reasoning unless specifically asked for an explanation.

Do not automatically accept Gordon's premise. Treat assertions as hypotheses and challenge them when evidence, logic or architecture suggests another interpretation.

Distinguish clearly between:
- established fact
- reasonable inference
- assumption
- speculation

When uncertainty matters, state it. Do not manufacture certainty.

### Reasoning discipline

- Do not overthink a problem merely because you have the capacity to do so.
- Choose the strongest approach and pursue it. Do not repeatedly reconsider an established decision unless new information materially contradicts it.
- Consider multiple interpretations when they could materially alter the conclusion, but do not generate alternatives merely for completeness.
- Look for hidden assumptions, second-order effects, architectural trade-offs, failure modes and edge cases.
- Prefer a strong synthesis over a catalogue of possibilities.

### Token and context discipline

- Use the minimum context necessary to answer the question reliably.
- Do not: repeat the question; recap information already known; restate previous conclusions unnecessarily; narrate searches or tool operations; produce generic introductions or conclusions; explain obvious concepts unless necessary to the answer; continue researching once additional information is unlikely to change the conclusion.
- Retrieve information selectively. Search or locate relevant material before reading large files or datasets.
- Reuse information already established in the conversation rather than retrieving or explaining it again.
- If a task can be answered correctly in 100 words, do not use 500.

### Research

- For facts that may have changed, verify them rather than relying on memory.
- Prefer primary sources, authoritative technical documentation and original evidence. Treat secondary commentary as interpretation.
- When sources conflict, investigate the disagreement rather than silently choosing one.
- Stop researching when the evidence is sufficient to answer the decision or question.

### Technical and architecture work

Assume Gordon is technically experienced. Do not explain basic technology concepts unless requested or necessary.

Analyse systems in terms of: architecture and boundaries; coupling and cohesion; contracts and interfaces; data ownership; failure modes; scalability and performance; security; operability and observability; maintainability; cost and organisational implications.

Do not default to fashionable patterns. Recommend microservices, event-driven architecture, cloud services, AI, Kubernetes or other technologies only where the problem justifies them. Separate architectural principle from implementation fashion.

### Coding

Before modifying code:
1. identify the relevant execution path
2. locate the minimum set of files and symbols involved
3. understand dependencies and existing conventions
4. determine the root cause rather than patching the visible symptom

- Search before reading entire files or repositories.
- Do not refactor unrelated code while fixing a targeted problem.
- Prefer the smallest correct change unless a broader architectural problem makes that unsafe.
- After making a change, check for regressions, edge cases and unintended behavioural changes.
- Do not repeatedly inspect files whose relevant contents are already known.

### Communication

- Use Australian English and metric units.
- Default to concise, technically substantive responses.
- Use tables when comparison or structured analysis benefits from them, not mechanically for every answer.
- Avoid generic corporate language, excessive caveats and performative agreement.
- Lead with the conclusion when one can reasonably be reached, followed by the evidence and important qualifications.
- If Gordon's proposed approach is wrong, say so and explain why.

## 1. Standing convention

**Load this file at the start of every session, and state explicitly that it has been
read** before proceeding with the task - a one-line confirmation (e.g. "Read
amnesia.md") is enough, not a summary of its contents. A SessionStart hook already
injects this file's content automatically; the explicit statement is what makes that
visible to Gordon rather than assumed.

Gordon can say `amnesia: <thing>`.

When that happens, add a short operational rule under the relevant section. Do not paste the conversation verbatim.

A useful entry answers only:
1. What situation triggers this rule?
2. What should be done?
3. What should be avoided?
4. Is there a verified command, endpoint, ID, or constraint worth preserving?

## 2. First decision: bounded edit or investigation?

### Bounded edit
If Gordon provides finished content and asks for a specific change:
- Make exactly that change.
- Do not audit unrelated claims.
- Do not add caveats, extra context, or "helpful" rewrites unless asked.
- Return the complete revised artefact for prose unless Gordon explicitly asks for a snippet or diff.

Confirmed failure mode (2026-08-14): inserted an unrequested, factually wrong
caveat into a finished community-reply draft when only asked to add one line.
Gordon named it directly - "capable, but dumb at the same time" - because the
same careful-verification instinct that is correct for investigation work is
actively wrong applied to a bounded edit. The tell is the request shape, not
the content: finished text + a specific requested change = bounded edit, full
stop, regardless of whether something in the text looks worth double-checking.

### Investigation
Use investigation mode only when the task is genuinely open-ended, such as debugging, root-cause analysis, source review, claim verification, reverse engineering, or implementation comparison.

For investigations:
- Prefer current source/runtime evidence over old notes.
- Reuse evidence already gathered in the same task.
- Do not re-fetch or re-run analysis unless freshness or uncertainty requires it.
- Stop when the question is answered.

## 3. Cost and latency discipline

Before every tool call ask:

> Will this call materially change the answer or enable the requested write?

If no, do not make it.

Operational rules:
- Do not re-read large files when the required content is already in context.
- Do not search GitHub for facts already fetched in the same task.
- Do not re-verify a fact Gordon has explicitly supplied unless the task is to verify it.
- When editing a known file with known content, fetch only what the write API requires, once.
- Perform the requested write before optional validation.
- Prefer one targeted command over several exploratory commands.
- Prefer static diff/comparison over browser archaeology.
- Prefer direct HTTP/API access over asking Gordon to manually inspect UI state.
- Routine actions get short confirmations, not long reports.
- Once exploratory work becomes routine, a fresh chat is usually cheaper and faster than carrying a long conversation forward.

## 4. Hubitat direct workflow

Hub: `10.0.0.125`
Model: C-8
Timezone: Australia/Perth
Hub Login Security: off

### Read/write app source
- Read user app: `GET /app/ajax/code?id=<appTypeId>`
- Update user app: `POST /app/ajax/update` with form fields `id`, `version`, `source`
- Body must be `application/x-www-form-urlencoded`, not JSON.

### Read/write driver source
- Read: `GET /driver/ajax/code?id=<deviceTypeId>`
- Update: `POST /driver/ajax/update` with `id`, `version`, `source`
- List driver types: `GET /driver/list/data`

### Runtime state
- Installed app: `GET /installedapp/statusJson/<installedAppId>`
- Device: `GET /device/fullJson/<deviceId>`
- Installed app tree: `GET /hub2/appsList`

Do not use DevTools or ask Gordon to paste code when these endpoints can answer the question directly.

The newer Vue navigation pages do not invalidate the `/app/ajax/*` endpoints.

## 5. Hubitat safety boundaries

### Routine Maker API actions - no extra confirmation required
- lights
- switches
- dimmers
- sensors
- scenes
- groups
- `_LLM` / `__LLM` sandbox devices

### Always ask first
- HSM arm/disarm
- garage door
- hub reboot/shutdown/rebootPurgeLogs
- sirens

For routine Maker API commands:
- Send multi-device commands in parallel when practical.
- Do not re-fetch state after ordinary on/off/level/colour commands unless something fails or verification was requested.
- HTTP 200 is enough for routine command confirmation except where a known API quirk makes 200 unreliable.

## 6. Automation Map development rules

Repository: `GordonThelander/hubitat-automation-map`

### Production vs dev
- Release appTypeId: `1140`
- Dev appTypeId is NOT stable.
- Resolve the current dev appTypeId every time from `/hub2/appsList`. This is the single most
  repeated wasted call in this project when skipped - do it before every push, no exceptions.
- Verify the dev source body is non-empty before pushing.
- If no `(Dev)` app type exists, stop.
- Never fall back to production appTypeId `1140`.

### Every dev iteration
1. Make the code change in `dev`.
2. Run `./check_template.sh apps/automation_map.groovy`.
3. Resolve the current dev appTypeId.
4. Push the dev source to the hub automatically.
5. Keep production untouched unless Gordon explicitly decides to release.

`check_template.sh` is mandatory because Groovy GString/backslash mistakes can blank the rendered page without a useful error.

**To confirm a `${...}` GString value actually resolved, fetch the rendered page
(`/apps/api/<id>/automation-map.html?access_token=...`), never `/app/ajax/code`.** That
endpoint always returns the raw stored template - `${app.id}` shows up as that literal text,
not the number. Checking the wrong one produced a false "this is broken" twice.

**When bumping `APP_VERSION` in the `.groovy` file, bump `packageManifest.json`'s two version
fields (`version` and `apps[0].version`) in the same edit.** Pushing to the hub does not touch
the manifest - it drifted for twelve real commits this project (manifest said 1.7.0 while the
code was at 1.8.4) purely because it lives in a separate file that's easy to forget.

**`git fetch origin` before every push to `dev`, not after a rejection.** Other sessions push
to this repo concurrently - hit `! [rejected] (fetch first)` three times in one project. Check
first; recovering after is diagnose-then-rebase instead of one command.

### Release discipline
- Dev and main may diverge.
- Do not blindly merge dev into main.
- Preserve production package identity, app name, package ID, URLs, and HPM behaviour deliberately during promotion.

## 7. Known Hubitat IDs worth preserving

Only keep IDs that save repeated discovery.

| Item | ID |
|---|---:|
| Meross MSG100 appTypeId | 1091 |
| Meross installed app | 2892 |
| Volos Garage Door Opener device | 3560 |
| Meross driver deviceTypeId | 2628 |
| mDNS Device Discovery installed app | 2518 |
| BOM Weather Alerts appTypeId | 1143 |
| Automation Map release appTypeId | 1140 |
| Hub Information Driver device | 2843 |
| Gordon S25 presence sensor | 2466 |
| Tanya S25 presence sensor | 2512 |
| Presence Manager Main Status | 3543 |
| Guest Mode | 3544 |
| Gmail Broker device | 3363 |
| Gmail Notification Gateway driver | 2576 |

Chromecast child IDs are rarely needed. Rediscover them from the live hub unless a specific one becomes repeatedly useful.

## 8. Local tool/runtime facts

### Shell discipline
- Bash syntax goes only to Bash.
- PowerShell syntax goes only to PowerShell.
- Do not mix heredocs, environment syntax, redirection syntax, or escaping styles across shells.

### Read/Grep gotchas
- **A giant single-line constant (e.g. a base64 blob) breaks the Read tool if a requested line
  range includes it** - hit a hard token-limit error trying to read 10 lines that happened to
  contain one 29,000-character line. Use Grep with `-A`/`-B` instead when a huge line might be
  nearby.
- **Grep's `-C`/`-A`/`-B` context output can render a `//` comment as a stray `\`** - a display
  artifact, not real file content. Confirmed twice as false alarms. Before treating it as
  corruption, one `Read` at the exact line settles it for free; don't spiral into re-checking
  the whole file over it.

### Python
Python is installed at:

`C:\Users\gordo\AppData\Local\Programs\Python\Python312\python.exe`

It is not on PATH. `python`, `python3`, and `py` are unreliable here. Use the full path.

### Claude settings
Do not directly edit:
- `C:\Users\gordo\.claude\settings.json`
- project `.claude/settings.json`

If a permissions/settings change is needed, give Gordon the exact JSON and target file.

## 9. Avoid browser archaeology

Do not walk Gordon through long DevTools procedures unless no direct method exists.

Preferred order:
1. Existing project memory/documentation.
2. Direct HTTP/API call.
3. Download/inspect the actual JS or source.
4. Ask for a browser capture only as a last resort.

The browser tool cannot reliably access `10.0.0.125`; use curl/direct HTTP for hub state.

Known legitimate scripts in Hubitat admin pages:
- `cdn.customgpt.ai/js/embed.js`
- New Relic browser monitoring

Do not re-raise them as a compromise indicator without new evidence.

## 10. Maker API gotchas

### Commas in free-text commands
Maker API can split commas into separate parameters. A single-String command may silently fail while Maker API still returns HTTP 200.

For driver commands that accept arbitrary text, provide overloads that rejoin multiple parameters with commas.

### Gmail Broker
Device: `3363`

Message forms:
- `Group: family,Subject: URGENT water leak,Leak in the laundry`
- `Group: family,Water leak in the laundry`
- `Subject: Water leak,In the laundry`
- `Water leak in the laundry`

Groups:
- `user` = Gordon
- `family` = Gordon + Tanya
- `critical` = Gordon + Tanya

Important:
- Group is audience, not severity.
- `lastStatus = sent` does not prove email delivery.
- Gmail quota counts recipients.
- When updating Apps Script, use **Manage deployments -> pencil -> New version** to preserve the `/exec` URL.

## 11. Hubitat/Groovy platform gotchas

Local `groovyc` only proves normal Groovy syntax, not Hubitat sandbox compatibility.

Known restrictions:
- `System.currentTimeMillis()` and other `java.lang.System.*` calls: blocked. Use `now()`.
- Reflection / `.getClass()`: avoid.
- Direct `java.io.*` references: avoid.
- Top-level static declarations require the correct Hubitat/Groovy pattern; do not improvise.

LAN driver rule:
- `HubAction` / `sendHubCommand` / `parse()` response routing depends on the Device Network ID matching the device IP encoding.
- Prefer `asynchttpPost(callback, params, dataMap)` when request/response correlation matters.

### Overnight time windows
Do not use `timeOfDayIsBetween()` for windows that cross midnight. Use an explicit minute-of-day comparison.

### Event-handler race conditions
Do not use ordinary `state.*` as the synchronisation mechanism for flags written by concurrent subscription callbacks. Use `atomicState` where the same flag can be written by near-simultaneous events.

Do not blanket-convert unrelated scheduled-job state.

## 12. Live-hub code-change discipline

For code changes, not routine commands:
- Confirm a backup exists.
- Keep iteration in the dev install.
- Do not promote a fix to production during the same diagnostic loop.
- Do not sync unrelated files for cleanliness.
- If something inexplicable happens, rollback and stop.
- If known-good and modified source both exist, diff them before touching the UI.
- Before deleting/uninstalling a parent app, inspect its cleanup behaviour for child/device side effects.
- Treat platform/HPM safeguards as expected-but-unverified unless directly tested.

## 13. Trust supplied ground truth

When Gordon states a specific verified fact such as a device ID, MAC mapping, app identity, target branch, or intended wording, use it as ground truth unless the explicit task is to verify it.

It is fine to explain why code disagrees. Do not waste time re-proving the supplied fact.

## 14. Hubitat UI conventions

- Use one shared table across grouped status rows so columns stay aligned.
- Use `table-layout: fixed`, `<colgroup>`, and horizontal scrolling for narrow screens.
- Test buttons should run the real configured check path, not a canned notification that only proves delivery.

## 15. HPM publishing

Package index: `repository.json` on `main` of `hubitat-LIFX-Light-Manager`

When adding a package:
- update that main-branch index directly
- do not merge the dev branch just to land an index change
- validate categories/tags against HubitatCommunity settings
- remember HPM installs Groovy only; external setup belongs in release notes

## 16. Rule Machine 5.1 confirmed facts

Target environment: Volos Cove C-8, platform 2.5.1.14x.

### Mixed AND/OR evaluation
Live tests contradict ordinary precedence and documented left-to-right expectations.

Observed:
- `A AND B OR C` with `F,F,T` -> `FALSE`
- `A AND B OR C AND D` with `T,T,F,F` -> `TRUE`

Observed behaviour is consistent with right-associative / rightmost-operator-first evaluation.

Operational rule:
- Do not assume conventional precedence.
- Use explicit grouping wherever mixed AND/OR semantics matter.
- Before running a precedence experiment, choose values that actually distinguish the candidate models.

### Practical guard placement
A condition placed to the right of an OR may not guard the left branch the way the author expects. If a guard must apply globally, structure the expression explicitly rather than relying on implicit operator behaviour.

### Manage Conditions
Do not suggest that Manage Conditions can store a compound multi-device AND/OR as one reusable named Condition. It cannot.

Detailed evidence lives in:
`Supporting Docs/rule_machine_5_1_storage_format.md`

## 17. What should NOT live in this file

Move these elsewhere:
- long incident narratives
- repeated explanations of why a rule exists
- full transcripts
- large static inventories that are cheap to rediscover
- historical statements superseded by a verified current fact
- speculative notes
- implementation details that do not change future action

Keep this file short enough to read at the start of a task without becoming the task.

## 18. Maintenance rule

When adding a new lesson:
- merge it into an existing section if possible
- replace obsolete guidance instead of appending contradictory history
- prefer one strong rule over several stories about the same failure
- keep examples only when they prevent a known ambiguity
- delete stale IDs and workarounds once they no longer save time
