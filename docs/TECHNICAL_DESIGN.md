# COC AI Agent — Technical Design (MVP)

## Product definition

COC AI Agent is an Android assistant for reading and prioritizing the built-in Clash of Clans **Profile → Achievements** list. It uses DeepSeek Vision and an opt-in Accessibility service for limited navigation: open the player profile, select the three Achievement tabs, and scroll the confirmed list.

It is not a full gameplay bot. It does not automate battles, select opponents, train or deploy troops, cast spells, modify memory, inject code, or interact with the game server.

## In scope

The scanner recognizes and reads every achievement in these three profile tabs:

1. Home Village (Russian UI: «Родная деревня»)
2. Builder Base (Russian UI: «Деревня строителя»)
3. Clan Capital (Russian UI: «Столица клана»)

It automatically scrolls long lists, detects when the visible page stops moving, cycles through all three tabs, and merges overlapping pages without creating duplicate tasks.

## Explicitly out of scope

- Events and Event Missions
- Season Challenges / Season Pass tasks
- Clan Games
- Clan War / CWL task automation
- Battle automation, troop deployment, spell use, opponent selection, or strategic gameplay actions. Only profile-entry/tab-selection taps and confirmed-list scroll gestures are included.
- Reading or controlling any app other than the allowlisted Clash of Clans package

Achievements are cumulative progress objectives, not ordinary quests with an accept/reject button. The app therefore recommends which achievement to work toward; it does not claim to accept or reject an achievement inside the game.

## Target device

- Primary test device: Samsung Galaxy A35 5G
- Android version: Android 16 (API 36)
- Kotlin native Android app
- Native Android Views for the app UI
- Gradle build and JVM safety tests in GitHub Actions to produce a debug APK; release signing is not configured

## Main user flow

1. User opens COC AI Agent and saves a DeepSeek API key.
2. User grants Usage Access, Display over other apps, and enables the app's Accessibility service once in Android Settings.
3. User taps the single **START** toggle.
4. Android asks for fresh MediaProjection consent for this capture session.
5. The app starts Clash of Clans and begins screen classification.
6. When DeepSeek confirms the main village, Accessibility taps the player-profile badge.
7. When DeepSeek confirms Profile → Achievements, it reads the visible rows and automatically scrolls.
8. After two scroll attempts with no detected screen movement, it selects the next Achievement tab. It scans all three tabs and stops after returning to the tab where the session began.
9. User taps **STOP** at any time. Capture and AI requests are cancelled, the toggle changes back to **START**, and the Achievement database is preserved.

The Accessibility service is limited to navigation gestures. The user must enable it once; Android still requires a new screen-capture consent for every MediaProjection session. The app does not play battles or earn combat achievements by itself.

## Game allowlist and foreground guard

- Allowlist the package ID `com.supercell.clashofclans`; do not accept arbitrary package names.
- Declare only this package in the manifest `<queries>` element.
- Do not request `QUERY_ALL_PACKAGES`.
- Before processing a frame or sending it to the AI provider, verify that the allowed game is the foreground app.
- If the user switches to another app, pause analysis and do not send that screen to the provider.
- The exact foreground-detection mechanism must be validated on the A35. Usage Access is used for package-state detection. The separate Accessibility service is filtered to the Clash package and only dispatches the fixed navigation gestures described above.
- If the app cannot confidently verify that Clash is foreground, it must pause rather than guess.

## Screen capture and floating control

Use Android MediaProjection for screen capture and a foreground service with the `mediaProjection` service type.

Important Android constraints:
- Ask for the user's screen-capture consent for every new capture session.
- Treat each MediaProjection token as single-use; do not reuse it to create another virtual display.
- Register the projection stop callback and release the virtual display, surface, bitmap buffers, and service resources on stop.
- Keep a visible foreground-service notification with a Stop action.
- Request `SYSTEM_ALERT_WINDOW` only for the floating control; explain the permission and provide a settings link.
- Start monitoring from a visible user action. Do not auto-start capture on boot.
- The overlay must be draggable, small, translucent, and positioned away from Achievement progress bars. It exposes one state-aware START / STOP toggle.
- If overlay permission is revoked or Android stops the service, show a clear status and fallback notification/app control.

## Screen recognition pipeline

1. Verify the foreground package.
2. Classify the current frame as Village Home, Player Profile, confirmed Achievement page, or Other using DeepSeek Vision.
3. Detect which of the three achievement tabs is selected.
4. Crop the screen while retaining the upper-left player badge and the central Achievement panel.
5. Wait for a stable frame after tab changes or scrolling.
6. Use DeepSeek Vision to parse the visible Russian Achievement rows and their numeric progress.
7. Validate screen type, selected tab, title, progress ranges, and confidence before saving.
8. Store normalized tasks in the local deduplicated database.

Do not analyze Event, Season, Clan Games, or other screens. If the Achievement page is not positively identified, ignore the frame.

## Achievement data model

Each record should contain:

- `tab`: HOME_VILLAGE, BUILDER_BASE, or CLAN_CAPITAL
- `title`
- `description`
- `current_progress` (nullable)
- `target_progress` (nullable)
- `remaining_progress` (derived only when both values are valid)
- `xp_reward` (nullable)
- `gem_reward` (nullable)
- `star_count` (nullable)
- `is_completed`
- `confidence` for OCR / vision extraction
- `last_seen_at`

Use a stable deduplication key based on tab + normalized title + normalized description, not the row's screen position.

## Recommendation engine

Do not let a language model invent progress values or make final arithmetic decisions.

The deterministic layer calculates:
- Remaining progress = max(target - current, 0)
- Completion state from the visible completion marker and/or validated progress
- Reward efficiency where reward values are available
- Historical pace using observed progress deltas across scans, when enough history exists

The ranking layer can consider:
- How close the achievement is to completion
- Estimated time / effort, when there is enough history
- Reward value
- User's manually entered player profile, if the user chooses to provide it
- Extraction confidence

If there is not enough information to estimate feasibility or time, show **Unknown / needs more data** instead of fabricating a confident estimate. A precise combat-power estimate requires additional player/army data; the achievement page alone does not provide it.

Every recommendation must include a short explanation, for example: “35 remaining; high observed progress rate; moderate confidence.”

## DeepSeek integration

- Use the current vision-capable model name from the official provider documentation (currently `deepseek-flash`).
- Send cropped achievement images only, not continuous full-screen video.
- Call the API only when the image changes materially or local extraction confidence is low.
- Request a strict JSON response and validate every field locally.
- Never accept model-provided arithmetic without rechecking it.
- Handle missing API key, invalid key, no credit, rate limits, timeouts, offline state, and provider errors without crashing.
- Keep a local-only analysis mode available if the user has not entered an API key.

## API key protection

Every user enters their own DeepSeek API key in Settings. The repository and APK must contain no real key.

- Store the key encrypted using a key held by Android Keystore.
- Never write the key to Git, logs, analytics, crash reports, screenshots, or backup files.
- Use HTTPS only.
- Mask the key in the UI and provide Delete / Replace controls.
- Do not log request headers or authorization values.
- Explain that client-side storage is not absolutely secret: a rooted or instrumented device, or a compromised app process, may expose a key. Users must be able to revoke and rotate it.
- The user must be told that images sent to DeepSeek leave the phone and are processed by the provider. Default to cropping the achievement panel and not retaining raw screenshots.

## Reliability and glitch prevention

### OCR and visual recognition
- Support Russian numeric formatting, including spaces and non-breaking spaces (for example, `8 946`).
- Do not assume a fixed screen resolution or row height.
- Use anchors and relative layout regions, not hard-coded absolute pixel coordinates.
- If title, progress, target, or reward is unclear, mark that field unknown and lower confidence.
- Use multiple visual signals for completion (green check, stars, and progress) rather than trusting one signal alone.
- If the game language is unsupported, pause with a clear message instead of producing guessed task names.

### Scrolling and duplicates
- Process after scrolling has settled.
- Use overlapping captures to avoid skipping rows.
- Deduplicate by stable task key.
- Detect the end of a list by repeated anchors / no new records across successive settled frames.
- Never overwrite a previously high-confidence value with a lower-confidence uncertain reading without showing a conflict.

### AI and network failures
- Debounce screen changes; do not send a request for every video frame.
- Cancel stale requests when the user changes page or presses Stop.
- Use bounded retries with backoff; do not retry indefinitely.
- Handle 401 (bad key), 429 (rate limited), timeout, offline, and 5xx separately.
- Cache results for an unchanged screen using an image hash.
- Keep the last valid local scan if network access is lost.

### Android lifecycle and Samsung behavior
- Stop capture on user Stop, screen lock, projection revocation, service failure, or confirmed departure from Clash.
- Release all buffers and services in every exit path.
- Use a foreground notification while capture runs.
- Do not promise uninterrupted monitoring if Android or Samsung battery management stops the process.
- Provide optional user guidance for battery settings; never change system security or battery settings automatically.

### Privacy and permissions
- Request only the permissions that are actually needed.
- No camera, microphone, contacts, location, or broad all-app visibility permission.
- Explain Overlay, Screen Capture, and any required Usage Access permission before opening system settings.
- Never capture or send frames while another app is foreground.
- Store structured achievement records locally; raw screenshot retention is off by default.

## Test plan

- Implemented JVM tests currently verify that game-input automation and Gem spending remain disabled.
- Planned tests: number parsing, progress arithmetic, completion detection, stable IDs, and ranking.
- Planned screenshot fixtures: the supplied Russian Achievement screen, all three tabs, completed rows, partially completed rows, and multiple scroll positions.
- Planned negative tests: Event / Season / Clan Games screens must be rejected.
- Planned API tests: missing / invalid key, DNS failure, offline mode, timeouts, 429, and malformed JSON. The in-app GET /models diagnostic is implemented separately.
- Planned lifecycle tests: permission denial, process recreation, screen lock, overlay revocation, app switch, rotation, and STOP.
- Device validation on Samsung Galaxy A35 5G / Android 16 remains required before calling the APK stable.

## MVP acceptance criteria

- The only addable game is the allowlisted Clash of Clans package.
- The scanner processes only the three Profile → Achievements tabs.
- Event, Season, and Clan Games screens are ignored.
- Visible rows accepted by the model are normalized with a confidence value; low-confidence rows are discarded.
- Repeated sightings of the same tab/title are merged. The user must scroll slowly enough for the stable-frame scanner to inspect each visible section; skipping due to fast scrolling remains possible.
- The ranking includes a numeric priority based on progress, visible Gem reward, XP reward, confidence, and historical rate where available; this is an estimate, not a guaranteed best-task decision.
- STOP releases capture resources and cancels the active API request while preserving the task database.
- No real API key is present in the repository or build artifacts.
- The app never performs automated game actions.

## Fair Play and policy boundary

This project is designed as a passive, read-only screen-analysis assistant. It must not automate gameplay or modify/interfere with the game. However, read-only behavior does not guarantee that Supercell considers a third-party overlay or achievement-analysis app authorized. Supercell's published Terms and Fair Play policy restrict unauthorized third-party software and automation. Before public distribution or live-game use, the project owner should review the current policies and, if certainty is required, obtain clarification from Supercell. Do not advertise the app as officially approved or ban-proof.


## Current MVP implementation notes (2026-11-24)

The first Android source drop now implements:
- Native Kotlin Android app with a fixed Clash of Clans package check.
- MediaProjection consent flow and a screen-capture foreground service.
- Frame processing throttled to a maximum of 30 frames/second.
- One floating state-aware START / STOP toggle; STOP preserves the saved Achievement database.
- Usage Access foreground-package check; analysis is paused unless the latest foreground activity belongs to the allowlisted Clash package.
- DeepSeek Vision automatically analyzes a stable changed frame, with a minimum interval between requests (not one remote API request per video frame).
- Central achievement-panel crop before the image is sent to the provider.
- AES-GCM encryption of the user's key using Android Keystore.
- A navigation-only Accessibility gesture service; strategic gameplay and Gem spending remain disabled.

The current MVP samples frames up to 30 FPS, samples a central 56x34 luminance grid and compares average image differences to ignore small edge animations, waits for a stable materially changed frame, and automatically sends a cropped candidate image to DeepSeek with a 3.5-second minimum request interval. DeepSeek first classifies the screen as the main village, player profile, Achievement page, or other. When the main village is confirmed, the app taps the player-profile badge; when the Achievement page is confirmed, it merges validated rows into a durable local database and schedules an upward swipe. A changed list page is then analyzed and merged. Automatic scrolling is capped at 60 gestures per session. After two consecutive swipes with no detected screen movement, the service assumes the current tab has reached its end, selects the next tab, and scans until it returns to the initially selected tab. The database and progress observations survive STOP, app closure, and later START. Local OCR and a complete combat-power feasibility model are not implemented. The app does not play battles or deploy troops. A basic completion-time estimate is derived from at least two observed progress increases separated by time; it is a rough historical-rate estimate, not a guaranteed prediction.

### User-driven extra context

If the AI needs a building or troop level to estimate whether an achievement is feasible, it may explain exactly what information is missing and ask the user to open that screen manually. This MVP taps only the player-profile badge after DeepSeek classifies the foreground screen as the main village, then uses Accessibility swipe gestures only after DeepSeek confirms the Achievement page. The user must enable the Accessibility service once and grant screen capture consent at each session. The initial profile tap and all three tab-selector tap points are calibrated from the supplied 1536x864 landscape screenshot and require physical-device confirmation. The current build does not automate battles, troop deployment, spell use, purchases, upgrades, or Gem spending. The achievement task parser itself rejects building-detail, army, shop, Events, Season Challenges, and Clan Games screens.

### Non-negotiable Gems lock

- No game input injection, Accessibility click actions, coordinate taps, UIAutomator, ADB control, or game-control API is included.
- No purchase, upgrade, boost, instant-finish, or spending interaction is implemented.
- Gems are parsed only as visible achievement rewards.
- No action capable of spending Gems is implemented; the current Accessibility layer is navigation-only.

### Frame-rate and provider-cost policy

The capture pipeline processes frames at up to 30 FPS and stores only the latest in-memory frame. It does not upload a video stream. A DeepSeek request occurs only after a stable screen change and is rate-limited to one per 3.5 seconds; the full 30 FPS stream is never uploaded. A separate GET /models diagnostic checks the API key and confirms that deepseek-flash advertises image input without consuming a vision inference.

### Build status

The GitHub Actions workflow is configured to run `assembleDebug` and upload an APK artifact. Unit tests and the GitHub Actions build must pass; physical-device validation on the Samsung Galaxy A35 5G / Android 16 is still required before calling the APK stable.


## Audit findings and mitigations

- Exact full-frame hashes were too sensitive to small animation changes; the scanner now compares a central 56x34 luminance grid and verifies whether an automated swipe materially changed the visible Achievement page.
- Stale in-flight Vision requests are disconnected on STOP and their results are discarded when their capture-session generation is no longer current.
- The saved active flag is cleared on service process recreation because Android MediaProjection cannot be restored after process death; the Achievement database is not cleared.
- Usage Events processing now handles both foreground/resume and background/pause events. Capture pauses when Clash is not the verified foreground package, the device is locked, or the display is off.
- DNS lookup, socket timeout, HTTP authorization, rate-limit, and provider-server errors are surfaced as distinct user-facing diagnostics.
- Vision output rows with low confidence, negative progress, or invalid titles are not inserted into the durable task database.
- Database writes use synchronous commit before the result is reported as saved.
- Ranking now considers progress completion ratio, visible Gem reward, XP reward, confidence, and historical completion rate where enough progress observations exist.
- A unit-test suite verifies that gameplay actions and Gem spending remain disabled. It does not test the Accessibility gestures; those require physical-device validation.

## Accessibility gesture service

- Requires one-time manual activation in Android Accessibility settings.
- Does not retrieve the view hierarchy or read other apps' text; it uses the Android dispatchGesture capability.
- Main-village profile tap is calibrated at approximately 6.6% of screen width and 6.8% of screen height.
- Achievement tab selectors are calibrated at x=29.2%, 50.1%, and 70.3%, y=14.6%.
- Achievement scrolling is a vertical swipe at x=50%, from y=74% to y=32%.
- The fixed gesture coordinates are based on the supplied 1536×864 landscape screenshot. They require physical validation on the target phone and may need recalibration if the game's UI layout or screen aspect ratio differs.
- Scroll completion is inferred from two consecutive swipe attempts that do not materially change a 56×34 central luminance signature. If Android or the game rejects a gesture, the controller may misinterpret it as the end of the list; verify all three tabs on-device.