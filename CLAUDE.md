# Notes for Claude Code

- Read `HANDOFF.md` first. It has the architecture, settings, build flow and history.
- The only build is GitHub Actions: push to `main` runs `gradle assembleDebug` and publishes `PingT.apk` to the rolling `latest` release. There is no local Android SDK and Google's Maven/Gradle hosts are blocked. Verify every change with the Actions run, and wait for a run to finish before pushing again (`cancel-in-progress` is on).
- Never change, move or regenerate `keystore/debug.keystore` or the signing config in `app/build.gradle.kts`.
- No external libraries (no AndroidX, Compose, Material). Plain Android framework APIs only.
- At the end of every task, update `HANDOFF.md` (version, history, settings, known limits) and commit it.
