package com.example.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

object StorageHelper {

    fun getPublicStorageDir(context: Context, isAudio: Boolean): File {
        val baseDir = if (isAudio) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val appFolder = File(baseDir, "TubeAIO")
        if (!appFolder.exists()) {
            appFolder.mkdirs()
        }
        if (appFolder.exists() && appFolder.canWrite()) {
            return appFolder
        }

        // Fallback to public Downloads directory
        val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TubeAIO")
        if (!dlDir.exists()) {
            dlDir.mkdirs()
        }
        if (dlDir.exists() && dlDir.canWrite()) {
            return dlDir
        }

        // Final fallback: external files dir
        val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    fun scanMediaFile(context: Context, file: File, mediaType: String) {
        try {
            val mimeType = if (mediaType == "AUDIO") "audio/*" else "video/*"
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d("StorageHelper", "Media scanner scanned: $path -> $uri")
            }

            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            context.sendBroadcast(scanIntent)
        } catch (e: Exception) {
            Log.e("StorageHelper", "Error scanning media file", e)
        }
    }
}
