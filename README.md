# COC AI Agent

An Android Achievement assistant for Clash of Clans. DeepSeek Vision reads the Achievement list; an opt-in Android Accessibility service can open the player profile and automatically scroll the confirmed Achievement page.

## Achievement tabs supported

- Home Village — «Родная деревня»
- Builder Base — «Деревня строителя»
- Clan Capital — «Столица клана»

The scanner is not intended for Event Missions, Season Challenges, Clan Games, or other screens.

## Android MVP

- Kotlin Android app; minimum Android 8, tested target SDK 36.
- Fixed package allowlist for the official Clash of Clans package: `com.supercell.clashofclans`.
- User-authorized MediaProjection screen capture, with a 30 FPS processing ceiling.
- One floating START / STOP toggle. STOP pauses capture without clearing the Achievement database; START continues from saved records.
- Foreground-package guard using Android Usage Access.
- Automatic DeepSeek Vision analysis when a stable captured screen changes, with a request interval limit.\n- A Settings diagnostic checks DNS, API-key validity and whether the account exposes image input for `deepseek-flash`.
- User's API key is encrypted with AES-GCM and an Android Keystore key.
- Achievement screenshots are cropped toward the central panel before being sent to DeepSeek.
- Basic completion-time estimates after multiple progress observations separated in time.\n- No raw screenshot archive is retained by default.
- Gems are treated as potential rewards only. Navigation gestures are limited to opening the player profile and scrolling the confirmed Achievement list; no battle, troop deployment, purchase, upgrade, boost, or Gem-spending actions are implemented.

## Important limits

START obtains Android's screen-capture consent, opens the allowlisted game, and begins automatic changed-frame analysis. When DeepSeek identifies the main village, the app taps the player-profile badge at a calibrated relative position. When DeepSeek confirms Profile → Achievements, Accessibility sends an upward swipe through the list; a changed page is then analyzed and merged into the local database. The current scroll controller is limited to 40 swipes per session and only runs when the game is confirmed in the foreground.

The user must enable the app's Accessibility service once in Android Settings. This service does not read the game UI hierarchy; it dispatches only the profile-entry tap and Achievement-list swipe. Android still requires fresh screen-capture consent for every MediaProjection session. The Achievement database is preserved between STOP and START.

**The app does not play battles or earn combat achievements by itself.** It does not choose a multiplayer opponent, train an army, deploy troops, cast spells, or manage battle outcomes. It also does not purchase, upgrade, boost, or spend Gems.

Supercell's current published policies prohibit bots/gameplay automation and unauthorized third-party software that modifies or interferes with game play. Read-only observation does not constitute official Supercell approval; use at your own account risk and review the current policies before live-game use.

## Build

GitHub Actions builds a debug APK on every push to `main`:

1. Open the repository's **Actions** tab.
2. Select **Build Android APK**.
3. Open the latest successful run.
4. Download the `COC-AI-Agent-debug` artifact and extract `app-debug.apk`.

The debug APK has application ID `com.khayom.cocai.debug`. A release build/signing pipeline is not configured yet.

## API key

Never put a real DeepSeek API key in this repository, source code, issues, screenshots, or logs. Enter your own key in the app's Settings area. Client-side key storage is not absolutely secret on rooted or compromised devices; rotate/revoke a key if exposure is suspected.

## Technical documentation

See [docs/TECHNICAL_DESIGN.md](docs/TECHNICAL_DESIGN.md) for the full architecture, permissions, safety boundaries, data model, failure handling, and test plan.

## Current status

Android MVP source and a GitHub Actions debug-APK workflow are committed. The automated profile-navigation and list-scroll implementation is under device validation. GitHub Actions runs the JVM safety tests and builds the debug APK; physical validation on the Samsung Galaxy A35 5G / Android 16 is still pending.
