package com.stickerstudio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class StickerProvider : ContentProvider() {
    private val repo by lazy { Repository(requireNotNull(context)) }

    override fun onCreate() = true

    private fun exposed(id: String? = null) =
        (if (id == null) repo.dao.exposedPacks() else listOfNotNull(repo.dao.pack(id)))
            .filter {
                it.pack.exposed &&
                    repo.issues(it).isEmpty() &&
                    it.stickers.all { s -> (s.duration > 0) == it.pack.animated }
            }
            .take(10)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val p = uri.pathSegments
        require(uri.authority == "${BuildConfig.APPLICATION_ID}.stickers")
        val cursor =
            when {
                p.size in 1..2 && p[0] == "metadata" ->
                    MatrixCursor(
                            arrayOf(
                                "sticker_pack_identifier",
                                "sticker_pack_name",
                                "sticker_pack_publisher",
                                "sticker_pack_icon",
                                "android_play_store_link",
                                "ios_app_download_link",
                                "sticker_pack_publisher_email",
                                "sticker_pack_publisher_website",
                                "sticker_pack_privacy_policy_website",
                                "sticker_pack_license_agreement_website",
                                "image_data_version",
                                "whatsapp_will_not_cache_stickers",
                                "animated_sticker_pack",
                            )
                        )
                        .apply {
                            exposed(p.getOrNull(1))
                                .filter { p.size == 1 || it.pack.id == p[1] }
                                .forEach { c ->
                                    val pack = c.pack
                                    addRow(
                                        arrayOf<Any>(
                                            pack.id,
                                            pack.name,
                                            requireNotNull(context).getString(R.string.app_name),
                                            pack.tray,
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            pack.version.toString(),
                                            0,
                                            if (pack.animated) 1 else 0,
                                        )
                                    )
                                }
                        }
                p.size == 2 && p[0] == "stickers" ->
                    MatrixCursor(
                            arrayOf(
                                "sticker_file_name",
                                "sticker_emoji",
                                "sticker_accessibility_text",
                            )
                        )
                        .apply {
                            exposed(p[1])
                                .firstOrNull { it.pack.id == p[1] }
                                ?.stickers
                                ?.forEach { addRow(arrayOf(it.asset, "😀", "")) }
                        }
                else -> throw IllegalArgumentException("Unknown provider URI")
            }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val p = uri.pathSegments
        return when {
            p.size == 1 && p[0] == "metadata" ->
                "vnd.android.cursor.dir/vnd.${BuildConfig.APPLICATION_ID}.stickers.metadata"
            p.size == 2 && p[0] == "metadata" ->
                "vnd.android.cursor.item/vnd.${BuildConfig.APPLICATION_ID}.stickers.metadata"
            p.size == 2 && p[0] == "stickers" ->
                "vnd.android.cursor.dir/vnd.${BuildConfig.APPLICATION_ID}.stickers.stickers"
            p.size == 3 && p[0] == "stickers_asset" ->
                if (p[2].endsWith(".png")) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown provider URI")
        }
    }

    private fun asset(uri: Uri, mode: String): File {
        if (mode != "r") throw SecurityException("Sticker assets are read-only")
        val p = uri.pathSegments
        if (
            uri.authority != "${BuildConfig.APPLICATION_ID}.stickers" ||
                p.size != 3 ||
                p[0] != "stickers_asset"
        )
            throw FileNotFoundException()
        val c = exposed(p[1]).firstOrNull { it.pack.id == p[1] } ?: throw FileNotFoundException()
        if (p[2] != c.pack.tray && c.stickers.none { it.asset == p[2] })
            throw FileNotFoundException()
        val parent = repo.folder(c.pack.id).canonicalFile
        val f = File(parent, p[2]).canonicalFile
        if (f.parentFile != parent || !f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(asset(uri, mode), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val f = asset(uri, mode)
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            f.length(),
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw SecurityException("Read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Read-only provider")
}
