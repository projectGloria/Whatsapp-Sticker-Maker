# Third-party software

The app bundles Google's libwebp 1.6.0 at commit `4fa21912338357f89e4fd51cf2368325b59e9bd9`, under the BSD license. Its license, patent grant and authors are preserved in `app/src/main/cpp/libwebp/` (`COPYING`, `PATENTS`, `AUTHORS`). Upstream: https://chromium.googlesource.com/webm/libwebp/.

The GIF decoder is Glide `gifdecoder` 4.16.0, distributed under BSD-style and Apache 2.0 licenses. Upstream licenses: https://github.com/bumptech/glide/tree/v4.16.0. AndroidX and Jetpack Compose are Apache 2.0, Kotlin and kotlinx.coroutines are Apache 2.0, and Coil is Apache 2.0. Their original license metadata is supplied through their Maven dependencies.

The WhatsApp provider protocol was implemented from the public contract at https://github.com/WhatsApp/stickers/tree/main/Android. Sticker Studio is an independent personal utility and is not affiliated with WhatsApp or Sticker.ly.

Build tools (JDK, Gradle, Android SDK, NDK and CMake) have their respective upstream licenses and are not bundled into the APK or source archive.
