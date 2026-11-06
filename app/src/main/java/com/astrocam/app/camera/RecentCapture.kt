package com.astrocam.app.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/** The newest photo this app saved, so the camera screen can show it when it opens. */
object RecentCapture {
    class Item(val thumb: Bitmap, val uri: String)

    fun latest(context: Context): Item? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                arrayOf("Pictures/AstroCamera%", "ASTRO_%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    Item(context.contentResolver.loadThumbnail(uri, Size(256, 256), null), uri.toString())
                } else null
            }
        }.getOrNull()
    }

    /** Opens the photo in the phone's gallery. */
    fun open(context: Context, uri: String) {
        runCatching {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
        }
    }
}
