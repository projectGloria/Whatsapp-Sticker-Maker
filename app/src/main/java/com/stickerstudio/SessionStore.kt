package com.stickerstudio

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object SessionStore {
    fun saveEditor(store: SharedPreferences, target: EditorTarget?) {
        val o =
            target?.let { t ->
                val m = t.media
                JSONObject()
                    .put("path", m.file.path)
                    .put("name", m.name)
                    .put("kind", m.kind)
                    .put("duration", m.duration)
                    .put("width", m.width)
                    .put("height", m.height)
                    .put("edit", t.edit.json())
                    .put("draft", t.draft.json())
                    .put("sticker", t.sticker?.id)
                    .put("index", t.pendingIndex)
            }
        store.edit().putString("editor", o?.toString()).apply()
    }

    fun restoreEditor(repo: Repository, store: SharedPreferences): EditorTarget? =
        runCatching {
                val raw = store.getString("editor", null) ?: return null
                val o = JSONObject(raw)
                val file = File(o.getString("path")).canonicalFile
                if (
                    !file.isFile ||
                        !file.toPath().startsWith(repo.context.filesDir.canonicalFile.toPath())
                )
                    return null
                val sticker =
                    if (o.has("sticker")) repo.dao.sticker(o.getString("sticker")) ?: return null
                    else null
                EditorTarget(
                        Incoming(
                            file,
                            o.getString("name"),
                            o.getString("kind"),
                            o.getInt("duration"),
                            o.getInt("width"),
                            o.getInt("height"),
                        ),
                        Edit.parse(o.getString("edit")),
                        sticker,
                        if (o.has("index")) o.getInt("index") else null,
                    )
                    .also { it.draft = Edit.parse(o.getString("draft")) }
            }
            .getOrNull()

    fun save(store: SharedPreferences, items: List<ImportItem>) {
        val data = JSONArray()
        items.forEach { item ->
            val m = item.media
            data.put(
                JSONObject()
                    .put("path", m.file.path)
                    .put("name", m.name)
                    .put("kind", m.kind)
                    .put("duration", m.duration)
                    .put("width", m.width)
                    .put("height", m.height)
                    .put("edit", item.edit.json())
                    .put("reviewed", item.reviewed)
            )
        }
        store.edit().putString("pending", data.toString()).apply()
    }

    fun restore(context: Context, store: SharedPreferences): List<ImportItem> =
        runCatching {
                val data = JSONArray(store.getString("pending", "[]"))
                val roots =
                    listOf(File(context.filesDir, "imports"), File(context.cacheDir, "imports"))
                        .map { it.canonicalFile }
                (0 until data.length()).mapNotNull { i ->
                    val o = data.getJSONObject(i)
                    val file = File(o.getString("path")).canonicalFile
                    if (!file.isFile || file.parentFile?.let { it in roots } != true) null
                    else
                        ImportItem(
                            Incoming(
                                file,
                                o.getString("name"),
                                o.getString("kind"),
                                o.getInt("duration"),
                                o.getInt("width"),
                                o.getInt("height"),
                            ),
                            Edit.parse(o.getString("edit")),
                            o.optBoolean("reviewed"),
                        )
                }
            }
            .getOrDefault(emptyList())
}
