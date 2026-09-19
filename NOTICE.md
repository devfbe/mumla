# Third-party notices

Mumla is licensed under the GNU GPL v3 (see `LICENSE`). It bundles or links the
following third-party components. Every entry is GPLv3-compatible.

| Component | Version | License | Source |
|---|---|---|---|
| Kotlin standard library and compiler | bundled with AGP 9.4.1 (built-in Kotlin; `agp` in `gradle/libs.versions.toml`) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx-coroutines | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (appcompat, activity, core, fragment, preference, recyclerview, cardview, documentfile, exifinterface) | see `gradle/libs.versions.toml` | Apache-2.0 | https://developer.android.com/jetpack |
| Material Components for Android | 1.14.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| jsoup | 1.23.2 | MIT | https://jsoup.org |
| MiniDNS | 1.0.5 | LGPL-2.1-or-later / Apache-2.0 / WTFPL (tri-licensed) | https://github.com/MiniDNS/minidns |
| NetCipher | 2.1.0 | Apache-2.0 | https://github.com/guardianproject/NetCipher — upstream has had no release since 2.1.0 (2021) and no commits since 2020-12; kept because no maintained replacement provides `OrbotHelper`. Re-evaluate when Tor integration is next touched. |
| protobuf-java | 3.11.4 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| Spongycastle | 1.51.0.0 | MIT (Bouncy Castle license) | https://rtyley.github.io/spongycastle/ — replaced by BouncyCastle in this branch |
| JetBrains annotations | 26.1.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Google Play Billing Library | 9.1.0 | Android SDK License (proprietary; `goog` flavor only, not shipped in the F-Droid `foss` build) | https://developer.android.com/google/play/billing |
| opus | 1.1 | BSD-3-Clause | https://github.com/xiph/opus |
| speex (codec + dsp, pre-split tree) | 1.2beta2+ | BSD-3-Clause | https://github.com/xiph/speex |
| CELT | 0.7.1, 0.11.1 | BSD-3-Clause | https://gitlab.com/quite/celt |
| JavaCPP | 0.7 | Apache-2.0 | https://github.com/bytedeco/javacpp — removed by the CMake migration |
