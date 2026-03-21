# Internal Bypass Third-Party Sources

The internal bypass pipeline vendors source code from these upstream projects so the app can build and run without `ByeDPIAndroid/`:

- `app/src/main/cpp/byedpi/`
  - Source: `https://github.com/hufrea/byedpi`
  - License: MIT
  - Local notice: `app/src/main/cpp/byedpi/LICENSE`
- `app/src/main/jni/hev-socks5-tunnel/`
  - Source: `https://github.com/heiher/hev-socks5-tunnel`
  - License: MIT
  - Local notice: `app/src/main/jni/hev-socks5-tunnel/LICENSE`

Only the native bridge/runtime pieces required for the manga app's internal bypass path were vendored.

## Maintenance Notes

- `ByeDPIAndroid/` is a read-only reference folder and is intentionally ignored by git. The app must keep building without it.
- The repository stays single-app and self-contained: `settings.gradle` includes only `:app`, and there is no Gradle module, included build, IPC bridge, or app-to-app launch path tied to the reference project.
- The active runtime entry points for the bypass path live under `app/src/main/java/com/maxwai/nclientv3/bypass/`.
- The app stays direct-first. The internal VPN path is activated only after runtime failure detection, then direct access is re-tested later so the bypass can shut back off when the network is healthy again.
