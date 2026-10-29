# Review fixes — 1.0.4-test

This update addresses the ten findings in ANDROID-APP-REVIEW-2026-10-29.md. The original report describes the earlier 1.0.3 build.

| Finding | Implementation |
| --- | --- |
| F01: exposed mixed-pack consistency | Normalize published assets inside the sticker mutation transaction. Edits read current metadata before committing, preserving reorder changes. |
| F02: unsaved editor work | Draft and undo/redo state belong to the retained editor session. Draft descriptors are also persisted for recovery after process loss. |
| F03: expensive outlines | Replace repeated bitmap composition with circular alpha dilation using sliding maxima. Preview/export rendering supports cancellation checks between dilation rows of offsets. |
| F04: preview/save disagreement | Preview uses the same compatibility/preservation rules as conversion. Unchanged compatible WebP displays the preserved image. |
| F05: unused generated files | Reconcile generated files against committed metadata after successful edits/imports/tray changes and during startup. Failed import tray output is reconciled; originals remain referenced. |
| F06: import recovery | Store pending media descriptors, accepted edits, destination, and active editor drafts. New imports use private files storage. Original filenames record import identity so recovery can exclude already committed imports. Missing files are omitted safely. |
| F07: provider scaling | Query the requested pack directly; list only exposed packs; count exposed packs with a SQL count rather than hydrating the library. Existing filename/path checks remain. |
| F08: playback timing | Schedule frame deadlines against elapsed realtime, subtracting decode/render work. Create, use and close the frame source within one dispatcher scope. |
| F09: crop resolution | Choose export decoding resolution from the crop fraction rather than always reducing the complete source to 1536 pixels. Enforce a conservative memory bound and reject crops that exceed it. |
| F10: missing WhatsApp target | Resolve installed destinations before exposing or rewriting assets. |

Additional safeguards include a shared mutation mutex across model instances, chunked cancellable original copying, cancellation checks before commit, conservative animation checks before GIF allocation, current-heap-headroom budgeting, native allocation exception boundaries, checked native file reads, and encoder ownership protection if scratch allocation fails.

## Validation

Final verification: debug build successful for all three configured native ABIs; **27/27 API 29 instrumentation tests and 6/6 unit tests passed**. Lint: **0 errors, 21 advisory warnings** (dependency notices, optional KTX style, backup configuration, launcher resources). Raw output and XML are in docs/review-fixes-evidence. No physical-phone or actual WhatsApp test was performed.

Delivered APK: deliverables/Nihil-and-catz.apk. SHA-256: 76AFDD858226CE88E7119BF1D9726E3129A76C51D02D2333FC438A5690D1C449. The previous 1.0.3 APK is retained as deliverables/Nihil-and-catz-1.0.3-before-review-fixes.apk.

The Android 10 / API 29 emulator suite contains 27 tests, including six review regressions, the existing processing/data/UI coverage and three diagnostic benchmarks. Six local unit tests cover limits and animation policies. The review regressions now assert repaired behavior rather than reproducing defects. Session recovery is tested by serializing and reconstructing descriptors and drafts, including missing-file handling; it is not a destructive operating-system kill test.

The existing UI tests now address the tool chip through its semantic action and scroll virtualized content into view before checking it. Their product assertions remain intact. Physical touch behavior on phones still needs device acceptance.

An initial successful run of the repaired outline implementation measured median render times of 37 ms at 8 px and 65 ms at 20 px, compared with 467 ms and 1,133 ms in the original review. PNG conversion measured about 33 ms, previously 518 ms. These are emulator measurements, with some host-load variation between runs. The circular mask implementation can produce slightly different edge pixels from the previous 24-angle repeated-composition approximation.

## Practical limits

- Actual WhatsApp/Business import, cache refresh and physical ARM playback remain device checks. No claim of physical-phone acceptance is made.
- A slow decoder can still miss playback deadlines. The scheduler avoids adding the full media delay after processing but cannot make a slow decoder meet real time.
- Late GIF/WebP selections still require sequential decoding. Arbitrary decoded animations are not cached in memory.
- Published-pack normalization still performs bounded wrapper/validation work within the metadata transaction, so very large or slow storage operations can briefly delay other database work. Model mutation serialization prevents competing UI mutations.
- Native calls remain synchronous; cancellation takes effect after an in-flight call returns. Memory limits are conservative estimates, not a guarantee against all device memory pressure.
- Persistent session writes use Android private preferences. An abrupt crash during a write may lose the most recent change. Full journaled conversion resumption and backup/restore remain separate work.
- Cleanup removes superseded generated filenames once replacement metadata commits. Clients querying an old filename before a mutation may need to requery; already open Android file descriptors remain usable.

The test build keeps application ID com.stickerstudio.test and the existing signing identity, with versionCode 5 and versionName 1.0.4-test, to update the previous test app without uninstalling its collection.
