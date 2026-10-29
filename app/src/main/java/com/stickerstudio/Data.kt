package com.stickerstudio

import android.content.Context
import android.net.Uri
import androidx.room.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "packs")
data class Pack(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val animated: Boolean,
    val version: Long = 1,
    val tray: String = "",
    val exposed: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "stickers",
    foreignKeys =
        [
            ForeignKey(
                entity = Pack::class,
                parentColumns = ["id"],
                childColumns = ["packId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("packId")],
)
data class Sticker(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val packId: String,
    val asset: String,
    val original: String,
    val kind: String,
    val edit: String = "{}",
    val position: Int,
    val duration: Int = 0,
)

data class PackContents(
    @Embedded val pack: Pack,
    @Relation(parentColumn = "id", entityColumn = "packId") val unsorted: List<Sticker>,
) {
    val stickers
        get() = unsorted.sortedBy { it.position }
}

@Dao
interface StudioDao {
    @Transaction
    @Query("SELECT * FROM packs ORDER BY created DESC")
    fun observe(): Flow<List<PackContents>>

    @Transaction @Query("SELECT * FROM packs ORDER BY created DESC") fun all(): List<PackContents>

    @Transaction @Query("SELECT * FROM packs WHERE id=:id") fun pack(id: String): PackContents?

    @Query("SELECT * FROM stickers WHERE id=:id") fun sticker(id: String): Sticker?

    @Transaction
    @Query("SELECT * FROM packs WHERE exposed=1 ORDER BY created DESC LIMIT 10")
    fun exposedPacks(): List<PackContents>

    @Query("SELECT COUNT(*) FROM packs WHERE exposed=1") fun exposedCount(): Int

    @Insert fun insert(pack: Pack)

    @Insert fun insert(sticker: Sticker)

    @Update fun update(pack: Pack)

    @Update fun update(sticker: Sticker)

    @Query("DELETE FROM packs WHERE id=:id") fun deletePack(id: String)

    @Query("DELETE FROM stickers WHERE id=:id") fun deleteSticker(id: String)
}

@Database(entities = [Pack::class, Sticker::class], version = 1, exportSchema = false)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun dao(): StudioDao

    companion object {
        @Volatile private var instance: StudioDatabase? = null

        fun get(context: Context): StudioDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                StudioDatabase::class.java,
                                "studio.db",
                            )
                            .build()
                            .also { instance = it }
                }
    }
}

object Limits {
    const val STATIC_BYTES = 100 * 1024
    const val ANIMATED_BYTES = 500 * 1024

    fun issues(count: Int, trayValid: Boolean, assetsValid: Boolean = true): List<String> =
        buildList {
            if (count < 3)
                add("Add ${3 - count} more sticker${if (count == 2) "" else "s"} (3 minimum).")
            if (count > 30) add("A pack can contain at most 30 stickers.")
            if (!trayValid) add("Choose a pack icon.")
            if (!assetsValid)
                add("Some assets are missing or exceed WhatsApp limits. Re-edit or remove them.")
        }
}

class Repository(val context: Context) {
    val db = StudioDatabase.get(context)
    val dao = db.dao()
    val root = File(context.filesDir, "collection").apply { mkdirs() }

    fun folder(id: String) = File(root, id).apply { mkdirs() }

    fun file(s: Sticker) = File(folder(s.packId), s.asset)

    fun original(s: Sticker) = File(folder(s.packId), s.original)

    fun notifyChange() {
        context.contentResolver.notifyChange(
            Uri.parse("content://${context.packageName}.stickers/metadata"),
            null,
        )
    }

    fun create(name: String, animated: Boolean = false): Pack {
        require(name.trim().isNotEmpty() && name.trim().length <= 128) {
            "Use a name from 1 to 128 characters."
        }
        return Pack(name = name.trim(), animated = animated).also {
            dao.insert(it)
            notifyChange()
        }
    }

    fun rename(id: String, name: String) {
        require(name.trim().isNotEmpty() && name.trim().length <= 128) {
            "Use a name from 1 to 128 characters."
        }
        db.runInTransaction {
            val p = dao.pack(id)!!.pack
            dao.update(p.copy(name = name.trim(), version = p.version + 1))
        }
        notifyChange()
    }

    fun removePack(id: String) {
        dao.deletePack(id)
        File(root, id).deleteRecursively()
        notifyChange()
    }

    fun remove(s: Sticker) {
        db.runInTransaction {
            dao.deleteSticker(s.id)
            val p = dao.pack(s.packId)!!.pack
            val remaining = dao.pack(s.packId)!!.stickers
            val tray = if (remaining.isEmpty()) "" else p.tray
            dao.update(
                p.copy(
                    version = p.version + 1,
                    tray = tray,
                    exposed = p.exposed && remaining.size >= 3,
                )
            )
        }
        folder(s.packId)
            .listFiles()
            ?.filter { it.name == "${s.id}.webp" || it.name.startsWith("${s.id}_") }
            ?.forEach { it.delete() }
        notifyChange()
    }

    fun move(s: Sticker, delta: Int) {
        db.runInTransaction {
            val contents = dao.pack(s.packId)!!
            val list = contents.stickers.toMutableList()
            val from = list.indexOfFirst { it.id == s.id }
            val to = (from + delta).coerceIn(0, list.lastIndex)
            list.add(to, list.removeAt(from))
            list.forEachIndexed { i, item -> dao.update(item.copy(position = i)) }
            dao.update(contents.pack.copy(version = contents.pack.version + 1))
        }
        notifyChange()
    }

    fun chooseTray(s: Sticker) {
        val current = dao.sticker(s.id) ?: error("Sticker was removed")
        val bitmap = MediaEngine.firstFrame(file(current))
        val name = "tray_${UUID.randomUUID()}.png"
        val out = File(folder(s.packId), name)
        try {
            MediaEngine.writeTray(bitmap, out)
            db.runInTransaction {
                val p = dao.pack(s.packId)!!.pack
                dao.update(p.copy(tray = name, version = p.version + 1))
            }
        } finally {
            bitmap.recycle()
            if (dao.pack(s.packId)?.pack?.tray != name) out.delete()
        }
        cleanup(s.packId)
        notifyChange()
    }

    fun issues(c: PackContents): List<String> {
        val tray = File(folder(c.pack.id), c.pack.tray)
        return Limits.issues(
            c.stickers.size,
            c.pack.tray.isNotEmpty() && tray.isFile && tray.length() in 1..51200,
            c.stickers.all {
                file(it).isFile &&
                    file(it).length() in
                        1..(if (it.duration > 0) Limits.ANIMATED_BYTES else Limits.STATIC_BYTES) &&
                    (it.duration == 0 || it.duration in 8..10000)
            },
        )
    }

    fun expose(id: String) {
        val generated = mutableListOf<File>()
        try {
            db.runInTransaction {
                val c = dao.pack(id) ?: error("Pack was deleted")
                require(issues(c).isEmpty()) { issues(c).joinToString("\n") }
                val animated = c.stickers.any { it.duration > 0 }
                c.stickers.forEach { s ->
                    val output =
                        if (animated && s.duration == 0) {
                            File(folder(id), "${s.id}_${UUID.randomUUID()}_mixed.webp").also {
                                generated += it
                                it.writeBytes(NativeWebp.wrapStill(file(s).path))
                                require(it.length() in 1..Limits.ANIMATED_BYTES) {
                                    "Sticker exceeds 500 KB"
                                }
                                dao.update(s.copy(asset = it.name, duration = 1000))
                            }
                        } else file(s)
                    val info = NativeWebp.info(output.path)
                    require(
                        info[0] == 512 &&
                            info[1] == 512 &&
                            (info[3] != 0) == animated &&
                            (!animated || info[2] in 8..10000 && info[4] >= 8)
                    ) {
                        "A sticker does not meet WhatsApp's dimensions or animation requirements."
                    }
                }
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(
                    File(folder(id), c.pack.tray).path,
                    bounds,
                )
                require(bounds.outWidth == 96 && bounds.outHeight == 96) {
                    "The pack icon must be 96 × 96 pixels."
                }
                require(c.pack.exposed || dao.exposedCount() < 10) {
                    "WhatsApp exposes up to 10 packs. Hide another pack first."
                }
                dao.update(
                    c.pack.copy(exposed = true, animated = animated, version = c.pack.version + 1)
                )
            }
        } catch (e: Exception) {
            generated.forEach { it.delete() }
            throw e
        }
        notifyChange()
    }

    fun normalizePublished(id: String) {
        val c = dao.pack(id) ?: return
        if (c.pack.exposed) expose(id)
    }

    fun cleanup(id: String) {
        val c = dao.pack(id) ?: return
        val live = c.stickers.flatMap { listOf(it.asset, it.original) }.toSet() + c.pack.tray
        File(root, id)
            .listFiles()
            ?.filter { it.isFile && it.name !in live }
            ?.forEach { it.delete() }
    }

    fun hide(id: String) {
        db.runInTransaction {
            val p = dao.pack(id)!!.pack
            dao.update(p.copy(exposed = false))
        }
        notifyChange()
    }
}
