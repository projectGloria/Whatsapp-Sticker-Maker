package com.stickerstudio

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.bumptech.glide.gifdecoder.*
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object NativeWebp {
    init {
        System.loadLibrary("studio")
    }

    external fun info(path: String): IntArray

    external fun openDecoder(path: String): Long

    external fun nextFrame(handle: Long, bitmap: Bitmap): Int

    external fun closeDecoder(handle: Long)

    external fun openEncoder(quality: Float): Long

    external fun addFrame(handle: Long, bitmap: Bitmap, timestamp: Int)

    external fun finish(handle: Long, duration: Int): ByteArray

    external fun closeEncoder(handle: Long)
}

data class Incoming(
    val file: File,
    val name: String,
    val kind: String,
    val duration: Int,
    val width: Int,
    val height: Int,
) {
    val animated
        get() = kind != "still"
}

object MediaEngine {
    const val MAX_INPUT = 100L * 1024 * 1024

    suspend fun copyIncoming(context: Context, uri: Uri): Incoming {
        var name = "Shared media"
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        }
        val file =
            File.createTempFile(
                "incoming_",
                ".media",
                File(context.cacheDir, "imports").apply { mkdirs() },
            )
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_INPUT) { "Input exceeds 100 MB" }
                        out.write(buffer, 0, n)
                    }
                }
            } ?: error("File is no longer readable")
            return inspect(file, name)
        } catch (t: Throwable) {
            file.delete()
            if (t is kotlinx.coroutines.CancellationException) throw t
            throw IllegalArgumentException("$name: ${t.message}", t)
        }
    }

    fun inspect(file: File, name: String = file.name): Incoming {
        val header = ByteArray(16)
        val n = file.inputStream().use { it.read(header) }
        require(n >= 12) { "Empty or truncated media" }
        val magic = String(header, Charsets.ISO_8859_1)
        if (magic.startsWith("RIFF") && magic.substring(8, 12) == "WEBP") {
            val i = NativeWebp.info(file.path)
            require(i[0] in 1..4096 && i[1] in 1..4096) { "WebP dimensions exceed 4096 pixels" }
            if (i[3] != 0) checkAnimationMemory(file, i[0], i[1])
            return Incoming(
                file,
                name,
                if (i[3] != 0) "webp" else "still",
                if (i[3] != 0) i[2] else 0,
                i[0],
                i[1],
            )
        }
        if (magic.startsWith("GIF87a") || magic.startsWith("GIF89a")) {
            val data = file.readBytes()
            val parser = GifHeaderParser().setData(data)
            val h = parser.parseHeader()
            require(h.status == GifDecoder.STATUS_OK && h.numFrames > 0) { "Malformed GIF" }
            require(h.width in 1..4096 && h.height in 1..4096) {
                "GIF dimensions exceed 4096 pixels"
            }
            checkAnimationMemory(file, h.width, h.height)
            val decoder =
                StandardGifDecoder(
                    GifMemory(),
                    h,
                    ByteBuffer.wrap(data),
                    gifSample(h.width, h.height),
                )
            try {
                val duration =
                    (0 until decoder.frameCount).sumOf {
                        decoder.getDelay(it).coerceAtLeast(8).toLong()
                    }
                require(duration <= Int.MAX_VALUE) { "GIF duration is too long" }
                return Incoming(file, name, "gif", duration.toInt(), h.width, h.height)
            } finally {
                decoder.clear()
                parser.clear()
            }
        }
        if (magic.substring(4, 8) == "ftyp") {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                require(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                    "File has no video track"
                }
                val duration =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: error("No video duration")
                require(duration in 8..Int.MAX_VALUE.toLong()) { "Invalid video duration" }
                val w =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                val h =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                require(w in 1..8192 && h in 1..8192) { "Video dimensions exceed 8192 pixels" }
                return Incoming(file, name, "video", duration.toInt(), w, h)
            } finally {
                r.release()
            }
        }
        val png = header[0] == 0x89.toByte() && magic.substring(1, 4) == "PNG"
        val jpeg = header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()
        require(png || jpeg) { "Unsupported file content. Choose PNG, JPEG, WebP, GIF or MP4." }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        require(
            opts.outWidth > 0 &&
                opts.outHeight > 0 &&
                opts.outWidth.toLong() * opts.outHeight <= 100_000_000
        ) {
            "Malformed or oversized image"
        }
        return Incoming(file, name, "still", 0, opts.outWidth, opts.outHeight)
    }

    private fun checkAnimationMemory(file: File, w: Int, h: Int) {
        require(file.length() + w.toLong() * h * 12 < Runtime.getRuntime().maxMemory() * 3 / 5) {
            "Animation is too large for this phone. Resize it before importing."
        }
    }

    fun gifSample(w: Int, h: Int) = Integer.highestOneBit(max(1, max(w, h) / 1024))

    fun firstFrame(file: File): Bitmap {
        val media = inspect(file)
        if (media.kind == "video") {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                return r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, 1024, 1024)
                    ?: error("Cannot decode video")
            } finally {
                r.release()
            }
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = min(1f, 1536f / max(info.size.width, info.size.height))
            decoder.setTargetSize(
                max(1, (info.size.width * scale).toInt()),
                max(1, (info.size.height * scale).toInt()),
            )
        }
    }

    fun writeTray(bitmap: Bitmap, out: File) {
        val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        try {
            out.outputStream().use { require(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(out.length() <= 51200) { "Pack icon exceeds 50 KB" }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun convert(media: Incoming, edit: Edit, out: File, progress: (Float) -> Unit): Int {
        currentCoroutineContext().ensureActive()
        val unchanged =
            edit.copy(end = 10000) == Edit() && (!media.animated || edit.end >= media.duration)
        if (media.kind != "video" && unchanged) {
            val info = runCatching { NativeWebp.info(media.file.path) }.getOrNull()
            if (
                info != null &&
                    info[0] == 512 &&
                    info[1] == 512 &&
                    media.file.length() <=
                        (if (media.animated) Limits.ANIMATED_BYTES else Limits.STATIC_BYTES) &&
                    (!media.animated || info[2] in 8..10000 && info[4] >= 8)
            ) {
                if (media.animated)
                    FrameSource.open(media).use { source ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val frame = source.next() ?: break
                            frame.first.recycle()
                            progress(frame.second.toFloat() / info[2])
                        }
                    }
                else firstFrame(media.file).recycle()
                currentCoroutineContext().ensureActive()
                media.file.copyTo(out, true)
                progress(1f)
                return if (media.animated) info[2] else 0
            }
        }
        if (!media.animated) {
            val source = firstFrame(media.file)
            val rendered =
                try {
                    Renderer.render(source, edit)
                } finally {
                    source.recycle()
                }
            try {
                for ((index, q) in listOf(100, 90, 80, 65, 50, 35, 20, 10).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    out.outputStream().use {
                        require(rendered.compress(Bitmap.CompressFormat.WEBP, q, it)) {
                            "WebP encoding failed"
                        }
                    }
                    progress((index + 1) / 8f)
                    if (out.length() <= Limits.STATIC_BYTES) return 0
                }
                error("Image cannot fit within 100 KB. Simplify the image or crop it more tightly.")
            } finally {
                rendered.recycle()
            }
        }
        val start = edit.start.coerceIn(0, max(0, media.duration - 8))
        val end = min(edit.end, media.duration)
        require(end - start in 8..10000) { "Trim this animation to 10 seconds or less." }
        val attempts = listOf(85f to 20, 65f to 15, 45f to 12, 30f to 8, 15f to 6)
        for ((attempt, settings) in attempts.withIndex()) {
            currentCoroutineContext().ensureActive()
            val encoder = NativeWebp.openEncoder(settings.first)
            var timestamp = if (media.kind == "video") start else 0
            var frames = 0
            var nextSample = start
            val step = 1000 / settings.second
            try {
                FrameSource.open(media, start).use {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val frame = it.next() ?: break
                        try {
                            val frameStart = timestamp
                            timestamp = frame.second
                            if (
                                timestamp > start &&
                                    frameStart < end &&
                                    (frames == 0 || frameStart >= nextSample)
                            ) {
                                val at = max(start, frameStart) - start
                                val rendered = Renderer.render(frame.first, edit, false)
                                try {
                                    NativeWebp.addFrame(encoder, rendered, at)
                                    frames++
                                } finally {
                                    rendered.recycle()
                                }
                                nextSample = max(start, frameStart) + step
                            }
                            progress(
                                (attempt +
                                    (min(timestamp, end) - start).coerceAtLeast(0).toFloat() /
                                        (end - start)) / attempts.size
                            )
                        } finally {
                            frame.first.recycle()
                        }
                        if (timestamp >= end) break
                    }
                }
                require(frames > 0) { "No frames in selected range" }
                val bytes = NativeWebp.finish(encoder, end - start)
                if (bytes.size <= Limits.ANIMATED_BYTES) {
                    out.writeBytes(bytes)
                    val info = NativeWebp.info(out.path)
                    require(info[3] != 0) {
                        "This clip contains only one distinct frame. Import it as a still image instead."
                    }
                    require(info[4] >= 8) { "Animation contains a frame shorter than 8 ms" }
                    progress(1f)
                    return info[2]
                }
            } finally {
                NativeWebp.closeEncoder(encoder)
            }
        }
        error("Animation still exceeds 500 KB. Shorten the selected clip or crop it more tightly.")
    }
}

class GifMemory : GifDecoder.BitmapProvider {
    override fun obtain(w: Int, h: Int, config: Bitmap.Config) = Bitmap.createBitmap(w, h, config)

    override fun release(bitmap: Bitmap) = bitmap.recycle()

    override fun obtainByteArray(size: Int) = ByteArray(size)

    override fun release(bytes: ByteArray) {}

    override fun obtainIntArray(size: Int) = IntArray(size)

    override fun release(array: IntArray) {}
}

abstract class FrameSource : AutoCloseable {
    abstract fun next(): Pair<Bitmap, Int>?

    companion object {
        fun open(media: Incoming, startAt: Int = 0): FrameSource =
            when (media.kind) {
                "webp" ->
                    object : FrameSource() {
                        var handle = NativeWebp.openDecoder(media.file.path)

                        override fun next(): Pair<Bitmap, Int>? {
                            val b =
                                Bitmap.createBitmap(
                                    media.width,
                                    media.height,
                                    Bitmap.Config.ARGB_8888,
                                )
                            val t = NativeWebp.nextFrame(handle, b)
                            if (t < 0) {
                                b.recycle()
                                return null
                            }
                            return b to t
                        }

                        override fun close() {
                            if (handle != 0L) {
                                NativeWebp.closeDecoder(handle)
                                handle = 0
                            }
                        }
                    }
                "gif" ->
                    object : FrameSource() {
                        val data = media.file.readBytes()
                        val parser = GifHeaderParser().setData(data)
                        val decoder =
                            StandardGifDecoder(
                                GifMemory(),
                                parser.parseHeader(),
                                ByteBuffer.wrap(data),
                                MediaEngine.gifSample(media.width, media.height),
                            )
                        var index = 0
                        var time = 0
                        var closed = false

                        override fun next(): Pair<Bitmap, Int>? {
                            if (index >= decoder.frameCount) return null
                            decoder.advance()
                            val b = decoder.nextFrame ?: error("Malformed GIF frame ${index+1}")
                            require(decoder.status == GifDecoder.STATUS_OK) {
                                "Incomplete GIF frame"
                            }
                            time += decoder.nextDelay.coerceAtLeast(8)
                            index++
                            return b to time
                        }

                        override fun close() {
                            if (!closed) {
                                closed = true
                                decoder.clear()
                                parser.clear()
                            }
                        }
                    }
                "video" ->
                    object : FrameSource() {
                        val retriever =
                            MediaMetadataRetriever().apply { setDataSource(media.file.path) }
                        var time = startAt.coerceIn(0, media.duration)
                        var closed = false

                        override fun next(): Pair<Bitmap, Int>? {
                            if (time >= media.duration) return null
                            val b =
                                retriever.getScaledFrameAtTime(
                                    time * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST,
                                    1024,
                                    1024,
                                ) ?: error("Cannot decode video frame")
                            time = min(time + 50, media.duration)
                            return b to time
                        }

                        override fun close() {
                            if (!closed) {
                                closed = true
                                retriever.release()
                            }
                        }
                    }
                else -> error("Not animated")
            }
    }
}
