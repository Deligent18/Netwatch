# NetWatch

Android app (Kotlin, Jetpack Compose) that monitors devices on your Wi-Fi/LAN.

## Download the app

**[Download netwatch.apk](https://github.com/Deligent18/Netwatch/releases/latest/download/netwatch.apk)**

1. Open the link above on your Android phone. If the repository is private, sign in to GitHub in the same browser first.
2. When the download finishes, open the file. Android will ask you to allow installs from your browser. Allow it for this one install.
3. Tap **Install**, then open **NetWatch**.

The link always gives the newest build. Every push to `main` builds a fresh APK and replaces the old one. If the link shows "Not Found", the first build hasn't finished or hasn't passed yet. Check the **Actions** tab.

## Features
- Discovers devices on your subnet (ping + TCP probes, reverse DNS, ARP when the OS allows)
- Tracks online/offline status, first/last seen, nicknames, and a "trusted" flag
- Background scan every ~15 min (Wi-Fi only) with a notification for each new untrusted device
- Port scan of common ports per device
- Optional router integration (OpenWrt): MAC addresses, hostnames, sleeping Wi-Fi devices, traffic totals, and
  **disconnect + 1-hour ban** of a Wi-Fi client

## Open it in Android Studio
1. Android Studio (Ladybug or newer) -> Open this folder. It will create the Gradle wrapper and sync.
2. Run on a phone connected to the Wi-Fi you want to monitor.

## Limits you should know about
- Android 10+ blocks apps from reading `/proc/net/arp`, so without the router integration, MAC addresses are
  usually unavailable and devices are tracked by IP (which can change). Configure the router for reliable identity.
- Disconnecting works only through your router's admin API, and only for Wi-Fi clients. Other router firmware needs
  another `RouterClient` implementation (see `router/Router.kt`).
- Per-device live bandwidth isn't possible from a phone; the app shows Wi-Fi byte counters from the router.
- Background scans run at Android's discretion (min 15 min, may be delayed by battery optimization).
- This code was written without an Android SDK available, so it has not been compiled or run yet.
  Expect to fix a few small build errors on first sync.
