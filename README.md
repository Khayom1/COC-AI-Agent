# COC AI Agent

A read-only Android assistant for reviewing and prioritizing the built-in Clash of Clans **Profile → Achievements** list.

## Achievement tabs supported

- Home Village — «Родная деревня»
- Builder Base — «Деревня строителя»
- Clan Capital — «Столица клана»

The scanner is not intended for Event Missions, Season Challenges, Clan Games, or other screens.

## Android MVP

- Kotlin Android app; minimum Android 8, tested target SDK 36.
- Fixed package allowlist for the official Clash of Clans package: `com.supercell.clashofclans`.
- User-authorized MediaProjection screen capture, with a 30 FPS processing ceiling.
- Floating START / STOP controls; START resumes the saved Achievement database.
- Foreground-package guard using Android Usage Access.
- Automatic DeepSeek Vision analysis when a stable captured screen changes, with a request interval limit.
- User's API key is encrypted with AES-GCM and an Android Keystore key.
- Achievement screenshots are cropped toward the central panel before being sent to DeepSeek.
- Basic completion-time estimates after multiple progress observations separated in time.\n- No raw screenshot archive is retained by default.
- Gems are treated as potential rewards only. The app has no game-input automation and cannot tap purchase, upgrade, boost, or spend controls.

## Important limits

This project is a passive, read-only screen analyzer. It does not tap Clash of Clans controls, deploy troops, automate battles, modify game memory, inject code, or interact with game servers. START obtains Android's screen-capture consent, opens the allowlisted game, and begins automatic changed-frame analysis. The user must navigate to Profile → Achievements and scroll manually; there is no automated game tapping or scrolling. The Achievement database is preserved between STOP and START.

For building/army-level context, the user must navigate to the relevant screen manually. The MVP's AI parser deliberately accepts only the three Achievement tabs. The app does not automatically open the profile, tap game controls, or scroll the list.

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

Android MVP source and a GitHub Actions debug-APK workflow are committed. The APK is not considered stable until the Actions build and unit tests succeed and the app is tested on the Samsung Galaxy A35 5G / Android 16.
