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
| protobuf-java (runtime) and protoc (build only) | 4.36.2 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| Bouncy Castle (bcprov-jdk18on, bcpkix-jdk18on, and bcutil-jdk18on pulled in by bcpkix) | 1.86 | MIT (Bouncy Castle Licence) — the artifacts ship `META-INF/LICENSE.md` containing the MIT License text | https://www.bouncycastle.org/ |
| JetBrains annotations | 26.1.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Google Play Billing Library | 9.1.0 | Android SDK License (proprietary; `goog` flavor only, not shipped in the F-Droid `foss` build) | https://developer.android.com/google/play/billing |
| opus | 1.6.1 | BSD-3-Clause | https://github.com/xiph/opus |
| speex (codec) | 1.2.1 | BSD-3-Clause | https://github.com/xiph/speex |
| speexdsp (resampler, preprocessor, jitter buffer) | 1.2.1 | BSD-3-Clause | https://github.com/xiph/speexdsp |
| CELT | 0.7.1, 0.11.1 | BSD-3-Clause | https://gitlab.com/quite/celt |
| RNNoise | 0.2 | BSD-3-Clause — the submodule's own `COPYING` is the 3-clause BSD text (Jean-Marc Valin; Amazon; Mozilla; Xiph.Org Foundation; Mark Borgerding) | https://github.com/xiph/rnnoise — the pinned model weights (`rnnoise_data-0b50c45`, embedded as `libraries/humla/src/main/cpp/rnnoise/model/weights_blob.bin`) are the ones upstream's own `download_model.sh` fetches from media.xiph.org; they carry no separate license file and the project README describes them as RNNoise files kept out of git only for size, so the same `COPYING` covers them. |
