# Ping -t for Android

Continuous ping, like `ping google.com -t` in Windows cmd. Uses the phone's own `ping` (real ICMP). Keeps running in the background and shows the live ping in a small floating bubble over games.

- cmd-style log: `Reply from …: bytes=64 time=23ms TTL=117` / `Request timed out.`
- Live stats: sent, received, loss %, min / avg / max, jitter
- Stability verdict from the last 30 pings: STABLE / FAIR / UNSTABLE / DOWN
- Stop prints the same summary cmd shows after Ctrl+C
- Keeps pinging when minimized or with the screen off, until you press **Stop** (in the app or the notification)
- Floating bubble over other apps, including fullscreen landscape games
- Game mode (click-through): the bubble never blocks touches
- Host presets: google.com, 8.8.8.8, 1.1.1.1. The last host is remembered.
- Optional keep-screen-on while pinging (only while the app is open)

## Notification

While pinging there is a silent ongoing notification:
`Ping -t: <host>` / `23 ms · STABLE · loss 0%`. Tapping it opens the app. Buttons:

- **Stop**: stops pinging and removes the bubble
- **Click-through: ON/OFF**: switches game mode on the live bubble
- **Hide bubble / Show bubble**

## Bubble settings

Main screen → **Settings**. Every change is saved at once and applied to the running bubble. The preview at the top cycles 23 → 120 → 210 → TIMEOUT → 25 ms so you can see every color.

| Setting | Default | What it does |
|---|---|---|
| Show floating bubble | On | Asks for "Display over other apps" if not allowed yet |
| Style | Pill | Pill (dot + ms), Dot, Text, Bars (signal bars), Graph (last 20 pings), Ring |
| Size | Medium | Small 0.75×, Medium 1×, Large 1.35× |
| Opacity | 90% | 20–100% in steps of 5 |
| Background | Dark | Dark, Light, None (no background, outlined text for any game) |
| Click-through (game mode) | Off | Touches go straight to the game. No drag or tap on the bubble. |
| Show only when bad | Off | Hidden while green. Shows on yellow, red or timeout; hides again after 3 greens in a row. |
| Blink on timeout | On | Bubble pulses while the last ping failed |
| Snap to edge | On | After dragging, slides to the nearest left/right edge |
| Lock position | Off | No dragging (tap still opens the app) |
| Move bubble to | – | Top-left, Top-right, Bottom-left, Bottom-right for the current orientation |
| Hide while Ping -t is open | On | No bubble on top of the app itself |
| Vibrate after timeouts in a row | 3 | Off, 1, 3, 5. One short double pulse per outage; resets after a reply. |
| Thresholds | Green < 80, Yellow < 150 ms | Colors and verdict. Must be 1 ≤ green < yellow ≤ 2000. |
| Ping every | 1s | 0.5s, 1s, 2s, 5s (each ping still waits max 2 s) |

Red border on the bubble = verdict UNSTABLE/DOWN or the last ping failed. `TO` = timeout or failure, `--` = waiting for the first reply.

Drag the bubble to move it, tap it to open Ping -t. Portrait and landscape keep separate positions.

## Permissions you'll be asked for

- **Notifications** (Android 13+, first Start). If you deny, pinging still runs, you just won't see the notification.
- **Display over other apps**, for the bubble. Asked on first Start, or when you switch the bubble on in Settings.

## Game mode tips

- Turn on **Click-through** before opening the game, or from the notification while in it.
- Use Small or Dot / Ring style, top-left or top-right corner, opacity around 70%.
- With click-through on, Android 12+ blocks touches through any overlay more opaque than 80%. Ping -t caps the bubble at 80% while click-through is on and keeps your saved value for when you turn it off.
- **Show only when bad** keeps the screen clean until the connection actually drops.

## Known limits

- Xiaomi/MIUI, Oppo/ColorOS, Vivo, Samsung and similar may still kill background apps. Set Ping -t's battery setting to **Unrestricted** (Settings → Apps → Ping -t → Battery) and, on Xiaomi, allow **Autostart**.
- Game boosters (Samsung Game Booster, Xiaomi Game Turbo, etc.) can block floating windows or notifications during games. Allow Ping -t in the booster's settings (e.g. turn off "Block notifications"/"Block floating windows" or add Ping -t as an allowed floating app).
- The bubble cannot tell when the game itself is lagging; it shows network ping only.
- If Android kills the app anyway, it tries to restart pinging the same host; some phones don't allow that.

## Getting the APK

Every push to `main` builds the APK with GitHub Actions and puts it in the **latest** release:

`https://github.com/coronfire-station/pingt/releases/download/latest/PingT.apk`

Open that on the phone, download, and install (allow "Install unknown apps" for your browser when asked). New builds install over the old one because the signing key in `keystore/` stays the same.

The key in `keystore/` is a throwaway debug key. Don't reuse it for anything else.
