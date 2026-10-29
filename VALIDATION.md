# Validation — 29 October 2026

## Delivered build

Sticker Studio 1.0.0 (`com.stickerstudio`), Android 10+ (min SDK 29, target SDK 35). Personal-install, debug-signed APK with ARM64, ARMv7 and x86-64 libraries.

APK: `deliverables/Sticker-Studio.apk` — 66,209,660 bytes.

SHA-256: `39dfce201257fbc468b1aadbd624e31b98050473c0024872ee6927b8543c1b6c`.

## Completed checks

| Check | Result |
| --- | --- |
| Android 10 / API 29, AOSP x86-64 emulator | 15 tests passed, 0 failed/skipped |
| Android 16 / API 36, Google APIs x86-64 emulator | 15 tests passed, 0 failed/skipped |
| Local JUnit pack-limit test | Passed |
| Android lint | 0 errors, 15 nonfatal warnings |
| APK signature | Verified, APK Signature Scheme v2 |
| APK ZIP alignment | Passed `zipalign -c -P 16 4` |
| Native ELF alignment | Every LOAD segment in all six native libraries uses 0x4000 alignment |
| Network permissions | No INTERNET or network-state permission requested |
| Provider access from unrelated shell UID | Denied by `com.whatsapp.sticker.READ` on both emulator versions |
| Light/dark library rendering | Screenshots inspected; readable status/navigation icons and layout |
| Full process restart | Created a pack, imported a shared PNG, force-stopped both app and sharing source, relaunched, and verified the pack, sticker count and rendered sticker on Android 10 |

The 15 instrumented tests cover:

- Still rendering, transparent pixels, manual erase/restore masks, text/edit instruction round trips, crop and rotation.
- Static and animated WebP imports preserved byte-for-byte when compatible.
- GIF disposal modes 2 and 3, cumulative frame timing, 512 × 512 animation output, duration and minimum frame delays.
- Animated WebP trimming/cropping, MP4 display rotation and selection of a video range that starts after zero.
- A detailed noise animation that exercises compression retries and finishes within 500 KB while retaining timing.
- Malformed/truncated media and inaccessible shared streams.
- Cancellation between frames and protection of existing assets after failed replacement.
- Database reopening, pack version increments, sticker reordering, tray changes and draft visibility.
- Read-only provider access and rejection of originals, unknown filenames and encoded path traversal.
- The ten-pack exposure limit and hiding a pack to make room.
- A full 30-sticker pack, mixed media, individual import errors and retention of unimported files.
- UI creation → external share → destination selection → save → text edit → save → activity recreation → reopen.
- Multiple external shares containing a PNG, GIF and malformed file, with individual error reporting.

JUnit XML and lint output are saved in `docs/validation/` and copied into `deliverables/validation/`. The fixtures are generated locally; `scripts/generate-fixtures.py` documents their construction. The rotated MP4 contains an actual display matrix, verified with FFprobe.

Lint warnings comprise seven newer-dependency notices, seven optional KTX API style suggestions and one legacy backup-configuration advisory. Versions are intentionally pinned. Legacy `allowBackup=false` and explicit Android 12+ cloud/device-transfer exclusions are in the manifest/resources.

## Pending phone checks

No physical phone was connected. The following require a phone with WhatsApp and/or WhatsApp Business:

1. Add a static pack containing at least three stickers and send one in a test chat.
2. Repeat for an animated pack; inspect playback and transparency after sending.
3. When both WhatsApp variants are installed, confirm the target chooser and add to each independently.
4. Edit a previously added pack, add it again and confirm WhatsApp's asset-cache refresh behavior.

ARM64 and ARMv7 were compiled and their APK/ELF structure checked; runtime tests used x86-64 emulators. Store publication and backup/restore remain outside this version, as specified in the plan.
