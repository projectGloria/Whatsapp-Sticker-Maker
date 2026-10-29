# Personal Android Sticker Maker

## Summary

Build an offline Android app for creating, importing, organizing, and adding static and animated sticker packs to WhatsApp.

The first version supports Android 10+, uses Kotlin and Jetpack Compose, and stores your collection on your phone. Deliver the source project and an installable APK.

## Features and workflow

- **My Packs:** Create, rename, delete, and preview packs. Reorder or remove stickers, choose a pack icon, and add each pack to WhatsApp.
- **Import:** Select multiple PNG, JPEG, WebP, or GIF files through Android’s file picker, or receive files shared from another app. Preview imports and choose a destination pack before saving.
- **Photo editor:** Crop, rotate, erase/restore background, add movable text, adjust an outline, and undo/redo edits. Show transparency against light and dark backgrounds.
- **Animated editor:** Import animated WebP/GIF files or create stickers from MP4 videos. Provide trimming, cropping, and playback preview. Background erasing and text editing apply only to still stickers in this version.
- **Pack organization:** Keep static and animated stickers in separate packs. Allow incomplete draft packs; explain what is missing before enabling WhatsApp export.

Importing from Sticker.ly works when it supplies actual saved or shared media files. Sticker.ly links, codes, private app storage, and proprietary pack archives are outside this version.

## Implementation

- Create a native Android project with Compose Material 3, system light/dark themes, and Room for pack and sticker metadata. Use **Sticker Studio** as the working app name.
- Store imported originals and generated assets in internal storage, so deleting the original download does not break a pack. Save still-image editing instructions separately for later editing.
- Use Android image/video decoding and a bundled GIF decoder; use Google’s **libwebp**, through an NDK wrapper, for animated WebP decoding and encoding. Process frames incrementally with progress and cancellation. [WebP API](https://developers.google.com/speed/webp/docs/container-api)
- Preserve already-compatible imported WebP files without recompression. Otherwise, render onto a 512 × 512 transparent canvas and compress to WhatsApp’s limits. For animations, reduce quality and frame rate as needed; if the output still exceeds limits, ask the user to shorten the clip.
- Validate export requirements: 3–30 stickers per pack, static files up to 100 KB, animated files up to 500 KB, animations no longer than 10 seconds, and a 96 × 96 tray icon up to 50 KB. Follow the documented maximum of 10 exposed packs. [WhatsApp requirements](https://raw.githubusercontent.com/WhatsApp/stickers/main/Android/README.md)
- Implement WhatsApp’s read-protected `ContentProvider` contract and `ENABLE_STICKER_PACK` intent. Keep pack identifiers stable and increment asset versions after edits. Support WhatsApp and WhatsApp Business, with a target chooser when both are installed.
- Receive Android `ACTION_SEND` and `ACTION_SEND_MULTIPLE` media streams, validate actual file content, and copy files while access is available. Report unsupported or unreadable files individually.
- Set up the missing Android build toolchain and pin compatible dependency versions. Build an APK for personal installation.

## Validation and defaults

- Test static editing, transparency, animated timing, GIF frame disposal, video rotation, compression limits, and cancellation.
- Test mixed imports, malformed files, inaccessible shared files, full packs, incomplete packs, and persistence after restarting the app.
- Verify the complete flow on Android 10 and a current Android emulator, then confirm static and animated pack installation and sending on a phone with WhatsApp. Report device testing as pending if no device is available.
- Check that pack changes update exported metadata, failed conversions leave existing stickers intact, and provider requests cannot access unrelated files.
- Default to English, local processing, and no accounts, ads, analytics, cloud sync, public sticker catalog, or automatic background removal. Store publication and backup/restore are deferred.