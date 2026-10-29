# Android app review — Sticker Studio / Nihil and catz
Date: 29 October 2026 (Europe/Istanbul)

## Assessment

The media pipeline has useful safeguards and substantial existing coverage, but the app has important correctness and responsiveness problems. The highest priorities are preserving unsaved editor work, keeping exported packs readable after edits, and replacing the expensive outline-rendering algorithm.

This review identifies **10 findings: three P1, six P2, and one P3**. Five targeted runtime probes reproduce four findings (storage retention has separate sticker and tray probes); the outline performance finding is independently measured. The remaining findings follow directly from code paths and are explicitly labeled as code findings. Three additional engineering risks need targeted stress or fault-injection testing.

P1 means fix before a wider release; P2 means a material functional, reliability, or performance problem; P3 means a lower-impact behavior problem. None of these labels implies a demonstrated remote exploit.

Production implementation files were not changed. Two instrumentation audit test files and this report/evidence were added. Tests labeled “audit” deliberately assert the currently observed bad behavior; they are diagnostic evidence, not regression tests asserting correct behavior. Invert their expectations when implementing fixes.

## Scope and reproducibility

Reviewed the active root project, not the archived backUP copy:

- All ten Kotlin production files: activity and application setup, Compose library/import/editor flows, ViewModel, Room repository, content provider, rendering, conversion and animation sampling.
- Custom JNI wrapper in app/src/main/cpp/studio.cpp, CMake configuration, manifest, Gradle settings, existing unit/instrumentation/performance tests, and validation documentation.
- Input copying and validation; still/GIF/WebP/MP4 processing; crop/rotation/brush/text; preview and cancellation; pack creation/edit/delete/reorder/export; provider access and file retention.
- The vendored libwebp implementation was not exhaustively audited or fuzzed. This is not a dependency vulnerability certification.

The tested app is **com.stickerstudio.test, version 1.0.3-test, versionCode 4**, with minSdk 29 and targetSdk 35. Runtime testing used the local Studio29 Android 10 / API 29 x86-64 emulator, configured with four vCPUs and 2 GiB RAM. The running guest reported a 512 MiB maximum Dalvik heap. Emulator timings are not physical-phone performance guarantees.

The rebuilt app-debug.apk and deliverables/Nihil-and-catz.apk have the same SHA-256:
`9956AF4646517202B0017E5DD3A22CC9AF5DE2263D68111907DEA4E67D1BDE4E`

The older Sticker-Studio.apk and 1.0.2 backup APK were not the runtime targets. There is no Git repository at the workspace root, so the evidence folder records source hashes instead of a commit identifier.

### Verification results

| Check | Result and interpretation |
| --- | --- |
| Debug build and native targets | Successful; three ABI targets configured/built, with many existing Gradle outputs reused |
| Local unit tests | Six passed after explicitly cleaning the unit-test task outputs |
| Android lint task | Successful; report contains 0 errors and 18 warnings; analysis was reused as up-to-date |
| Initial API 29 instrumentation run | 26 tests: 23 passed, 3 failed |
| Existing tests within that initial run | All 16 processing/data tests and 3 diagnostic performance tests passed; both existing UI tests failed |
| Final focused audit run | Five audit tests passed, reproducing the reported behaviors |
| Actual WhatsApp / Business integration | Not run; neither end-to-end acceptance nor cache refresh is established |
| API 36 and physical ARM runtime | Not rerun during this review; older documentation reports prior runs |
| Security | Reviewed provider boundaries and existing test results; no new fuzzing or independent cross-UID permission test |

The initial third failure was an exploratory audit hypothesis that constant-frame animation editing would fail. That hypothesis did not reproduce. A narrower trim hypothesis also did not reproduce; neither is included as a defect. The final audit source removes this exploratory test.

The existing UI tests failed twice in this environment:
- StudioUiTest.mixedAndMalformedSharedFilesAreReportedIndividually: could not find “Static image.”
- StudioUiTest.createShareEditAndReopenCollection: could not find “Sticker text” after the tool-selection click.

The new lifecycle probe initially encountered the same pointer-click problem. Invoking the chip's semantic click action allowed it to reach and verify the lifecycle defect. This suggests interaction/test synchronization or geometry deserves investigation, but does not prove the cause of the existing failures. Those tests were not modified to conceal their failures. The final focused pass does **not** mean the complete suite is green.

Evidence files in docs/review-evidence:
- initial-api29.xml: full initial run, including failures.
- focused-api29.xml: repeat of the existing UI failures and exploratory probes.
- final-audit-api29.xml: final five reproducibility probes.
- TEST-com.stickerstudio.AnimationEncodingTest.xml and TEST-com.stickerstudio.LimitsTest.xml: fresh unit results.
- performance-api29.txt: raw benchmark log.
- lint-debug.txt: complete lint findings.
- source-hashes.json: hashes of production Kotlin, JNI, Gradle app configuration and main manifest.

## Findings at a glance

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| F01 | P1 | Editing a still in an exposed mixed pack makes the pack unreadable through the provider | Runtime reproduction |
| F02 | P1 | Activity recreation silently loses unsaved editor work | Runtime reproduction |
| F03 | P1 | Outline rendering takes hundreds of milliseconds per interactive update | Measured |
| F04 | P2 | Compatible WebP editor preview differs from the saved output | Runtime reproduction |
| F05 | P2 | Successful edits and tray changes accumulate unreferenced files | Two runtime reproductions |
| F06 | P2 | Process death loses pending imports and their accepted edits | Code finding |
| F07 | P2 | Each provider asset read scans and validates the whole collection | Code finding |
| F08 | P2 | Animation preview adds processing time to every frame's intended delay | Code finding, supported by decode timings |
| F09 | P2 | Cropping uses a globally downsampled image, permanently losing available detail | Code finding |
| F10 | P3 | An unsuccessful add attempt exposes a pack before WhatsApp availability is checked | Code finding |

## F01 — Exposed mixed packs disappear after an ordinary still edit

**P1. Runtime confirmed.**
References: [StudioModel.kt:144–154](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:144>) and 218–240; [Data.kt:224–269](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Data.kt:224>); [StickerProvider.kt:18–26](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StickerProvider.kt:18>).

A mixed pack is made uniform during Repository.expose(): still stickers are wrapped in animated WebP and their stored duration changes to 1000 ms. The pack is then marked animated and exposed.

Editing one of those still-source stickers reprocesses its original as a still. acceptEdit() writes a static asset and duration 0, but only increments the pack version. It leaves animated=true and exposed=true. The provider requires every sticker's duration-derived type to match the pack type. That filter now rejects the entire pack. The same invariant can break when adding a still to an already exposed animated pack, or an animation to an exposed static pack.

**Reproduction**
1. Create a pack with two stills and an animated WebP.
2. Expose it. A metadata query for the pack returns one row.
3. Edit text on a still-source sticker and save.
4. The save reports success and the pack remains marked exposed.
5. The same metadata query returns zero rows; asset opens relying on exposed() also fail.
6. Calling expose() again restores provider visibility.

The audit verifies this exact transition. What WhatsApp displays from its existing cache is not established; the directly confirmed defect is loss of provider access.

**Fix**
Maintain the uniform export invariant in the same logical operation that commits a changed sticker. Prefer separate editable/source assets and published export assets, so ordinary edits cannot invalidate the currently published snapshot. A smaller repair can wrap still output before committing it to an animated exposed pack. Define explicitly how adding animation changes a static pack. Avoid advertising “Visible to WhatsApp” when the provider rejects the pack.

**Regression acceptance**
Query metadata and open every asset after editing a wrapped still, adding either media kind, deleting the last true animation, and re-exposing. Verify filename, format, pack type and version agree without requiring a second manual add.

## F02 — Rotation or activity recreation loses unsaved editor work

**P1. Runtime confirmed.**
References: [EditorUi.kt:29–38](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/EditorUi.kt:29>), 50–57; [StudioModel.kt:123–131](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:123>).

The editor's draft, undo and redo are held in remember(target). They are not in the retained ViewModel, a saved-state holder, or a persistent draft. The ViewModel retains the original EditorTarget, so activity recreation reopens the editor with the old edit and silently drops the current one.

**Reproduction**
Open a still in the editor, enter “Unsaved audit text,” recreate the activity, return to the Text tool. The editor still exists but the text has disappeared. The final lifecycle audit performs this sequence successfully using the chip's semantic click action.

Crop, text placement, brush strokes, outline, trim and undo history share the same storage pattern. The reproduction directly tests text; the broader impact follows from the shared draft object. Saving a sticker and then recreating the activity, as the existing UI test does, does not cover this case.

**Fix**
Move the active draft into a ViewModel-owned editor session and update it as edits occur. Persist recoverable draft content for process death. Avoid putting unlimited brush coordinates into a Bundle; a draft record/file can store the bulk data, with a small identity in saved state.

**Regression acceptance**
Change each edit type, recreate the activity and verify exact draft equality. Test saved stickers and pending imports separately. Verify explicit discard still discards and explicit save commits only once.

## F03 — The outline algorithm dominates editor latency

**P1. Measured.**
References: [Edits.kt:183–195](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Edits.kt:183>); [EditorUi.kt:69–75](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/EditorUi.kt:69>), 133–184, 295–345.

The outline renderer draws the alpha bitmap at 24 angular offsets for every integer radius. An 8-pixel outline requires 192 drawBitmap operations; a 20-pixel outline requires 480. Each is applied to a 512 × 512 mask. This creates roughly 50 million and 126 million nominal pixel positions respectively, before clipping and implementation optimizations.

Measured by the existing production Renderer benchmark, five timed samples after warmup:

| Outline | Median render time | Relative to no outline |
| --- | ---: | ---: |
| 0 px | 2.69 ms | 1× |
| 8 px, default | 466.94 ms | about 174× |
| 20 px | 1,132.93 ms | about 422× |

The editor requests a fresh full render whenever draft changes, including each brush move, text move and slider value. Running it on Dispatchers.Default avoids directly executing that work on the UI thread, but does not make the preview responsive. Renderer.render is synchronous and has no cancellation checkpoints: cancellation can discard the result after expensive work has already happened. Rapid input can leave CPU work in flight and add allocation pressure.

For comparison, still WebP encoding of the fixture took about 13.5–15.0 ms. Optimizing the encoder first would miss the dominant cost. Whole PNG conversion took 517.6 ms in this run.

**Fix**
Replace repeated full bitmap draws with a bounded mask dilation/distance-based approach or an equivalent efficient implementation. Cache the source/crop transform and alpha mask; moving text should not regenerate the photo outline. Use a conflated preview worker so obsolete requests do not queue unlimited work, and check cancellation inside expensive loops. Maintain a separate high-quality export path if interactive previews need reduced resolution.

**Regression acceptance**
Measure render p50/p95 for 0/8/20 px, short and long stroke histories, and continuous input on a modest physical ARM phone. Verify outline shape, transparency and brush alignment against the current renderer. Set a concrete preview latency budget; a sub-50-ms p95 on the chosen baseline device would be a useful initial product target, not a currently achieved result.

## F04 — The editor can show changes that Save does not apply

**P2. Runtime confirmed.**
References: [MediaEngine.kt:217–242](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MediaEngine.kt:217>); [EditorUi.kt:69–75](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/EditorUi.kt:69>); [Edits.kt:23](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Edits.kt:23>), 132, 183–195.

Compatible 512 × 512 WebP is preserved byte-for-byte when edit equals the default Edit. That default includes outline=8. The editor nevertheless always calls Renderer.render(), which adds the still-image margin and outline.

Consequently, opening a compatible WebP with the default edit displays a rendered transformation, but saving with those same controls preserves the original bytes. A minor subsequent change switches into rendering and suddenly introduces the default margin/outline as well.

**Reproduction**
Decode static.webp, render Edit() as the editor does, then call MediaEngine.convert with Edit(). The audit confirms the saved bytes equal the input bytes and the preview bitmap differs from the saved bitmap.

**Fix**
Share an explicit “effective edit” decision between preview and conversion. Represent “preserve original” distinctly from “apply an 8-pixel outline,” or preview the byte-preserved source until the user enables an actual edit. Keep the valuable lossless import behavior while making the visual promise accurate.

**Regression acceptance**
Compare preview and saved composition for compatible static/animated WebP, PNG, and JPEG; include untouched save, rotate then undo, text then delete, and explicitly selected outline values.

## F05 — Repeated successful edits leak storage

**P2. Runtime confirmed.**
References: [StudioModel.kt:136–166](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:136>); [Data.kt:194–206](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Data.kt:194>), 233–240; [StudioModel.kt:206–214](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:206>) and 252–256.

Successful sticker edits create a unique output filename and update the row, but do not delete or retire the previous output. chooseTray() creates a unique PNG and replaces the pack's tray field without removing the old icon. Mixed-pack wrapping also leaves the former still asset behind. A tray created during a failed import has no corresponding cleanup in the per-item failure block.

The audit separately verifies that the old asset and the old tray remain after successful replacement and are no longer named by the current metadata.

This is unbounded storage retention over repeated editing, rather than an in-memory leak. A hundred edits to near-limit animated outputs can retain roughly 49 MiB of unused data for one sticker. At present old sticker outputs are removed on sticker deletion by the filename-prefix cleanup, and deleting a whole pack removes its directory. Old tray files survive until pack deletion.

**Fix**
After successful metadata commit, retire superseded generated files with a safe cleanup policy. Preserve originals. Consider a short retention window if a client may still hold a previously queried filename. Add a startup/maintenance reconciliation that computes the set of referenced assets, originals and trays and removes only verified unreferenced generated files. Track temporary tray creation alongside output/original creation for rollback cleanup.

**Regression acceptance**
Repeated edits and tray changes should reach a bounded storage footprint. Test failed commits, cancellation and process interruption; cleanup must never remove referenced originals or the active output.

## F06 — Pending imports cannot recover after process death

**P2. Code finding.**
References: [StudioModel.kt:30–44](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:30>), 66–98, 123–131; [MainActivity.kt:51](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MainActivity.kt:51>) and 78; [StudioUi.kt:59](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioUi.kt:59>).

Pending files, accepted import edits, destination selection and editor context are held only in memory. Copied files live in cache/imports, but there is no persisted manifest to restore their names, media metadata, edits or selection. A newly created ViewModel starts empty. MainActivity only reprocesses a share when savedInstanceState is null and consumes the share action.

If Android kills the background process during review, a reopened app loses the import workflow even if the copied files still exist. The startup cleanup eventually deletes abandoned imports older than 24 hours. This is distinct from F02: F02 occurs on activity recreation even while the ViewModel survives; F06 requires process loss.

**Fix**
Persist import sessions with file identity, edit JSON and destination. Restore the session on launch; reconcile missing cache files and explain any losses. Use app-private durable temporary storage if recovery is promised, and delete entries only after commit or explicit discard. Decide whether conversion itself should resume or merely restore the pending batch.

**Regression acceptance**
Import multiple files, edit them, background the app, kill the process without clearing app data, and relaunch. Confirm accepted edits and batch progress recover without duplicate committed stickers. Also test cache eviction.

## F07 — Provider reads scale with the entire library

**P2. Code finding; no large-library timing benchmark performed.**
References: [StickerProvider.kt:18–27](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StickerProvider.kt:18>), 66–68, 99–102, 133; [Data.kt:59–61](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Data.kt:59>), 210–221.

Every metadata query, sticker-list query and asset open calls exposed(). It loads all packs and their related stickers, checks assets on disk for exposed packs, sorts sticker lists, and only then selects the requested pack. Even an asset request for one known pack materializes the whole collection, including unexposed draft metadata.

A client fetching a pack with 30 stickers plus a tray can repeat this scan at least 31 times just for asset opens. With ten full exposed packs, that can cause roughly 9,300 sticker validations, with multiple filesystem calls per validation, before counting metadata/list requests. Unlimited drafts add database/object-allocation cost.

These are code-derived operation counts, not measured milliseconds. Small fixtures hide this scaling problem.

**Fix**
Use targeted DAO queries for the requested pack and exposed metadata. Query exposed-pack counts without hydrating all stickers. Validate outputs at commit time and retain versioned validation status, while keeping safe per-open filename and canonical-path checks. Do not trade away the existing whitelist protections.

**Regression acceptance**
Benchmark metadata and complete asset retrieval with 1, 10, 100 and 1,000 saved packs; only ten need be exposed. Assert unrelated drafts do not materially increase a single-pack asset-open cost.

## F08 — Animation previews run slower than the intended media clock

**P2. Code finding.**
References: [EditorUi.kt:416–444](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/EditorUi.kt:416>); [MediaEngine.kt:427–448](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MediaEngine.kt:427>).

Playback decodes a frame, renders it, publishes it, and then delays for that frame's full intended duration. Actual display cadence therefore includes decode/render/scheduling time in addition to the media delay. Each loop also adds an unconditional 8 ms. GIF/WebP previews of late trims restart decoding from the beginning on every loop.

The video diagnostic measured about 801.6 ms decoding, 76.5 ms rendering and 96.9 ms native encoding for 16 frames of an 800-ms source. Preview does not encode, but that decode/render work alone is substantial relative to the clip duration. Those stage totals do not directly measure preview wall time; they support the concern rather than constitute a playback benchmark.

**Fix**
Schedule against a monotonic playback clock and delay only until the next presentation deadline. Define frame dropping when processing falls behind. Use bounded caching or an appropriate decoder path for repeated short selections, especially late GIF/WebP trims, without retaining arbitrary full-resolution animations.

**Regression acceptance**
Measure the elapsed time of repeated preview loops against requested duration for video and variable-delay GIF/WebP. Test late trims and pause/resume. Verify timing remains correct when decode is deliberately slowed.

## F09 — Tight crops lose image detail before cropping

**P2. Code finding.**
References: [MediaEngine.kt:182–201](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MediaEngine.kt:182>), 245–250; [Edits.kt:120–144](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Edits.kt:120>).

The still export path calls firstFrame(), which reduces the entire image's longest edge to at most 1536 pixels. Renderer then crops that reduced bitmap and scales the crop into the sticker.

For a 6000-pixel-wide source, a 10% crop originally contains 600 pixels of horizontal detail. After whole-image downsampling it contains only about 154 pixels, which are enlarged into as much as 456 pixels of still-image content. The app retained the original but does not use its available crop resolution for final output.

**Fix**
Separate preview decoding from final export decoding. Determine the transformed crop and choose a decode sample size that preserves enough crop pixels, or decode the relevant region where supported. Handle EXIF orientation/rotation consistently. Keep explicit pixel/memory limits.

**Regression acceptance**
Use high-resolution text and line-pattern fixtures. Compare a tight crop to a crop-aware reference at the same 512-pixel output size; include rotated and portrait images and memory-constrained devices.

## F10 — A failed add attempt still consumes exposure state

**P3. Code finding.**
References: [StudioUi.kt:148–170](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioUi.kt:148>); [Data.kt:224–269](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Data.kt:224>).

The Add to WhatsApp action invokes repo.expose() before determining whether either WhatsApp package is installed. When neither is available, the app reports an installation instruction but leaves the pack exposed, increments its version, and may rewrite static assets into animated wrappers. Repeating this with different packs can consume the ten exposed-pack slots despite no successful handoff.

A failed/cancelled add request does not necessarily mean a pack must become unexposed: previously published packs may need to stay readable. The confirmed issue here is the avoidable mutation when no target application exists at all.

**Fix**
Resolve an available target before preparing exposure. Separate preparation, publication and add-request status; avoid rolling back an already published pack merely because a later user cancels. Make local exposure state understandable.

**Regression acceptance**
With neither WhatsApp package installed, tapping Add must not alter version, asset filenames or exposure count. Test one and two available targets, chooser cancellation, and launch exceptions.

## Additional risks requiring focused testing

### R01 — Memory limits are checked after some large allocations

References: [MediaEngine.kt:109–129](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MediaEngine.kt:109>), 174–176, 363–379, 388–400; studio.cpp:12–24 and 51–56.

GIF inspection reads the entire file and parses it before running the estimated memory check. Native WebP inspection reads the whole file before the Kotlin animation budget check. The estimate uses maximum heap, not current headroom or simultaneous editor/Coil allocations. A 4096 × 4096 ARGB frame is 64 MiB; the WebP FrameSource allocates a fresh bitmap on each next() call, including an allocation before checking end-of-stream.

Risk: large but permitted files may cause severe allocation churn or failures on busy/low-memory devices. Native vector/make_unique allocations lack an explicit C++ bad_alloc boundary. No out-of-memory crash was reproduced during this review.

Suggested work: validate cheap header/file bounds before full reads, budget all concurrent buffers, sample where safe, reuse frame buffers with clear ownership, and test under constrained heap and memory pressure. Do not use catch-all error handling as the main memory strategy.

### R02 — Cancellation has resource handoff and commit gaps

References: [EditorUi.kt:60–75](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/EditorUi.kt:60>) and 417–442; [StudioModel.kt:204–242](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:204>); [MediaEngine.kt:362–376](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/MediaEngine.kt:362>).

Native calls and rendering are synchronous. Preview resources returned from withContext can be created just as cancellation prevents their delivery to the outer try/finally. A FrameSource opened this way can contain a native decoder or video retriever; deterministic cleanup is important. The final copy of an import original can be up to 100 MiB and uses non-cancellable copyTo(), followed by a transaction without another ensureActive() after that copy.

Risk: delayed cancellation, a last import committing after the cancel tap, and resource retention during rapid editor open/close. These are code-level risks, not measured leaks or reproduced crashes. The existing cancellation test passed but does not fault-inject these boundaries.

Suggested work: keep resource creation and cleanup in the same cancellation-safe scope, define a clear commit boundary, copy with chunk-level cancellation, and add cancellation checkpoints before starting the transaction. Avoid reporting cancellation as guaranteed rollback after a commit has already happened.

### R03 — Mutations outside the conversion dialog are not serialized

References: [StudioModel.kt:47–56](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioModel.kt:47>) and 66–76; [StudioUi.kt:148–170](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/StudioUi.kt:148>); [Data.kt:194–206](<C:/Users/Nihil/Desktop/Gloria Repoz/Whatsapp Sticker Maker/app/src/main/java/com/stickerstudio/Data.kt:194>) and 224–269.

General actions launch independently; expose() performs file/native work inside a database transaction and has no busy UI state. Repeated add/tray/edit interactions can overlap. Some operations use stale Sticker or PackContents snapshots, while some reread state in a transaction.

Risk: redundant work, long database lock duration, stale metadata overwrites and file cleanup complications. No specific race was reproduced. Use a per-pack mutation queue or mutex, reread mutable metadata at commit time, and move expensive preparation outside transactions while validating the expected version before publication.

## Performance measurements and interpretation

Measurements below are from the raw log captured in this review. Production conversions ran three times per fixture; medians are the middle of those three values.

| Production conversion | Median | Observed range | Output |
| --- | ---: | ---: | --- |
| Noisy 1-second GIF | 1,829.7 ms | 1,757.3–1,897.1 ms | 449,370 bytes, 8 frames, 1000 ms |
| Disposal GIF | 15.0 ms | 14.5–23.5 ms | 812 bytes, 3 frames, 400 ms |
| Non-compatible animated WebP fixture | 14.8 ms | 14.6–28.5 ms | 812 bytes, 3 frames, 400 ms |
| Compatible animated WebP | 6.1 ms | 5.9–7.4 ms | 2,846 bytes, 3 frames, 400 ms |
| Rotated MP4, 0.8 seconds | 973.0 ms | 972.2–1,002.3 ms | 56,470 bytes, 16 frames, 800 ms |

Single-pass still diagnostics: compatible static WebP 1.72 ms; PNG 517.58 ms. The noisy-GIF output fits the configured limit. The current native optimization and adaptive retry changes appear effective on these fixtures; the photo editor is the clearest measured bottleneck.

CreationPerformanceTest also contains an explicitly labeled legacy-policy diagnostic. Its q/fps stage logs intentionally use the older sampler. Do not confuse those output sizes/frame counts with the current production conversion results above.

There are no measured startup, frame-jank, thermal, battery, peak-RSS, long-library, or physical ARM results in this review. Such results should not be inferred from a fast compatible-WebP copy.

## Security, data integrity and release observations

Positive properties observed in the implementation:
- No INTERNET permission in the main manifest.
- Originals and edits are app-private.
- Provider writes are rejected; current asset/tray filenames are whitelisted and canonical parent checks constrain paths.
- The provider declares WhatsApp's read permission.
- Input format is inspected from content, not trusted solely from filenames or MIME types.
- Input byte, dimension, duration and output-size limits exist.
- Conversion commits metadata after output creation; the existing failed-replacement test passes.
- Room foreign keys and transactional metadata operations protect several consistency boundaries.
- Native encoder/decoder ownership is usually paired with try/finally, and frame processing is incremental.

These controls reduce risk but do not establish that arbitrary malformed media is safe. Existing malformed-fixture tests are a small set, not fuzz coverage. The read-permission behavior across unrelated UIDs was documented in earlier validation but not independently rerun here.

The distributed APK is a debug/test build. Native codec optimization is enabled even in Debug, which is appropriate for these measurements. For broader distribution, review signing, debuggability, shrinker configuration and ABI delivery separately. The current app APK is 65,542,670 bytes (about 62.5 MiB); that is a packaging opportunity, not itself a correctness bug. No release-build test was performed.

No backup/restore is implemented and backup is explicitly disabled. This is documented product scope rather than an undisclosed bug, but uninstall remains destructive to the collection. No schema migration is currently needed at database version 1; exportSchema=false and absent migration tests become maintenance concerns when the schema changes.

The lint report's 18 warnings comprise seven dependency update notices, seven KTX suggestions, a backup-configuration advisory, and three launcher-resource warnings. Dependency notices are not vulnerability findings. Upgrade only with compatibility testing; this review does not establish which versions should replace the pinned ones.

## Recommended implementation order

1. **Protect work and published state:** F01 and F02; add lifecycle and provider-invariant regression tests before changing architecture.
2. **Fix interactive rendering:** F03, then unify preview/save semantics for F04. Measure again on a representative ARM phone.
3. **Bound storage and restore sessions:** F05 and F06, including crash-safe reconciliation and commit/cancel boundaries.
4. **Scale and improve fidelity:** F07–F09, followed by the lower-impact export-preflight fix F10.
5. **Restore a reliable UI test gate:** investigate the two repeatedly failing existing tests without relaxing assertions that represent real product expectations.
6. **Complete device acceptance:** static and mixed packs in WhatsApp and Business; re-edit/re-add/cache refresh; large files and long trims; background/process death; low memory/storage; Android API 29 and current supported device versions.

Before considering the review findings resolved, require a green full suite, repaired audit expectations, physical-device preview measurements, and actual WhatsApp import/update tests. The current full-suite failures and missing phone integration checks remain open.

## External contract reference

The official [WhatsApp Android sticker contract](https://github.com/WhatsApp/stickers/blob/main/Android/README.md) was checked during this review. It specifies uniform pack formats, 3–30 stickers, 512 × 512 dimensions, static/animated byte limits, animation timing limits, and tray-image requirements. It also documents image_data_version as the cache-update version. The app largely implements those export requirements; F01 concerns preserving them after later mutations.


