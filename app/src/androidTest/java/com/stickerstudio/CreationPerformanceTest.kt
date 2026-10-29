package com.stickerstudio

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic timings, deliberately without device-dependent speed assertions. */
@RunWith(AndroidJUnit4::class)
class CreationPerformanceTest {
    @Test
    fun profileActualAnimationConversion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "conversion_perf_${UUID.randomUUID()}")
        check(dir.mkdirs())
        try {
            for (name in listOf("noisy.gif", "disposal.gif", "animated.webp", "compatible.webp", "rotated.mp4")) {
                val file = File(dir, name)
                instrumentation.context.assets.open(name).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val media = MediaEngine.inspect(file)
                repeat(3) { iteration ->
                    val out = File(dir, "output.webp")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val duration = MediaEngine.convert(media, Edit(), out) {}
                    val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    check(out.length() <= Limits.ANIMATED_BYTES)
                    check(duration == media.duration)
                    var frames = 0
                    FrameSource.open(MediaEngine.inspect(out)).use { source ->
                        while (true) {
                            val frame = source.next() ?: break
                            frame.first.recycle()
                            frames++
                        }
                    }
                    Log.i("StickerPerf", "actual file=$name iteration=$iteration ms=$ms bytes=${out.length()} duration=$duration frames=$frames")
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun profileVideoStages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("video_perf_", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("rotated.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val media = MediaEngine.inspect(file)
            var decodeNs = 0L
            var renderNs = 0L
            var encodeNs = 0L
            var count = 0
            val encoder = NativeWebp.openEncoder(85f)
            try {
                FrameSource.open(media).use { frames ->
                    while (true) {
                        var tick = SystemClock.elapsedRealtimeNanos()
                        val frame = frames.next()
                        decodeNs += SystemClock.elapsedRealtimeNanos() - tick
                        if (frame == null) break
                        try {
                            tick = SystemClock.elapsedRealtimeNanos()
                            val rendered = Renderer.render(frame.first, Edit(), false)
                            renderNs += SystemClock.elapsedRealtimeNanos() - tick
                            try {
                                tick = SystemClock.elapsedRealtimeNanos()
                                NativeWebp.addFrame(encoder, rendered, count * 50)
                                encodeNs += SystemClock.elapsedRealtimeNanos() - tick
                            } finally { rendered.recycle() }
                        } finally { frame.first.recycle() }
                        count++
                    }
                }
                val bytes = NativeWebp.finish(encoder, media.duration)
                Log.i("StickerPerf", "video frames=$count decodeMs=${decodeNs / 1e6} renderMs=${renderNs / 1e6} addMs=${encodeNs / 1e6} bytes=${bytes.size}")
            } finally { NativeWebp.closeEncoder(encoder) }
        } finally { file.delete() }
    }

    @Test
    fun profileCreationStages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "perf_${UUID.randomUUID()}")
        check(dir.mkdirs())
        fun fixture(name: String): File = File(dir, name).also { output ->
            instrumentation.context.assets.open(name).use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
        fun report(message: String) = Log.i("StickerPerf", message)
        fun now() = SystemClock.elapsedRealtimeNanos()
        fun ms(nanos: Long) = nanos / 1_000_000.0
        try {
            val still = MediaEngine.inspect(fixture("source.png"))
            val source = MediaEngine.firstFrame(still.file)
            try {
                for (outline in listOf(0f, 8f, 20f)) {
                    Renderer.render(source, Edit(outline = outline)).recycle()
                    val samples = (0 until 5).map {
                        val start = now()
                        Renderer.render(source, Edit(outline = outline)).recycle()
                        ms(now() - start)
                    }.sorted()
                    report("render outline=$outline medianMs=${samples[2]}")
                }
                val rendered = Renderer.render(source, Edit())
                try {
                    for (q in listOf(100, 90, 80)) {
                        val output = File(dir, "still_$q.webp")
                        val start = now()
                        @Suppress("DEPRECATION")
                        output.outputStream().use { rendered.compress(Bitmap.CompressFormat.WEBP, q, it) }
                        report("stillEncode quality=$q ms=${ms(now() - start)} bytes=${output.length()}")
                    }
                } finally { rendered.recycle() }
            } finally { source.recycle() }
            for (name in listOf("static.webp", "compatible.webp", "source.png", "rotated.mp4")) {
                val input = MediaEngine.inspect(fixture(name))
                val start = now()
                MediaEngine.convert(input, Edit(), File(dir, "converted_$name.webp")) {}
                report("convert file=$name ms=${ms(now() - start)}")
            }
            // Keep the legacy retry/sampling policy for comparable native-stage timings.
            val media = MediaEngine.inspect(fixture("noisy.gif"))
            val end = min(10000, media.duration)
            for ((quality, fps) in listOf(85f to 20, 65f to 15, 45f to 12, 30f to 8, 15f to 6)) {
                val start = now()
                val encoder = NativeWebp.openEncoder(quality)
                var decodeNs = 0L
                var renderNs = 0L
                var encodeNs = 0L
                var decoded = 0
                var encoded = 0
                var timestamp = 0
                var nextSample = 0
                try {
                    FrameSource.open(media).use { frames ->
                        while (true) {
                            var tick = now()
                            val frame = frames.next()
                            decodeNs += now() - tick
                            if (frame == null) break
                            decoded++
                            val frameStart = timestamp
                            timestamp = frame.second
                            try {
                                if (timestamp > 0 && frameStart < end && (encoded == 0 || frameStart >= nextSample)) {
                                    tick = now()
                                    val rendered = Renderer.render(frame.first, Edit(), false)
                                    renderNs += now() - tick
                                    try {
                                        tick = now()
                                        NativeWebp.addFrame(encoder, rendered, max(0, frameStart))
                                        encodeNs += now() - tick
                                    } finally { rendered.recycle() }
                                    encoded++
                                    nextSample = frameStart + 1000 / fps
                                }
                            } finally { frame.first.recycle() }
                            if (timestamp >= end) break
                        }
                    }
                    val tick = now()
                    val bytes = NativeWebp.finish(encoder, end)
                    val finishNs = now() - tick
                    report("animation q=$quality fps=$fps decoded=$decoded encoded=$encoded decodeMs=${ms(decodeNs)} renderMs=${ms(renderNs)} addMs=${ms(encodeNs)} finishMs=${ms(finishNs)} totalMs=${ms(now() - start)} bytes=${bytes.size}")
                    if (bytes.size <= Limits.ANIMATED_BYTES) break
                } finally { NativeWebp.closeEncoder(encoder) }
            }
        } finally { dir.deleteRecursively() }
    }
}

