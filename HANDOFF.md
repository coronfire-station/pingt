# Ping -t: handoff notes

Read this first. Keep it current: update it and commit it at the end of every task.

## Summary

Android app that pings a host continuously like Windows `ping host -t`, using the phone's own `/system/bin/ping` (real ICMP). It keeps pinging in a foreground service while minimized or with the screen off and shows the live ping in a customizable floating bubble over other apps (made for mobile games), with a click-through game mode and an on-screen positioning tool.

- Package `app.pingt`, Kotlin, minSdk 24, compileSdk/targetSdk 34, AGP 8.7.3, Kotlin 2.0.21
- **No external libraries** (no AndroidX, Compose or Material). Plain framework APIs only.
- Look: dark `#0C0C0C`, monospace, green accent `#3DDC84`, button green `#1E7D3A`
- Ping colors: < green_max `#3DDC84`, < yellow_max `#FFC107`, else/loss `#F44336`, idle `#888888`
- **Current version: 1.3 (versionCode 4)**

## Architecture

| File | Job |
|---|---|
| `PingTApp.kt` | Application; runs the one-time prefs migration (`Prefs.migrate`) before anything reads settings |
| `MainActivity.kt` | Main screen. Shows `PingState` (log, last ms, verdict, stats), sends Start/Stop intents to the service, host presets, notification permission prompt |
| `PingService.kt` | Foreground service (`specialUse`). Owns the ping loop thread, wake lock, ongoing notification + actions, vibration, live settings (SharedPreferences listener), the bubble overlay and the positioning tool |
| `PingState.kt` | Singleton session state (main thread only): cmd-style log (500 lines), stats, 30-ping verdict, 60-sample history, listener list for screens, visible-screen counter for hide-in-app. Also `PingResult` |
| `Prefs.kt` | All setting keys and defaults (`BubbleSettings`), window alpha cap, position save/snapshot helpers, migration |
| `BubbleGeometry.kt` | Position math: full display size, status bar height, fractions ↔ px, fit-to-status-bar, presets |
| `BubbleOverlay.kt` | The bubble window (TYPE_APPLICATION_OVERLAY, full-display coordinates): flags, alpha, drag, snap, tap-to-open, per-orientation placement |
| `BubbleView.kt` | Draws all 6 bubble styles with Canvas; also used for the live preview in settings |
| `PositionPanel.kt` | Positioning tool control panel (second overlay): D-pad with hold acceleration, Fine/Coarse, presets, Done/Cancel |
| `SettingsActivity.kt` | "Bubble settings" screen built in code, with live preview |
| `res/layout/activity_main.xml` | Main screen layout |
| `res/drawable/` | Launcher icon, notification icon, chip background |

Data flow: `PingService` worker thread → `main.post` → `PingState.handleResult` → listeners (MainActivity) + service updates notification and bubble. Settings screens only write prefs; the service listens and applies everything live.

## Build and release

- **The only build is GitHub Actions** (`.github/workflows/build.yml`). The cloud session has no Android SDK and can't reach Google's Maven/Gradle hosts.
- Every push to `main` runs `gradle assembleDebug` and replaces the rolling **latest** release:
  https://github.com/coronfire-station/pingt/releases/download/latest/PingT.apk
- `cancel-in-progress` is on: a new push to `main` cancels a running build. Wait for green before the next push.
- Optional local type-check: kotlinc 2.0.21 with `org.robolectric:android-all:14-robolectric-10818077` (Maven Central) as classpath plus a stub `R` object. It does not check resources or hidden-API use.
- **Signing:** debug builds are signed with `keystore/debug.keystore` (committed, throwaway key). Never change or regenerate it, or updates stop installing over the old APK.

## Settings (SharedPreferences file `pingt_settings`)

Bubble look/behavior settings are per orientation (see `<key>_land` below); the service loads `BubbleSettings.load(prefs, landscape)` for the current orientation and reloads on rotation. Hide-in-app, vibrate, thresholds, interval are shared.

| Pref key | Default | Meaning |
|---|---|---|
| `show_bubble` | true | Show floating bubble (needs "Display over other apps") |
| `style` | `pill` | pill / dot / text / bars / graph / ring |
| `size` | `medium` | small 0.75× / medium 1× / large 1.35× |
| `opacity` | 90 | 20–100, window alpha. Capped at max obscuring opacity (0.8) on API 31+ while click-through is on |
| `background` | `dark` | dark / light / none (outlined) |
| `click_through` | false | FLAG_NOT_TOUCHABLE (game mode) |
| `only_when_bad` | false | Show on yellow/red/timeout, hide after 3 greens |
| `blink_timeout` | true | Pulse while last result failed |
| `snap_mode` | `off` | off / edges / corner (v1.1 `snap_edge` migrated: true→edges, false→off) |
| `lock_position` | false | No dragging |
| `fit_status_bar` | false | In the status bar strip, scale bubble to strip height (min 16dp) |
| `hide_in_app` | true | Hide bubble while a Ping -t screen is visible |
| `vibrate_after` | 3 | 0 (off) / 1 / 3 / 5 timeouts in a row |
| `green_max` | 80 | ms; must be 1 ≤ green < yellow ≤ 2000 |
| `yellow_max` | 150 | ms; STABLE verdict needs avg < yellow_max |
| `interval_ms` | 1000 | 500 / 1000 / 2000 / 5000 |
| `<key>_land` | unset → portrait value | Landscape override for the per-orientation keys: `show_bubble`, `style`, `size`, `opacity`, `background`, `click_through`, `only_when_bad`, `blink_timeout`, `snap_mode`, `lock_position`, `fit_status_bar` (`Prefs.ORIENTED`). Read with `Prefs.getBool/getStr/getInt(p, key, landscape, def)`, write with `Prefs.key(key, landscape)` |
| `pos_port_xf`, `pos_port_yf`, `pos_land_xf`, `pos_land_yf` | unset → Top-right preset | Position as fraction of free space on the full display (x / (displayW − bubbleW)) |
| `last_host` | google.com | Remembered host |
| `running_host` | – | Set while pinging; used if Android restarts the service |
| `notif_asked`, `overlay_asked` | false | One-time permission prompts done |
| `prefs_version` | 2 | Migration marker |

## History

- **v1.0** (versionCode 1): basic continuous ping in the Activity, cmd-style log, stats, 30-ping verdict, keep-screen-on.
- **v1.1** (versionCode 2): foreground PingService (keeps running minimized/screen off), ongoing notification with Stop / Click-through / Hide bubble, floating bubble with 6 styles, size, opacity, background, show-only-when-bad, blink, vibrate, hide-in-app, drag/snap/lock, corner presets, settings screen with live preview, configurable thresholds and interval, host presets, click-through game mode with the Android 12 alpha cap.
- **v1.2** (versionCode 3): free positioning anywhere on the full display incl. the status bar strip and cutout corners; positions stored as fractions per orientation (v1.1 px positions migrated); snap modes Off/Edges/Corner; 9 grid + 3 status bar presets; fit to status bar height; on-screen positioning tool (control panel overlay, D-pad, Done/Cancel, notification "Done positioning").
- **v1.3** (versionCode 4): separate bubble settings for portrait and landscape (Portrait/Landscape selector in settings, landscape inherits portrait until changed, "Same as portrait" reset); notification toggles act on the current orientation.

## Known limits

- TYPE_APPLICATION_OVERLAY is always below the status bar: in the strip, status bar icons draw over the bubble unless the game is fullscreen. Touches in the strip go to the status bar, so the bubble can't be dragged there (use the positioning tool).
- Aggressive OEM battery managers (Xiaomi, Oppo, Vivo, Samsung) may kill the app; users need battery "Unrestricted" (and Autostart on Xiaomi).
- Game boosters (Samsung Game Booster, Xiaomi Game Turbo) may block floating windows/notifications during games.
- Notification permission denied on Android 13+: pinging runs, but there is no notification (no Stop/toggles outside the app).
- If Android kills the process, the sticky restart resumes the last host; Android 12+ may refuse to start the foreground service from the background.
- Shows network ping to the chosen host only, not game-server latency.
- The settings screen's presets for the other orientation use the current status bar height (portrait and landscape bars can differ by a few px).
- Nothing has an automated test; every change needs the phone checklist in the release notes / README.

## Open ideas

- CSV export of the ping history
- Beep (or vibration) on recovery after an outage
- Auto-start pinging when a chosen game is opened
