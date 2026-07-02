package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object WallpaperStorage {
    const val PREF_KEY_IREADER_CACHE_URI = "ireader_wallpaper_cache_uri"
    const val PREF_KEY_IREADER_SKIN_URI = "ireader_wallpaper_skin_uri"
    const val PREF_KEY_IREADER_SKIN_TREE_URI = "ireader_wallpaper_skin_tree_uri"
    const val PREF_KEY_IREADER_SKIN_FILE_NAME = "ireader_wallpaper_skin_file_name"
    private const val DIR_NAME = "NeoReader"
    private const val FILE_NAME = "neoreader_wallpaper.png"
    private const val MIME_TYPE = "image/png"

    fun save(context: Context, bitmap: Bitmap): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap)
        } else {
            saveViaFilePath(context, bitmap)
        }
    }

    fun saveIReaderCacheFromPrefs(context: Context, bitmap: Bitmap): String? {
        val uri = context.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
            .getString(PREF_KEY_IREADER_CACHE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return saveIReaderCache(context, bitmap, uri)
    }

    fun saveIReaderSkinFromPrefs(context: Context, bitmap: Bitmap): String? {
        val prefs = context.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        val treeUri = prefs.getString(PREF_KEY_IREADER_SKIN_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val fileName = prefs.getString(PREF_KEY_IREADER_SKIN_FILE_NAME, null)?.takeIf { it.isNotBlank() }
        if (treeUri != null && fileName != null) {
            return saveIReaderSkinInTree(context, bitmap, treeUri, fileName)
        }
        val uri = context.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
            .getString(PREF_KEY_IREADER_SKIN_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return saveIReaderSkin(context, bitmap, uri)
    }

    fun saveIReaderCache(context: Context, bitmap: Bitmap, uriString: String): String {
        val uri = Uri.parse(uriString)
        writeBitmap(context, bitmap, uri, Bitmap.CompressFormat.JPEG, 95, "掌阅缓存 JPEG 编码失败")
        return uriString
    }

    fun saveIReaderSkin(context: Context, bitmap: Bitmap, uriString: String): String {
        val uri = Uri.parse(uriString)
        val name = displayName(context, uri).lowercase()
        val isPng = name.endsWith(".png") || uriString.lowercase().contains(".png")
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (isPng) 100 else 95
        val label = if (isPng) "PNG" else "JPEG"
        writeBitmap(context, bitmap, uri, format, quality, "掌阅 Skin $label 编码失败")
        return uriString
    }

    fun saveIReaderSkinInTree(context: Context, bitmap: Bitmap, treeUriString: String, fileName: String): String {
        val treeUri = Uri.parse(treeUriString)
        val childUri = findChildDocument(context, treeUri, fileName)
            ?: error("Skin 目录中未找到文件: $fileName")
        val isPng = fileName.lowercase().endsWith(".png")
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (isPng) 100 else 95
        val label = if (isPng) "PNG" else "JPEG"
        writeBitmap(context, bitmap, childUri, format, quality, "掌阅 Skin $label 编码失败")
        return "$treeUriString/$fileName"
    }

    private fun findChildDocument(context: Context, treeUri: Uri, fileName: String): Uri? {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                if (name == fileName) {
                    val docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
        }
        return null
    }

    private fun writeBitmap(
        context: Context,
        bitmap: Bitmap,
        uri: Uri,
        format: Bitmap.CompressFormat,
        quality: Int,
        encodeError: String
    ) {
        val resolver = context.contentResolver
        val wrote = resolver.openOutputStream(uri, "rwt")?.use { out ->
            bitmap.compress(format, quality, out)
        } ?: false
        if (!wrote) error(encodeError)
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: ""
            }
        }
        return uri.lastPathSegment ?: ""
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/"
        findExistingImage(context, relativePath)?.let { uri ->
            runCatching {
                writePng(resolver, uri, bitmap)
                return mediaPathHint(relativePath)
            }
        }

        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        ) ?: error("无法创建壁纸图片")
        writePng(resolver, uri, bitmap)
        resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            },
            null,
            null
        )
        return mediaPathHint(relativePath)
    }

    private fun writePng(resolver: android.content.ContentResolver, uri: Uri, bitmap: Bitmap) {
        resolver.openOutputStream(uri, "rwt")?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("壁纸图片编码失败")
            }
        } ?: error("无法写入壁纸图片")
    }

    private fun findExistingImage(context: Context, relativePath: String): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, relativePath),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun saveViaFilePath(context: Context, bitmap: Bitmap): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建目录: ${dir.absolutePath}")
        val file = File(dir, FILE_NAME)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("壁纸图片编码失败")
            }
        }
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(MIME_TYPE)
            ) { _, _ -> }
        }
        return file.absolutePath
    }

    private fun mediaPathHint(relativePath: String): String {
        return "/storage/emulated/0/$relativePath$FILE_NAME"
    }
}
