# Ping -t for Android

Continuous ping, like `ping google.com -t` in Windows cmd. Uses the phone's own `ping` (real ICMP), one request per second.

- cmd-style log: `Reply from …: bytes=64 time=23ms TTL=117` / `Request timed out.`
- Live stats: sent, received, loss %, min / avg / max, jitter
- Stability verdict from the last 30 pings: STABLE / FAIR / UNSTABLE / DOWN
- Stop prints the same summary cmd shows after Ctrl+C
- Optional keep-screen-on while pinging

## Getting the APK

Every push to `main` builds the APK with GitHub Actions and puts it in the **latest** release:

`https://github.com/<owner>/<repo>/releases/download/latest/PingT.apk`

Open that on the phone, download, and install (allow "Install unknown apps" for your browser when asked). New builds install over the old one because the signing key in `keystore/` stays the same.

The key in `keystore/` is a throwaway debug key. Don't reuse it for anything else.
