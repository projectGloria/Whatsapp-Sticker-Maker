package com.stickerstudio

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

data class ImportItem(val media: Incoming, val edit: Edit = Edit(), val reviewed: Boolean = false)

data class EditorTarget(
    val media: Incoming,
    val edit: Edit,
    val sticker: Sticker? = null,
    val pendingIndex: Int? = null,
)

class StudioModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val packs =
        repo.dao
            .observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var selected by mutableStateOf<String?>(null)
    var pending by mutableStateOf<List<ImportItem>>(emptyList())
    var errors by mutableStateOf<List<String>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    var editor by mutableStateOf<EditorTarget?>(null)
    private var work: Job? = null

    init { // Only abandoned, uncommitted imports live in this cache folder.
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "imports")
                .listFiles()
                ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                ?.forEach { it.delete() }
        }
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message = e.message ?: "Operation failed"
            }
        }
    }

    fun create(name: String, animated: Boolean, onCreated: (Pack) -> Unit = {}) {
        action {
            val p = repo.create(name, animated)
            withContext(Dispatchers.Main) { onCreated(p) }
        }
    }

    fun receive(uris: List<Uri>) {
        if (busy != null) {
            message = "Finish or cancel the current operation before importing more files."
            return
        }
        busy = "Copying shared files"
        progress = 0f
        work =
            viewModelScope.launch {
                val collected = mutableListOf<ImportItem>()
                val failures = mutableListOf<String>()
                try {
                    for ((i, uri) in uris.withIndex()) {
                        ensureActive()
                        try {
                            val media =
                                withContext(Dispatchers.IO) {
                                    MediaEngine.copyIncoming(getApplication(), uri)
                                }
                            collected += ImportItem(media)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            failures += "File ${i+1}: ${e.message}"
                        }
                        progress = (i + 1).toFloat() / uris.size
                    }
                } finally {
                    pending = pending + collected
                    errors = errors + failures
                    busy = null
                }
            }
    }

    fun discard() {
        pending.forEach { it.media.file.delete() }
        pending = emptyList()
    }

    fun drop(index: Int) {
        pending[index].media.file.delete()
        pending = pending.filterIndexed { i, _ -> i != index }
    }

    fun openEditor(s: Sticker) {
        action {
            val media = MediaEngine.inspect(repo.original(s))
            withContext(Dispatchers.Main) { editor = EditorTarget(media, Edit.parse(s.edit), s) }
        }
    }

    fun editIncoming(index: Int) {
        val item = pending[index]
        editor = EditorTarget(item.media, item.edit, pendingIndex = index)
    }

    fun acceptEdit(edit: Edit) {
        val target = editor ?: return
        if (target.pendingIndex != null) {
            pending =
                pending.mapIndexed { i, item ->
                    if (i == target.pendingIndex) item.copy(edit = edit, reviewed = true) else item
                }
            editor = null
            return
        }
        val sticker = target.sticker ?: return
        busy = "Saving sticker"
        progress = 0f
        work =
            viewModelScope.launch {
                val dir = repo.folder(sticker.packId)
                val asset = "${sticker.id}_${UUID.randomUUID()}.webp"
                val out = File(dir, asset)
                var committed = false
                try {
                    withContext(Dispatchers.IO) {
                        val duration =
                            MediaEngine.convert(target.media, edit, out) { progress = it }
                        ensureActive()
                        repo.db.runInTransaction {
                            check(repo.dao.sticker(sticker.id) != null) { "Sticker was removed" }
                            repo.dao.update(
                                sticker.copy(asset = asset, edit = edit.json(), duration = duration)
                            )
                            val p = repo.dao.pack(sticker.packId)!!.pack
                            repo.dao.update(p.copy(version = p.version + 1))
                        }
                        committed = true
                        repo.notifyChange()
                    }
                    editor = null
                    message = "Sticker saved"
                } catch (c: CancellationException) {
                    message = "Edit cancelled. Saved sticker kept."
                } catch (e: Exception) {
                    message = e.message ?: "Could not save sticker"
                } finally {
                    if (!committed) out.delete()
                    busy = null
                }
            }
    }

    fun saveImports(packId: String) {
        val batch = pending.toList()
        if (batch.isEmpty()) return
        busy = "Creating stickers"
        progress = 0f
        work =
            viewModelScope.launch {
                val completed = mutableSetOf<File>()
                val failures = mutableListOf<String>()
                try {
                    for ((i, item) in batch.withIndex()) {
                        ensureActive()
                        val id = UUID.randomUUID().toString()
                        val dir = repo.folder(packId)
                        val out = File(dir, "$id.webp")
                        val original = File(dir, "${id}_original.media")
                        var committed = false
                        try {
                            withContext(Dispatchers.IO) {
                                val c =
                                    repo.dao.pack(packId) ?: error("Destination pack was deleted")
                                require(c.pack.animated == item.media.animated) {
                                    "This pack accepts ${if(c.pack.animated)"animated" else "static"} stickers only"
                                }
                                require(c.stickers.size < 30) { "Pack is full (30 stickers)" }
                                require(
                                    !item.media.animated ||
                                        item.media.duration <= 10000 ||
                                        item.reviewed
                                ) {
                                    "Open Edit and trim this clip to 10 seconds or less"
                                }
                                val duration =
                                    MediaEngine.convert(item.media, item.edit, out) {
                                        progress = (i + it) / batch.size
                                    }
                                ensureActive()
                                item.media.file.copyTo(original, true)
                                val tray =
                                    if (c.pack.tray.isEmpty()) "tray_${UUID.randomUUID()}.png"
                                    else c.pack.tray
                                if (c.pack.tray.isEmpty()) {
                                    val b = MediaEngine.firstFrame(out)
                                    try {
                                        MediaEngine.writeTray(b, File(dir, tray))
                                    } finally {
                                        b.recycle()
                                    }
                                }
                                repo.db.runInTransaction {
                                    val current =
                                        repo.dao.pack(packId)
                                            ?: error("Destination pack was deleted")
                                    require(current.stickers.size < 30) { "Pack is full" }
                                    repo.dao.insert(
                                        Sticker(
                                            id,
                                            packId,
                                            out.name,
                                            original.name,
                                            item.media.kind,
                                            item.edit.json(),
                                            (current.stickers.maxOfOrNull { it.position } ?: -1) +
                                                1,
                                            duration,
                                        )
                                    )
                                    repo.dao.update(
                                        current.pack.copy(
                                            version = current.pack.version + 1,
                                            tray = tray,
                                        )
                                    )
                                }
                                committed = true
                                completed += item.media.file
                                repo.notifyChange()
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            failures += "${item.media.name}: ${e.message}"
                        } finally {
                            if (!committed) {
                                out.delete()
                                original.delete()
                            }
                        }
                    }
                    message = "Saved ${completed.size} sticker${if(completed.size==1)"" else "s"}"
                } catch (c: CancellationException) {
                    message = "Import cancelled. ${completed.size} completed stickers kept."
                } finally {
                    completed.forEach { it.delete() }
                    pending = pending.filterNot { it.media.file in completed }
                    errors = errors + failures
                    selected = packId
                    busy = null
                }
            }
    }

    fun cancel() {
        work?.cancel()
    }
}
