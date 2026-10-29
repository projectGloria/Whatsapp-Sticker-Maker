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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportItem(val media: Incoming, val edit: Edit = Edit(), val reviewed: Boolean = false)

data class EditorTarget(
    val media: Incoming,
    val edit: Edit,
    val sticker: Sticker? = null,
    val pendingIndex: Int? = null,
) {
    var persist: (() -> Unit)? = null
    private var draftState by mutableStateOf(edit)
    var draft: Edit
        get() = draftState
        set(value) {
            draftState = value
            persist?.invoke()
        }

    val undo = mutableStateListOf<Edit>()
    val redo = mutableStateListOf<Edit>()
}

class StudioModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val packs =
        repo.dao
            .observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var selected by mutableStateOf<String?>(null)
    private val sessions = app.getSharedPreferences("import_sessions", 0)
    private var destinationState by mutableStateOf(sessions.getString("destination", null))
    var destination: String?
        get() = destinationState
        set(value) { destinationState = value; sessions.edit().putString("destination", value).apply() }
    private var pendingState by
        mutableStateOf<List<ImportItem>>(SessionStore.restore(app, sessions))
    var pending: List<ImportItem>
        get() = pendingState
        set(value) {
            pendingState = value
            SessionStore.save(sessions, value)
        }

    var errors by mutableStateOf<List<String>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    private var editorState by mutableStateOf<EditorTarget?>(null)
    var editor: EditorTarget?
        get() = editorState
        set(value) {
            editorState = value
            value?.persist = { SessionStore.saveEditor(sessions, value) }
            SessionStore.saveEditor(sessions, value)
        }

    private var work: Job? = null

    private suspend fun <T> mutate(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutationGate.withLock { block() } }

    companion object {
        private val mutationGate = Mutex()
    }

    init {
        viewModelScope.launch {
            val restored = mutate {
                val contents = repo.dao.all()
                contents.forEach { repo.cleanup(it.pack.id) }
                val originals = contents.flatMap { it.stickers }.map { it.original }
                val alreadySaved =
                    pending.filter { item ->
                        originals.any { it.endsWith("_" + item.media.file.name) }
                    }
                if (alreadySaved.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        pending = pending.filterNot { it in alreadySaved }
                    }
                    alreadySaved.forEach { it.media.file.delete() }
                }
                SessionStore.restoreEditor(repo, sessions)
            }
            if (editor == null && restored != null) editor = restored
        }
        // Only abandoned, uncommitted imports live in this legacy cache folder.
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "imports")
                .listFiles()
                ?.filter { candidate ->
                    pending.none { it.media.file == candidate } &&
                        System.currentTimeMillis() - candidate.lastModified() > 24 * 60 * 60 * 1000L
                }
                ?.forEach { it.delete() }
        }
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutate { block() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message = e.message ?: "Operation failed"
            }
        }
    }

    fun create(name: String, onCreated: (Pack) -> Unit = {}) {
        action {
            val p = repo.create(name)
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
                            mutate { collected += ImportItem(MediaEngine.copyIncoming(getApplication(), uri)) }
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
        val discarded = pending
        pending = emptyList()
        discarded.forEach { it.media.file.delete() }
    }

    fun drop(index: Int) {
        val discarded = pending[index]
        pending = pending.filterIndexed { i, _ -> i != index }
        discarded.media.file.delete()
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
                    mutate {
                        val duration =
                            MediaEngine.convert(target.media, edit, out) { progress = it }
                        ensureActive()
                        repo.db.runInTransaction {
                            val current =
                                repo.dao.sticker(sticker.id) ?: error("Sticker was removed")
                            repo.dao.update(
                                current.copy(asset = asset, edit = edit.json(), duration = duration)
                            )
                            val p = repo.dao.pack(sticker.packId)!!.pack
                            repo.dao.update(p.copy(version = p.version + 1))
                            repo.normalizePublished(sticker.packId)
                        }
                        committed = true
                        repo.cleanup(sticker.packId)
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
                        val original = File(dir, "${id}_original_${item.media.file.name}")
                        var committed = false
                        try {
                            mutate {
                                val c =
                                    repo.dao.pack(packId) ?: error("Destination pack was deleted")
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
                                item.media.file.inputStream().use { input ->
                                    original.outputStream().use { output ->
                                        val buffer = ByteArray(65536)
                                        while (true) {
                                            ensureActive()
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            output.write(buffer, 0, n)
                                        }
                                    }
                                }
                                ensureActive()
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
                                ensureActive()
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
                                    repo.normalizePublished(packId)
                                }
                                committed = true
                                repo.cleanup(packId)
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
                                withContext(NonCancellable + Dispatchers.IO) {
                                    mutationGate.withLock { repo.cleanup(packId) }
                                }
                            }
                        }
                    }
                    message = "Saved ${completed.size} sticker${if(completed.size==1)"" else "s"}"
                } catch (c: CancellationException) {
                    message = "Import cancelled. ${completed.size} completed stickers kept."
                } finally {
                    pending = pending.filterNot { it.media.file in completed }
                    completed.forEach { it.delete() }
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
