package com.stickerstudio

import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repo = Repository(context)
    private val created = mutableListOf<String>()
    private val temp = File(context.cacheDir, "test_${UUID.randomUUID()}").apply { mkdirs() }

    private fun fixture(name: String): File =
        File(temp, name).also { file ->
            instrumentation.context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

    private fun pack(): PackContents {
        val p = repo.create("Test ${UUID.randomUUID()}", false)
        created += p.id
        val dir = repo.folder(p.id)
        val source = fixture("static.webp")
        val bitmap = MediaEngine.firstFrame(source)
        MediaEngine.writeTray(bitmap, File(dir, "tray.png"))
        bitmap.recycle()
        repeat(3) { i ->
            source.copyTo(File(dir, "$i.webp"))
            source.copyTo(File(dir, "$i.media"))
            repo.dao.insert(
                Sticker(
                    packId = p.id,
                    asset = "$i.webp",
                    original = "$i.media",
                    kind = "still",
                    position = i,
                )
            )
        }
        repo.dao.update(p.copy(tray = "tray.png"))
        return repo.dao.pack(p.id)!!
    }

    @After
    fun cleanup() {
        created.forEach { repo.removePack(it) }
        temp.deleteRecursively()
    }

    @Test
    fun stillEditingTransparencyAndEditRoundTrip() = runBlocking {
        val src = MediaEngine.firstFrame(fixture("source.png"))
        val edits =
            Edit(
                outline = 0f,
                text = "Hi",
                textX = .5f,
                textY = .85f,
                strokes =
                    listOf(Stroke(listOf(BrushPoint(.2f, .5f), BrushPoint(.4f, .5f)), 80f, false)),
            )
        assertEquals(edits, Edit.parse(edits.json()))
        val erased = Renderer.render(src, Edit(outline = 0f, strokes = edits.strokes))
        assertEquals(0, Color.alpha(erased.getPixel(150, 256)))
        val restored =
            Renderer.render(
                src,
                Edit(
                    outline = 0f,
                    strokes =
                        edits.strokes +
                            Stroke(listOf(BrushPoint(.2f, .5f), BrushPoint(.4f, .5f)), 80f, true),
                ),
            )
        assertTrue(Color.alpha(restored.getPixel(150, 256)) > 240)
        val outlined = Renderer.render(src, Edit(outline = 8f))
        assertEquals(0, Color.alpha(outlined.getPixel(0, 0)))
        val output = File(temp, "edited.webp")
        MediaEngine.convert(MediaEngine.inspect(fixture("source.png")), edits, output) {}
        assertTrue(output.length() <= Limits.STATIC_BYTES)
        assertEquals(512, NativeWebp.info(output.path)[0])
        src.recycle()
        erased.recycle()
        restored.recycle()
        outlined.recycle()
    }

    @Test
    fun cropAndRotationChangeComposition() {
        val src = MediaEngine.firstFrame(fixture("source.png"))
        val crop = Renderer.render(src, Edit(right = .5f, outline = 0f))
        val pixel = crop.getPixel(256, 256)
        assertTrue(Color.red(pixel) > 200)
        assertTrue(Color.blue(pixel) < 30)
        val rotated = Renderer.render(src, Edit(turns = 1, outline = 0f))
        assertTrue(Color.red(rotated.getPixel(256, 130)) > 200)
        assertTrue(Color.blue(rotated.getPixel(256, 370)) > 200)
        src.recycle()
        crop.recycle()
        rotated.recycle()
    }

    @Test
    fun compatibleStaticAndAnimatedWebpAreBytePreserved() = runBlocking {
        for (name in listOf("static.webp", "compatible.webp")) {
            val input = fixture(name)
            val output = File(temp, "copy_$name")
            MediaEngine.convert(MediaEngine.inspect(input), Edit(), output) {}
            assertArrayEquals(input.readBytes(), output.readBytes())
        }
    }

    @Test
    fun gifDisposalAndAnimatedTiming() = runBlocking {
        val media = MediaEngine.inspect(fixture("disposal.gif"))
        assertEquals(400, media.duration)
        FrameSource.open(media).use { source ->
            val first = source.next()!!
            assertEquals(80, first.second)
            first.first.recycle()
            val second = source.next()!!
            assertEquals(200, second.second)
            assertEquals(0, Color.alpha(second.first.getPixel(15, 40)))
            second.first.recycle()
            val third = source.next()!!
            assertEquals(400, third.second)
            assertEquals(0, Color.alpha(third.first.getPixel(45, 40)))
            third.first.recycle()
            assertNull(source.next())
        }
        val output = File(temp, "gif.webp")
        val duration = MediaEngine.convert(media, Edit(), output) {}
        val info = NativeWebp.info(output.path)
        assertEquals(512, info[0])
        assertEquals(512, info[1])
        assertEquals(400, duration)
        assertTrue(info[3] != 0)
        assertTrue(info[4] >= 8)
        assertTrue(output.length() <= Limits.ANIMATED_BYTES)
    }

    @Test
    fun animatedWebpTrimAndVideoRotation() = runBlocking {
        val webp = MediaEngine.inspect(fixture("animated.webp"))
        val out = File(temp, "trim.webp")
        MediaEngine.convert(webp, Edit(start = 80, end = 400, right = .95f), out) {}
        assertEquals(320, NativeWebp.info(out.path)[2])
        val media = MediaEngine.inspect(fixture("rotated.mp4"))
        assertEquals("video", media.kind)
        val frame = MediaEngine.firstFrame(media.file)
        assertTrue("Rotation must be reflected in the decoded frame", frame.height > frame.width)
        frame.recycle()
        val videoOut = File(temp, "video.webp")
        MediaEngine.convert(media, Edit(start = 200, end = 600), videoOut) {}
        assertEquals(400, NativeWebp.info(videoOut.path)[2])
        assertTrue(videoOut.length() <= Limits.ANIMATED_BYTES)
    }

    @Test
    fun complexAnimationRetriesCompressionToMeetSizeLimit() = runBlocking {
        val media = MediaEngine.inspect(fixture("noisy.gif"))
        val output = File(temp, "noisy.webp")
        var progress = 0f
        var retried = false
        MediaEngine.convert(media, Edit(), output) {
            progress = maxOf(progress, it)
            if (it > .2f && it < 1f) retried = true
        }
        assertTrue(output.length() <= Limits.ANIMATED_BYTES)
        assertEquals(1000, NativeWebp.info(output.path)[2])
        assertEquals(1f, progress, 0f)
        assertTrue("Detailed animation should exercise a compression retry", retried)
    }

    @Test
    fun nativeEncoderPreservesPartialAlphaAndTransparentPixels() {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val encoder = NativeWebp.openEncoder(85f)
        val output = File(temp, "alpha.webp")
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            canvas.drawRect(40f, 40f, 240f, 240f, Paint().apply { color = Color.argb(128, 255, 0, 0) })
            NativeWebp.addFrame(encoder, bitmap, 0)
            canvas.drawRect(300f, 300f, 400f, 400f, Paint().apply { color = Color.BLUE })
            NativeWebp.addFrame(encoder, bitmap, 100)
            output.writeBytes(NativeWebp.finish(encoder, 200))
            val media = MediaEngine.inspect(output)
            assertEquals(200, media.duration)
            FrameSource.open(media).use { source ->
                val first = source.next()!!
                try {
                    assertEquals(0, Color.alpha(first.first.getPixel(0, 0)))
                    val pixel = first.first.getPixel(100, 100)
                    assertEquals(128, Color.alpha(pixel))
                    assertTrue(Color.red(pixel) > 230)
                    assertEquals(0, Color.alpha(first.first.getPixel(350, 350)))
                } finally { first.first.recycle() }
                val second = source.next()!!
                try {
                    assertEquals(255, Color.alpha(second.first.getPixel(350, 350)))
                    assertTrue(Color.blue(second.first.getPixel(350, 350)) > 230)
                } finally { second.first.recycle() }
            }
        } finally {
            NativeWebp.closeEncoder(encoder)
            bitmap.recycle()
        }
    }

    @Test
    fun lowerRateVideoRetrievesOnlyRequiredFrames() {
        val media = MediaEngine.inspect(fixture("rotated.mp4"))
        val start = 200
        val fps = 6
        var count = 0
        var previous = start
        FrameSource.open(media, start, fps).use { source ->
            while (true) {
                val frame = source.next() ?: break
                try {
                    assertTrue(frame.second > previous)
                    assertTrue(frame.second <= media.duration)
                    previous = frame.second
                    count++
                } finally { frame.first.recycle() }
            }
        }
        assertEquals(((media.duration - start) * fps + 999) / 1000, count)
        assertEquals(media.duration, previous)
    }

    @Test
    fun malformedAndUnsupportedMediaAreRejected() {
        val bad = File(temp, "fake.png").apply { writeText("not an image but named PNG") }
        assertTrue(runCatching { MediaEngine.inspect(bad) }.isFailure)
        bad.writeText("RIFF1234WEBP1234")
        assertTrue(runCatching { MediaEngine.inspect(bad) }.isFailure)
        bad.writeBytes(byteArrayOf(1, 2))
        assertTrue(runCatching { MediaEngine.inspect(bad) }.isFailure)
    }

    @Test
    fun cancellationStopsFrameProcessingAndKeepsExistingAsset() = runBlocking {
        val existing = fixture("static.webp")
        val bytes = existing.readBytes()
        val output = File(temp, "pending.webp")
        val media = MediaEngine.inspect(fixture("disposal.gif"))
        val job =
            launch(start = CoroutineStart.LAZY) {
                MediaEngine.convert(media, Edit(), output) {
                    if (it > 0f) cancel("test cancellation")
                }
            }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertArrayEquals(bytes, existing.readBytes())
        assertFalse(output.exists())
    }

    @Test
    fun databasePersistenceVersioningReorderAndProviderWhitelist() {
        val c = pack()
        repo.expose(c.pack.id)
        val resolver = context.contentResolver
        val base = "content://${context.packageName}.stickers"
        resolver.query(Uri.parse("$base/metadata/${c.pack.id}"), null, null, null, null)!!.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(c.pack.id, it.getString(0))
        }
        val reopened =
            androidx.room.Room.databaseBuilder(context, StudioDatabase::class.java, "studio.db")
                .build()
        try {
            assertEquals(3, reopened.dao().pack(c.pack.id)!!.stickers.size)
        } finally {
            reopened.close()
        }
        val before = c.pack.version
        repo.rename(c.pack.id, "Changed")
        repo.move(c.stickers[2], -1)
        repo.chooseTray(c.stickers[1])
        val changed = repo.dao.pack(c.pack.id)!!
        assertTrue(changed.pack.version >= before + 3)
        assertEquals(c.stickers[2].id, changed.stickers[1].id)
        resolver
            .openAssetFileDescriptor(
                Uri.parse("$base/stickers_asset/${c.pack.id}/${c.stickers[0].asset}"),
                "r",
            )!!
            .use { assertTrue(it.length > 0) }
        for (path in
            listOf("../studio.db", "%2e%2e%2fstudio.db", c.stickers[0].original, "unknown.webp")) {
            assertTrue(
                path,
                runCatching {
                        resolver
                            .openFileDescriptor(
                                Uri.parse("$base/stickers_asset/${c.pack.id}/$path"),
                                "r",
                            )
                            ?.close()
                    }
                    .isFailure,
            )
        }
        assertTrue(
            runCatching {
                    resolver.openFileDescriptor(
                        Uri.parse("$base/stickers_asset/${c.pack.id}/${c.stickers[0].asset}"),
                        "w",
                    )
                }
                .isFailure
        )
        repo.remove(c.stickers[0])
        assertFalse(repo.dao.pack(c.pack.id)!!.pack.exposed)
        resolver.query(Uri.parse("$base/metadata/${c.pack.id}"), null, null, null, null)!!.use {
            assertEquals(0, it.count)
        }
    }

    @Test
    fun tenPackExposureLimit() {
        val packs = (0..10).map { pack() }
        packs.take(10).forEach { repo.expose(it.pack.id) }
        assertTrue(runCatching { repo.expose(packs.last().pack.id) }.isFailure)
        repo.hide(packs.first().pack.id)
        repo.expose(packs.last().pack.id)
        assertEquals(10, repo.dao.all().count { it.pack.exposed })
    }

    @Test
    fun unreadableShareIsReported() = runBlocking {
        assertTrue(
            runCatching {
                    MediaEngine.copyIncoming(
                        context,
                        Uri.parse("content://missing.provider/no-file"),
                    )
                }
                .isFailure
        )
    }

    private fun waitForModel(model: StudioModel) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            var finished = false
            instrumentation.runOnMainSync { finished = model.busy == null }
            if (finished) return
            Thread.sleep(30)
        }
        fail("Model operation did not finish")
    }

    @Test
    fun fullPackAndMixedImportFailuresKeepUnimportedFiles() {
        val p = repo.create("Capacity test", false)
        created += p.id
        val source = fixture("static.webp")
        val gif = MediaEngine.inspect(fixture("disposal.gif"))
        val bad =
            File(temp, "broken.media").apply {
                writeText("Not an image, despite the declared kind")
            }
        val items =
            mutableListOf(
                ImportItem(Incoming(bad, "broken", "still", 0, 512, 512)),
                ImportItem(gif),
            )
        repeat(31) { i ->
            val f = File(temp, "input_$i.webp")
            source.copyTo(f)
            items += ImportItem(MediaEngine.inspect(f))
        }
        lateinit var model: StudioModel
        instrumentation.runOnMainSync {
            model = StudioModel(context.applicationContext as android.app.Application)
            model.pending = items
            model.saveImports(p.id)
        }
        waitForModel(model)
        assertEquals(30, repo.dao.pack(p.id)!!.stickers.size)
        assertEquals(3, model.pending.size)
        assertEquals(3, model.errors.size)
        assertTrue(model.errors.any { it.contains("full") })
        assertTrue(repo.dao.pack(p.id)!!.stickers.any { it.duration > 0 })
        assertFalse(model.errors.any { it.contains("stickers only") })
        assertTrue(model.pending.all { it.media.file.exists() })
        instrumentation.runOnMainSync { model.discard() }
    }

    @Test
    fun mixedPackImportsAndExportsWithoutChoosingAType() {
        val initial = pack()
        val still = initial.stickers.first()
        val originalBytes = repo.original(still).readBytes()
        val expected = MediaEngine.firstFrame(repo.file(still))
        val gif = MediaEngine.inspect(fixture("disposal.gif"))
        lateinit var model: StudioModel
        instrumentation.runOnMainSync {
            model = StudioModel(context.applicationContext as android.app.Application)
            model.pending = listOf(ImportItem(gif))
            model.saveImports(initial.pack.id)
        }
        waitForModel(model)
        assertTrue(model.errors.isEmpty())
        assertTrue(model.pending.isEmpty())
        assertEquals(4, repo.dao.pack(initial.pack.id)!!.stickers.size)
        repo.expose(initial.pack.id)
        val exported = repo.dao.pack(initial.pack.id)!!
        assertTrue(exported.pack.animated)
        assertTrue(repo.issues(exported).isEmpty())
        exported.stickers.forEach {
            val info = NativeWebp.info(repo.file(it).path)
            assertTrue(info[3] != 0)
            assertTrue(info[2] in 8..10000)
            assertTrue(info[4] >= 8)
        }
        val wrapped = exported.stickers.first { it.id == still.id }
        assertArrayEquals(originalBytes, repo.original(wrapped).readBytes())
        val decoder = NativeWebp.openDecoder(repo.file(wrapped).path)
        val frame = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(500, NativeWebp.nextFrame(decoder, frame))
            assertTrue(expected.sameAs(frame))
            assertEquals(1000, NativeWebp.nextFrame(decoder, frame))
            assertTrue(expected.sameAs(frame))
            assertEquals(-1, NativeWebp.nextFrame(decoder, frame))
        } finally {
            NativeWebp.closeDecoder(decoder)
            frame.recycle()
            expected.recycle()
        }
        val assetBefore = wrapped.asset
        repo.expose(initial.pack.id)
        assertEquals(assetBefore, repo.dao.sticker(still.id)!!.asset)
    }

    @Test
    fun failedReplacementKeepsSavedStickerAndMetadata() {
        val c = pack()
        val sticker = c.stickers.first()
        val saved = repo.file(sticker).readBytes()
        val broken = File(temp, "broken_original.media").apply { writeText("invalid image bytes") }
        lateinit var model: StudioModel
        instrumentation.runOnMainSync {
            model = StudioModel(context.applicationContext as android.app.Application)
            model.editor =
                EditorTarget(Incoming(broken, "broken", "still", 0, 512, 512), Edit(), sticker)
            model.acceptEdit(Edit(text = "test"))
        }
        waitForModel(model)
        assertArrayEquals(saved, repo.file(sticker).readBytes())
        assertEquals(sticker, repo.dao.sticker(sticker.id))
        assertEquals(c.pack.version, repo.dao.pack(c.pack.id)!!.pack.version)
        assertNotNull(model.message)
    }
}
