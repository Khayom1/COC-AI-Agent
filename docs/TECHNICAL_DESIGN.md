# COC AI Agent — Technical Design (MVP)

## Product definition

COC AI Agent is a read-only Android assistant for reviewing and prioritizing the built-in Clash of Clans **Profile → Achievements** list.

It is not a game bot. It does not automate battles, deploy troops, tap game controls, accept in-game actions, modify memory, inject code, or interact with the game server.

## In scope

The scanner recognizes and reads every achievement in these three profile tabs:

1. Home Village (Russian UI: «Родная деревня»)
2. Builder Base (Russian UI: «Деревня строителя»)
3. Clan Capital (Russian UI: «Столица клана»)

It must support long lists by scanning as the user scrolls, and it must merge overlapping pages without creating duplicate tasks.

## Explicitly out of scope

- Events and Event Missions
- Season Challenges / Season Pass tasks
- Clan Games
- Clan War / CWL task automation
- Battle automation, troop deployment, spell use, or automated game taps
- Reading or controlling any app other than the allowlisted Clash of Clans package

Achievements are cumulative progress objectives, not ordinary quests with an accept/reject button. The app therefore recommends which achievement to work toward; it does not claim to accept or reject an achievement inside the game.

## Target device

- Primary test device: Samsung Galaxy A35 5G
- Android version: Android 16 (API 36)
- Kotlin native Android app
- Jetpack Compose for the app UI
- Gradle build in GitHub Actions to produce a signed/release-ready APK

## Main user flow

1. User opens COC AI Agent.
2. User taps **+ Add Game**.
3. The app checks only the known Clash of Clans package: `com.supercell.clashofclans`.
4. If that package is installed, the app shows one fixed game card. It does not show a general installed-app picker and does not request `QUERY_ALL_PACKAGES`.
5. User launches Clash of Clans from that card.
6. User opens Profile → Achievements manually.
7. User grants screen-capture permission when starting a new monitoring session.
8. User taps the floating **START** control. The scanner reads the currently visible achievement page.
9. User can switch between the three achievement tabs and scroll. The scanner detects page changes and collects every visible achievement.
10. The app ranks achievements and shows the reason for each recommendation.
11. User taps **STOP** to stop capture, processing, and API calls. The floating control can remain visible in its idle state until dismissed.

The MVP is intentionally read-only: the user navigates the game UI; the assistant only observes and analyzes the allowed achievement page.

## Game allowlist and foreground guard

- Allowlist the package ID `com.supercell.clashofclans`; do not accept arbitrary package names.
- Declare only this package in the manifest `<queries>` element.
- Do not request `QUERY_ALL_PACKAGES`.
- Before processing a frame or sending it to the AI provider, verify that the allowed game is the foreground app.
- If the user switches to another app, pause analysis and do not send that screen to the provider.
- The exact foreground-detection mechanism must be validated on the A35: prefer the least-privileged reliable option. Usage Access may be used for package-state detection; an Accessibility service should be considered only if required and must be filtered to the Clash package. Do not use Accessibility to perform game taps.
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
- The overlay must be draggable, small, translucent, and positioned away from achievement progress bars. Include Start, Stop, and Hide controls.
- If overlay permission is revoked or Android stops the service, show a clear status and fallback notification/app control.

## Screen recognition pipeline

1. Verify the foreground package.
2. Detect the Achievement screen using known anchors: the profile header and the three tab labels.
3. Detect which of the three achievement tabs is selected.
4. Crop only the achievement-list region. Exclude unrelated village UI, player name, chat, and resource counters where possible.
5. Wait for a stable frame after tab changes or scrolling.
6. Use local OCR / image parsing first. The first UI language to support is Russian; English can follow.
7. Use DeepSeek Vision only when local extraction is uncertain or the task description needs semantic interpretation.
8. Validate the structured result before saving it.

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

- Unit tests for number parsing, progress arithmetic, completion detection, stable IDs, and ranking.
- Screenshot fixtures for the supplied Russian Achievement screen and additional scroll positions.
- Tests for all three tabs, completed achievements, partially completed achievements, and long lists.
- Negative tests for Event / Season / Clan Games pages: they must be ignored.
- API tests for missing / invalid key, offline mode, timeouts, 429, and malformed JSON.
- Lifecycle tests for permission denial, screen lock, overlay revocation, app switch, rotation, and Stop.
- Device validation on Samsung Galaxy A35 5G / Android 16 before calling the APK stable.

## MVP acceptance criteria

- The only addable game is the allowlisted Clash of Clans package.
- The scanner processes only the three Profile → Achievements tabs.
- Event, Season, and Clan Games screens are ignored.
- All visible rows are parsed with explicit confidence and unknown values where necessary.
- Scrolling does not create duplicates or skip rows.
- The ranking explains why an achievement is recommended.
- Start and Stop always release capture resources and stop API calls.
- No real API key is present in the repository or build artifacts.
- The app never performs automated game actions.

## Fair Play and policy boundary

This project is designed as a passive, read-only screen-analysis assistant. It must not automate gameplay or modify/interfere with the game. However, read-only behavior does not guarantee that Supercell considers a third-party overlay or achievement-analysis app authorized. Supercell's published Terms and Fair Play policy restrict unauthorized third-party software and automation. Before public distribution or live-game use, the project owner should review the current policies and, if certainty is required, obtain clarification from Supercell. Do not advertise the app as officially approved or ban-proof.


## Current MVP implementation notes (2026-11-24)

The first Android source drop now implements:
- Native Kotlin Android app with a fixed Clash of Clans package check.
- MediaProjection consent flow and a screen-capture foreground service.
- Frame processing throttled to a maximum of 30 frames/second.
- Floating draggable START / STOP controls; START resumes the saved Achievement database.
- Usage Access foreground-package check; analysis is paused unless the latest foreground activity belongs to the allowlisted Clash package.
- DeepSeek Vision automatically analyzes a stable changed frame, with a minimum interval between requests (not one remote API request per video frame).
- Central achievement-panel crop before the image is sent to the provider.
- AES-GCM encryption of the user's key using Android Keystore.
- A central no-game-input / no-gem-spending policy guard.

The current MVP sends a cropped image to DeepSeek for semantic parsing and merges validated rows into a local, deduplicated Achievement database across manual scroll-and-scan passes. Local OCR, automatic list navigation, and a complete combat-power feasibility model remain planned work. A basic completion-time estimate is now derived from at least two observed progress increases separated by time; it is a rough historical-rate estimate, not a guaranteed prediction.

### User-driven extra context

If the AI needs a building or troop level to estimate whether an achievement is feasible, it may explain exactly what information is missing and ask the user to open that screen manually. This MVP does not tap game objects or navigate the game automatically. The user must navigate to the Achievement tab and scroll manually; automatic recognition and merging continue while the scan session is active. The achievement task parser itself rejects building-detail, army, shop, Events, Season Challenges, and Clan Games screens.

### Non-negotiable Gems lock

- No game input injection, Accessibility click actions, coordinate taps, UIAutomator, ADB control, or game-control API is included.
- No purchase, upgrade, boost, instant-finish, or spending interaction is implemented.
- Gems are parsed only as visible achievement rewards.
- A future feature that adds any game input must not be merged without a separate safety review; spending Gems is out of scope permanently.

### Frame-rate and provider-cost policy

The capture pipeline accepts frames at up to 30 FPS and stores only the latest in-memory frame. It does not upload a video stream. A DeepSeek request occurs only after a stable screen change and is rate-limited; the full 30 FPS stream is never uploaded.

### Build status

The GitHub Actions workflow is configured to run `assembleDebug` and upload an APK artifact. A green build and physical-device test are still required before calling the APK stable.
