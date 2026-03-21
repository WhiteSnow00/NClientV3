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
