# Sticker Studio

A native, offline Android 10+ app for making personal static and animated WhatsApp sticker packs. Kotlin, Jetpack Compose Material 3, Room, Android media decoders, Glide's bundled GIF decoder and a JNI wrapper around Google's libwebp 1.6.0.

## Install

Open `deliverables/Sticker-Studio.apk` on an Android phone and allow installation from the app used to open it. This is a debug-signed personal-install build. Keep the app installed so WhatsApp can read its sticker assets. There is no account, network permission, analytics, advertising or server.

## Use

1. Create a static or animated pack in **My packs**.
2. Import actual PNG, JPEG, WebP, GIF or MP4 files with the system file picker, or share one or multiple files to Sticker Studio from another app.
3. Review imports, open their editor if needed, and choose a matching destination pack. Mixed file types can be handled in batches: incompatible files stay in the preview list for a different pack. Errors are reported for each file.
4. Still editor: crop edges, rotate, manually erase/restore, add and drag text, adjust a white outline, undo/redo and inspect light/dark transparency. Changing the crop or rotation resets brush strokes; Undo restores them.
5. Animated editor: crop, rotate, choose start/end and preview the selected loop. Still-only brush/text controls are not offered for animation. Shorten a clip if it cannot fit the size limit after quality/frame-rate reduction.
6. Tap a saved sticker to edit it again, move it earlier/later, remove it or use its first frame as the pack icon. Rename/delete packs from the pack screen.
7. With 3–30 valid stickers and an icon, **Add to WhatsApp** becomes available. If both WhatsApp and WhatsApp Business are installed, select the destination. Up to ten packs can be exposed; hide a pack to make room for another. Hiding does not delete copies WhatsApp already cached.

Draft packs may contain fewer than three stickers. Static and animated stickers are kept in separate packs. Originals and editing instructions are stored separately from generated assets in app-private storage; deleting the source download does not break the collection. Uninstalling this app deletes that collection. Backup/restore is deferred.

Sticker.ly is supported only when it supplies actual shared or saved media. Links, codes, private storage and proprietary pack archives are outside this version. There is no automatic background removal.

## Build

Pinned toolchain: JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Compose BOM 2025.04.01, Room 2.7.1, SDK/build tools 35, NDK 27.0.12077973, CMake 3.22.1. Dependency versions are in the Gradle files. The APK includes ARM64, ARMv7 and x86-64 native libraries, with 16 KB shared-library alignment.

On Windows:

```powershell
./scripts/setup.ps1
./scripts/build.ps1
# With an emulator/device connected:
./scripts/build.ps1 -Test
```

The setup script installs project-local tools in `.tools/` and writes `local.properties`. Alternatively use an existing JDK 17/Android SDK and configure `JAVA_HOME` and `sdk.dir`. Open this folder in Android Studio, or run `gradlew.bat :app:assembleDebug` (`./gradlew` on Linux/macOS with an existing SDK). Internet access is needed for the first build; runtime processing is offline.

Output: `app/build/outputs/apk/debug/app-debug.apk`. `scripts/build.ps1` copies it to `deliverables/Sticker-Studio.apk`. Source includes libwebp, so no native dependency download is needed during compilation. Tool downloads and build caches are excluded from the source handoff.

## Format and security

Still stickers are 512 × 512 WebP, up to 100 KB; animation is 512 × 512 WebP, up to 500 KB and ten seconds, with frames at least 8 ms. Tray icons are 96 × 96 PNG, up to 50 KB. Compatible WebP is validated and preserved without recompression. Other inputs are rendered onto a transparent canvas. GIF disposal is handled by Glide; libwebp composes animated WebP frames. Video frames use Android's decoder and retain display rotation.

Frames are processed incrementally, with progress, cooperative cancellation between decode/encode steps, and reduced quality/sample rate on retry. One decode/encode call must finish before cancellation can take effect. Imported files are capped at 100 MB, animation dimensions at 4096 pixels, video dimensions at 8192, and still images at 100 megapixels. Inputs exceeding a phone's estimated animation memory budget are rejected with a resize message.

The provider at `com.stickerstudio.stickers` uses WhatsApp's read permission. It exposes only eligible packs explicitly added, whitelists current asset/tray filenames, checks canonical parent paths and rejects all writes. Originals, edits, database files and unlisted paths cannot be served. Pack IDs are stable; asset versions increase after changes. Conversion commits metadata only after successful output, and failed replacements leave saved assets intact.

Protocol references: [WhatsApp Android contract](https://github.com/WhatsApp/stickers/blob/main/Android/README.md), [libwebp container API](https://developers.google.com/speed/webp/docs/container-api), [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes). See `THIRD_PARTY_NOTICES.md` for dependencies and licenses, and `VALIDATION.md` for actual verification results and pending device checks.
