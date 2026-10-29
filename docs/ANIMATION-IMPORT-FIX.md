# Animated import fix — 1.0.3-test

29 October 2026. Updates `com.stickerstudio.test`, version code 4, using the same signing certificate and database schema as 1.0.2-test.

## Changes

- Compile the entire bundled native codec and JNI wrapper with `-O2` in the delivered debuggable build, on ARM64, ARMv7 and x86-64. The old build had no compiler optimization. CMake applies the option before adding libwebp, so it covers the codec itself.
- Use libwebp's default disposal heuristics instead of forcing the slow `minimize_size` search. Keep encoder method 3 and the initial quality 85 / 20 fps setting.
- Reuse one 1 MiB native pixel buffer per encoder. Opaque pixels bypass unnecessary unpremultiplication divisions. Partial alpha still takes the original unpremultiplication path.
- Skip one intermediate compression setting when the previous output exceeds four times the byte budget. Modest overshoots still follow the gradual fallback sequence. Every path retains the lowest-quality fallback and terminates after bounded attempts.
- Sample against an absolute clock, fixing the old drift that reduced a requested 15 fps to 10 fps on a 20 fps source. Preserve source-frame boundaries and variable frame delays. Do not duplicate low-rate source frames.
- Add a quality-only 25 / 8 fps fallback before dropping to 15 / 6 fps. This lets near-budget clips keep eight output frames per second after correcting sampling.
- Retrieve video frames at the selected retry frame rate instead of retrieving twenty per second and discarding frames afterward. Use rational timestamp calculation and 64-bit arithmetic to avoid accumulated rounding and overflow.
- Check coroutine cancellation after rendering and assembly, before continuing native work or writing a successful output. Native calls remain synchronous, so cancellation waits for the current call to return.

The change keeps streaming frame processing and does not cache complete decoded animations. The compatible-WebP byte-preserving path, source originals, pack format and database schema remain intact. Still-image outlines and the photo editor were outside this animation fix.

## Measurements

Android 10 / API 29 x86-64 emulator, same fixtures as the baseline investigation. These are emulator measurements, not physical-phone speed guarantees.

| Case | Before | Final build |
| --- | ---: | ---: |
| Difficult one-second GIF, full conversion | 17.20–17.21 s | 1.72–1.79 s; median 1.776 s over three runs |
| Difficult GIF output | 453,122 bytes, 7 frames | 449,370 bytes, 8 frames |
| Same GIF, fixed legacy retry/sampling diagnostic | 17.20–17.21 s | 1.71 s, same output byte sizes at each setting |
| Compatible animated WebP | 3.3–3.6 ms | 2.8–3.8 ms, byte-preserved |
| Simple disposal GIF | — | 15.5–16.4 ms, 3 frames / 400 ms |
| 0.8-second rotated video | 2.15–2.16 s | 1.10–1.16 s, 16 frames / 800 ms |

The fixed legacy-policy diagnostic isolates the native improvements from retry and sampling changes: it produces the same per-attempt sizes as before, roughly ten times faster. The final production-policy result also emits one more frame for the difficult GIF. That synthetic noisy clip uses quality 25 instead of the old quality 30, so these are not pixel-identical final outputs. Simple artwork retains the initial high-quality attempt, and existing valid WebP files are copied byte-for-byte.

## Validation

- Six local unit tests: pack boundaries, exact requested frame counts, variable-delay sampling, nonzero trims, rational video timing and bounded adaptive fallback.
- Android 10: all 21 instrumented tests pass, comprising 18 processing/data/UI regressions and three diagnostic measurements.
- Android 16 / API 36: all 18 processing/data/UI regression tests pass; diagnostic timings were intentionally limited to Android 10 for baseline comparison.
- New native round-trip regression checks transparent, partially transparent and opaque pixels across changing animation frames.
- New video-source regression checks the number of retrieved frames at 6 fps, increasing timestamps and exact source end time.
- Existing tests cover GIF disposal, trim timing, rotation, byte-preserving WebP import, size limits, cancellation, failed replacement, mixed-source packs, provider restrictions, persistence and full UI flows.
- Lint: zero errors, 18 nonfatal warnings for pinned dependencies, optional KTX suggestions, backup configuration and existing launcher resources.
- APK signature: v2 verified; certificate matches 1.0.2-test.
- ZIP alignment: 16 KiB check passes. All LOAD segments in all six shipped native libraries have 0x4000 alignment.
- ARM64, ARMv7 and x86-64 compile successfully. Runtime measurements use x86-64; physical ARM/WhatsApp checks remain pending.

Evidence is in `docs/validation/animation-fix-*`, with the original performance baseline retained separately. `CreationPerformanceTest.profileActualAnimationConversion` runs the real production conversion three times per fixture; the legacy diagnostic deliberately retains its old policy for comparison.

## Install

Install `deliverables/Nihil-and-catz.apk` over the existing Nihil and catz test app. Do not uninstall it first, because uninstalling deletes its collection. The previous test APK is retained as `deliverables/Nihil-and-catz-1.0.2-before-animation-fix.apk`.
