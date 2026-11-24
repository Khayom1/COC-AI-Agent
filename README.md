# COC AI Agent

A read-only Android assistant that scans and prioritizes the built-in Clash of Clans **Profile → Achievements** list.

## Scope

- Home Village achievements
- Builder Base achievements
- Clan Capital achievements
- Russian-first screen recognition, with support for additional languages planned
- Local progress tracking and explainable achievement recommendations
- Optional DeepSeek Vision analysis using each user's own API key

## Explicit exclusions

This project does **not** automate battles, deploy troops, tap game controls, modify game memory, inject code, or interact with the game server. Event Missions, Season Challenges, and Clan Games are not part of the scanner.

The app is designed to add only the official Clash of Clans package and to process achievement screens only.

## Technical design

See [docs/TECHNICAL_DESIGN.md](docs/TECHNICAL_DESIGN.md) for the architecture, Android permissions, privacy requirements, reliability safeguards, data model, and test plan.

## API key safety

Do not commit a real DeepSeek API key to this repository. Each user enters their own key in the app. The app is planned to store it encrypted with Android Keystore and must explain that no client-side storage is absolutely secret.

## Status

Planning and technical specification stage. No installable APK is available yet.
