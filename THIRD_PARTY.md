# Third-party software

LANyard bundles the following third-party code in its source tree.

## qrcodegen (QR Code generator library)

- **File:** `internal/uiserver/web/qrcodegen.js` (compiled from TypeScript, ES6 build)
- **Version:** 1.8.0
- **Upstream:** https://github.com/nayuki/QR-Code-generator (release asset `qrcodegen-v1.8.0-es6.js`,
  https://github.com/nayuki/QR-Code-generator/releases/tag/v1.8.0)
- **Author:** Project Nayuki
- **License:** MIT

The vendored file is unmodified and carries its original MIT license header. It renders the
pairing QR code entirely client-side (no network, no CDN).

```
MIT License

Copyright (c) Project Nayuki. (MIT License)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Android `:core` (Gradle/Maven dependencies)

The Android module under `android/` pulls the following libraries at build time.
Versions are pinned in `android/gradle/libs.versions.toml`.

| Library | Version | License | Purpose |
| :--- | :--- | :--- | :--- |
| org.conscrypt:conscrypt-openjdk-uber | 2.6.3 | Apache-2.0 | TLS provider tried first (BoringSSL) |
| org.bouncycastle:bcprov-jdk18on | 1.83 | MIT | Ed25519 key generation and primitives |
| org.bouncycastle:bcpkix-jdk18on | 1.83 | MIT | X.509 certificate building |
| org.bouncycastle:bctls-jdk18on | 1.83 | MIT | JSSE provider used for Ed25519 mTLS (Conscrypt could not present the client certificate) |
| com.google.code.gson:gson | 2.11.0 | Apache-2.0 | JSON for the peer/UI APIs |
| org.junit.jupiter:junit-jupiter | 5.11.4 | EPL-2.0 | Test framework |
| org.junit.platform:junit-platform-launcher | 1.11.4 | EPL-2.0 | Test runtime |
| org.jetbrains.kotlin:kotlin-test | 2.0.21 | Apache-2.0 | Test assertions |
| org.jetbrains.kotlin:kotlin-stdlib / kotlin-gradle-plugin | 2.0.21 | Apache-2.0 | Kotlin/JVM toolchain |

The Android `:app` build (a later task) will use `org.conscrypt:conscrypt-android` (AAR,
`minSdkVersion=21`; native libraries for arm64-v8a, armeabi-v7a, x86, x86_64) in place of
`conscrypt-openjdk-uber`.
