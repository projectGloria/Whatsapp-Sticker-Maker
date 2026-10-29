# Sticker creation performance investigation

29 October 2026. Historical baseline investigation of version 1.0.2, before optimization. At that stage production code and deliverable APKs were not changed. The animation fixes subsequently delivered in version 1.0.3 are documented in [ANIMATION-IMPORT-FIX.md](ANIMATION-IMPORT-FIX.md). The observations below describe the earlier implementation.

## Main conclusion

There are two different primary bottlenecks:

1. Still images and photo-editor interactions: the default white outline repeatedly composites a full bitmap hundreds of times.
2. Animation: expensive native encoding, built without compiler optimization, followed by repeated complete encoding attempts until the output fits.

Video also pays substantial per-frame retrieval costs. Database writes and file copying are secondary candidates; neither explains the measured conversion costs. Moving conversion to a background dispatcher has already been done and does not make this computation faster.

## Evidence and measurement limits

Measured on the connected Android 10 / API 29 x86-64 emulator using `CreationPerformanceTest`, against the installed test app. The timings below are representative diagnostic measurements, not physical-phone performance promises. Render medians use five timed iterations after one warm-up; conversion and per-attempt numbers are individual observations, with a repeat run checking consistency. Fixtures are small synthetic assets, not the user's actual slow imports. No ARM device was connected.

Evidence:

- `app/src/androidTest/java/com/stickerstudio/CreationPerformanceTest.kt`: repeatable stage measurements; no device-dependent speed assertions.
- `docs/validation/creation-performance-android-10.log`: captured stage timings.
- `docs/validation/creation-performance-run.txt`: final diagnostic test results.
- Existing `docs/validation/android-10.xml` and `android-16.xml`: the complex animation test already took 21.636 s and 20.455 s respectively.

| Operation | Observed time |
| --- | ---: |
| Still render, outline disabled | 1.35–3.10 ms median |
| Still render, default 8 px outline | 423–429 ms median |
| Still render, maximum UI outline of 20 px | 1,031 ms median |
| PNG conversion, default edits | 441–451 ms |
| Compatible static WebP conversion | 2.4–2.5 ms |
| Compatible animated WebP conversion, three frames | 3.3–3.6 ms |
| 0.8 s rotated MP4 conversion | 2.15–2.16 s |

The PNG fixture's WebP compression took only 5 ms at quality 100, or 12–19 ms at quality 90. In this case the outline, rather than compression, dominates. These encoding times do not represent detailed photos.

### Detailed one-second animation

The noisy GIF has twenty 256 × 256 frames. Rendering expands each selected frame to the required 512 × 512 output.

| Retry | Requested fps | Decoded frames | Encoded frames | Decode | Render | Native addFrame | Whole attempt | Bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| quality 85 | 20 | 20 | 20 | 29 ms | 25 ms | 7,663 ms | 7,729 ms | 2,669,118 |
| quality 65 | 15 | 20 | 10 | 20 ms | 11 ms | 3,562 ms | 3,600 ms | 954,908 |
| quality 45 | 12 | 20 | 10 | 19 ms | 12 ms | 3,494 ms | 3,531 ms | 784,790 |
| quality 30 | 8 | 20 | 7 | 19 ms | 8 ms | 2,306 ms | 2,337 ms | 453,122 |

Total: approximately **17.20 seconds**, 80 source frames decoded and 47 frames encoded to retain seven frames. The first three attempts consume 14.86 seconds, about 86% of the total, and all exceed the 512,000-byte limit. Native `addFrame` accounts for approximately 99% of attempt time. This call includes JNI pixel conversion and the WebP animation encoder; this diagnostic does not separate those two costs. Assembly takes less than 2 ms per attempt.

The video stage test separately measured sixteen frames: approximately 810–834 ms retrieving frames, 48–51 ms rendering, and 1,208–1,258 ms in native `addFrame`. Both decoding and encoding matter for this video.

## Findings

### 1. The delivered native codec is compiled without optimization

`scripts/build.ps1` builds `assembleDebug` and copies that APK to `deliverables/Nihil-and-catz.apk`. `app/build.gradle.kts` defines no optimized native configuration for debug. The generated `app/.cxx/Debug/1u6dq6k3/x86_64/build.ninja` compile flags for both `studio.cpp` and libwebp contain debug symbols and no `-O` optimization flag. Clang therefore uses its default unoptimized compilation. The ARM build configurations also select Debug.

This affects the bundled animated WebP codec and the native pixel loop. Static `Bitmap.compress` uses Android's codec and is not the same build. An optimized native build is the first animation experiment to run. Its speedup has not yet been measured; build configuration evidence alone does not justify a numerical speedup claim.

### 2. The encoder explicitly selects slower size minimization

`app/src/main/cpp/studio.cpp:71` sets `o.minimize_size=1`, with encoding method 3. The bundled libwebp header describes minimize_size as slow. In `libwebp/src/mux/anim_encode.c`, it disables keyframes and can try both disposal candidates instead of selecting one.

The default `WebPConfig` also leaves `thread_level=0`. This is another experiment, not a guarantee of improvement for 512 × 512 frames. Compare native optimization first, then minimize_size off, lower encoding method, and thread level one, independently. Faster settings can increase bytes and thereby cause more retries, so assess complete conversion time and final quality, not just per-frame speed.

### 3. Retry policy spends most of its time producing discarded outputs

`MediaEngine.kt:272` always starts at quality 85 / 20 fps and tries up to five whole conversions. The byte limit is checked only after every selected frame is encoded. Each attempt reopens the source, decodes it, renders again and encodes again. A GIF or WebP trim also decodes from the beginning on every attempt to preserve frame composition.

For the measured noisy GIF, caching decode/render results would save very little: those stages together cost under 200 ms, while native calls cost about 17 seconds. The main improvement is reducing the cost and number of native encodes. Use a small complexity estimate or pilot encode to choose a more realistic initial budget, followed by an adaptive quality/frame-rate policy. Preserve a high-quality route for simple animations that already fit on their first attempt.

Do not solve this by storing every full frame unboundedly: a 512 × 512 ARGB frame is 1 MiB; ten seconds at 20 fps is about 200 MiB before encoder/decoder memory. Any reuse needs a strict memory or disk budget.

### 4. Default outline performs 192 bitmap draws per render

`Edits.kt:183–195` loops over every integer outline radius and twenty-four angles, drawing the extracted alpha bitmap each time. The default is 8 px, so even a newly imported PNG pays for 192 draws. At the UI maximum of 20 px, that becomes 480 draws. Measured cost is about 0.43 s and 1.03 s respectively, per render.

Replace repeated full-canvas compositing with a bounded alpha dilation or distance-based outline. A separable max filter is fast but gives a different shape from a circular outline; compare edge shape, transparency and antialiasing explicitly. Cache the base crop/mask and outlined result separately from text placement so dragging text does not regenerate the outline.

### 5. Interactive editing reruns all still rendering for every draft change

`EditorUi.kt:68–75` keys `produceState` on the entire `draft`. Text dragging, brush movement and sliders all launch a complete `Renderer.render`, including the default expensive outline and replay of every brush stroke. Renderer has no cooperative cancellation checks, so cancelling the coroutine does not interrupt an already executing render. Replaced preview and source bitmaps also rely on garbage collection rather than explicit bounded ownership.

Use a layered preview, reuse unaffected layers, and coalesce rapid edits to the latest draft. Use a lower-cost interactive preview while dragging and render the final image after the interaction. Bitmap reuse/recycling must respect Compose ownership; recycling an image still being displayed can break rendering.

### 6. Video repeatedly requests closest frames, including ones it will discard

`MediaEngine.kt:419–432` advances video time by a fixed 50 ms and calls `getScaledFrameAtTime(OPTION_CLOSEST, 1024, 1024)` each time. The outer retry loop applies its lower frame-rate filter only after retrieving the bitmap. Thus a low-fps retry still retrieves 20 frames per second and discards many of them. Each retrieval is a timestamp request rather than an app-managed sequential decoder; its internal seek/decode cost depends on the source and device.

Pass the requested output timestamps into the video source so it retrieves only needed frames. For longer or high-resolution clips, evaluate sequential MediaExtractor/MediaCodec decoding. Decode/render dimensions should follow crop requirements; blindly reducing to 512 before a tight crop can visibly lose detail. Validate display rotation and timing.

### 7. The requested frame rates do not match emitted frame rates

`MediaEngine.kt:301` schedules the next sample relative to the frame just accepted, rather than to a fixed sampling timeline. On a 20 fps source, frames arrive at 0, 50, 100, 150 ms, etc. Requesting 15 fps gives a 66 ms interval, accepts 0 then 100 then 200 ms, and produces 10 fps. Requesting 12 fps also produces 10 fps. This was observed directly in the measurements.

Use an absolute sampling schedule, select the source frame covering each target time, and preserve output timestamps and final duration. This is both a quality issue and an optimization constraint: fixing the schedule alone can encode more frames, so tune the byte budget afterward.

### 8. Batches and unchanged saves amplify the wait

`StudioModel.kt:181` converts imports sequentially. Thirty copies of the measured PNG would spend roughly thirteen seconds in conversion alone; this is an extrapolation, not a measured thirty-file benchmark. Thirty complex animations would take much longer. Each import also copies the original from cache into permanent storage and commits/notifies individually. Those operations matter for large files, but are lower priority than the measured rendering/encoding costs.

`acceptEdit` also converts a saved sticker even if its edit instructions have not changed. Detect unchanged edits and keep the existing asset. Consider an atomic same-filesystem move for originals only with explicit cancellation/failure recovery, so a failed import remains retryable.

Parallelizing many animations immediately would increase memory pressure and may compete with the codec's own threads. After the individual pipeline is efficient, benchmark bounded concurrency with a memory budget. Keep ordered database commits and the existing cancellation/partial-success guarantees.

### 9. Progress and playback make the process feel slower

Animation progress divides every attempt by five even when only one succeeds, so a successful first attempt travels toward 20% and then jumps to 100%. It is based on source time rather than encoding effort. A long native call cannot report progress or observe cancellation until it returns. Show the current file and processing stage; use indeterminate progress where total work is unknown.

`EditorUi.kt:429` delays for the full frame duration after decoding and rendering. Playback therefore takes frame duration plus processing time and slows down as work increases. Schedule against a monotonic playback clock and skip late preview frames where appropriate. This affects preview responsiveness rather than export throughput.

## Recommended implementation order

1. **Optimize native compilation**, keeping package ID, signing and collection compatibility. Benchmark before/after on identical inputs. Compare encoder settings individually, measuring final bytes, visual quality and total retries.
2. **Replace the outline algorithm and cache preview layers.** This directly targets the measured still-image and editor bottleneck. Preserve intended edge appearance with image comparisons.
3. **Make compression adaptive and fix the sampling timeline together.** Avoid several doomed full encodes while keeping simple animations smooth. Test both compressible cartoons and high-detail/noisy clips.
4. **Decode video only at required timestamps**, then consider a sequential decoder if measurements justify it. Add bounded frame/buffer reuse.
5. **Skip unchanged saves, improve stage feedback and cancellation**, then evaluate bounded batch concurrency and storage optimizations.

## Validation needed before an optimized APK

Measure optimized and current versions on an ARM phone with the same static photos, transparent artwork, variable-delay GIFs, animated WebP and long-GOP video, including trimmed ranges. Track total latency, p50/p95 across repeated runs, peak memory, retries, actual output frame cadence, bytes and cancellation latency. Compare output visually. Keep existing size, transparency, duration, disposal, rotation, byte-preserving import and failed-replacement checks.

The diagnostic harness does not mutate packs or saved sticker assets. It copies test fixtures into unique temporary files and removes those files afterward. It is a profiling aid, not a production change or a complete benchmark suite.
