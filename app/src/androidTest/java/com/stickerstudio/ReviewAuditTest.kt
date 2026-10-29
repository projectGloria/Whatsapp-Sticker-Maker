package com.stickerstudio

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for the review fixes. */
@RunWith(AndroidJUnit4::class)
class ReviewAuditTest {
    @Test
    fun pendingSessionRestoresMediaAndAcceptedEdits() {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val source =
            fixture("source.png").copyTo(File(imports, "recovery_${UUID.randomUUID()}.media"))
        val store = context.getSharedPreferences("audit_session", 0)
        try {
            val item =
                ImportItem(MediaEngine.inspect(source), Edit(text = "Recovered", left = .1f), true)
            SessionStore.save(store, listOf(item))
            assertEquals(listOf(item.copy(media = item.media.copy(file = source.canonicalFile))), SessionStore.restore(context, store))
            val target = EditorTarget(item.media, item.edit, pendingIndex = 0)
            target.draft = item.edit.copy(text = "Unsaved recovered")
            SessionStore.saveEditor(store, target)
            assertEquals(target.draft, SessionStore.restoreEditor(repo, store)!!.draft)
            source.delete()
            assertTrue(SessionStore.restore(context, store).isEmpty())
            assertNull(SessionStore.restoreEditor(repo, store))
        } finally {
            source.delete()
            store.edit().clear().commit()
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repo = Repository(context)
    private val ids = mutableListOf<String>()
    private val temp = File(context.cacheDir, "audit_${UUID.randomUUID()}").apply { mkdirs() }

    private fun fixture(name: String) =
        File(temp, name).also { file ->
            instrumentation.context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

    private fun pack(mixed: Boolean = false): PackContents {
        val p = repo.create("Audit ${UUID.randomUUID()}")
        ids += p.id
        val dir = repo.folder(p.id)
        repeat(3) { index ->
            val src = fixture(if (mixed && index == 2) "compatible.webp" else "static.webp")
            val media = MediaEngine.inspect(src)
            val id = UUID.randomUUID().toString()
            src.copyTo(File(dir, "$id.webp"))
            src.copyTo(File(dir, "${id}_original.media"))
            repo.dao.insert(
                Sticker(
                    id,
                    p.id,
                    "$id.webp",
                    "${id}_original.media",
                    media.kind,
                    position = index,
                    duration = media.duration,
                )
            )
        }
        repo.chooseTray(repo.dao.pack(p.id)!!.stickers.first())
        return repo.dao.pack(p.id)!!
    }

    private fun waitFor(model: StudioModel) {
        val end = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < end) {
            var done = false
            instrumentation.runOnMainSync { done = model.busy == null }
            if (done) return
            Thread.sleep(20)
        }
        fail("Model did not finish")
    }

    private fun edit(s: Sticker): StudioModel {
        val media = MediaEngine.inspect(repo.original(s))
        lateinit var model: StudioModel
        instrumentation.runOnMainSync {
            model = StudioModel(context.applicationContext as Application)
            model.editor = EditorTarget(media, Edit.parse(s.edit), s)
            model.acceptEdit(Edit(text = "audit"))
        }
        waitFor(model)
        assertEquals("Sticker saved", model.message)
        return model
    }

    private fun visible(id: String) =
        context.contentResolver
            .query(
                Uri.parse("content://${context.packageName}.stickers/metadata/$id"),
                null,
                null,
                null,
                null,
            )!!
            .use { it.count }

    @After
    fun cleanup() {
        ids.forEach { repo.removePack(it) }
        temp.deleteRecursively()
    }

    @Test
    fun auditMixedPackVanishesAfterStillEdit() {
        val c = pack(true)
        repo.expose(c.pack.id)
        assertEquals(1, visible(c.pack.id))
        edit(repo.dao.pack(c.pack.id)!!.stickers.first())
        assertTrue(repo.dao.pack(c.pack.id)!!.pack.exposed)
        assertEquals(1, visible(c.pack.id))
        repo.expose(c.pack.id)
        assertEquals(1, visible(c.pack.id))
    }

    @Test
    fun auditSuccessfulReplacementRetainsOldAsset() {
        val c = pack()
        val old = repo.file(c.stickers.first())
        edit(c.stickers.first())
        assertNotEquals(old.name, repo.dao.sticker(c.stickers.first().id)!!.asset)
        assertFalse("Old output removed", old.exists())
    }

    @Test
    fun auditTrayReplacementRetainsOldTray() {
        val c = pack()
        val old = File(repo.folder(c.pack.id), c.pack.tray)
        repo.chooseTray(c.stickers.last())
        assertNotEquals(c.pack.tray, repo.dao.pack(c.pack.id)!!.pack.tray)
        assertFalse("Old tray removed", old.exists())
    }

    @Test
    fun auditDefaultWebpPreviewDiffersFromSavedOutput() = runBlocking {
        val file = fixture("static.webp")
        val source = MediaEngine.firstFrame(file)
        val preview = MediaEngine.preview(MediaEngine.inspect(file), source, Edit())
        val out = File(temp, "preserved.webp")
        MediaEngine.convert(MediaEngine.inspect(file), Edit(), out) {}
        val saved = MediaEngine.firstFrame(out)
        try {
            assertArrayEquals(file.readBytes(), out.readBytes())
            assertTrue("Preview agrees with preserved output", preview.sameAs(saved))
        } finally {
            source.recycle()
            preview.recycle()
            saved.recycle()
        }
    }
}
