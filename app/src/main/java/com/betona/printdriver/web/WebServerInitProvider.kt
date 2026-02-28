package com.betona.printdriver.web

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * ContentProvider that auto-starts the web server when the app process starts.
 * No existing code modification needed — just register in AndroidManifest.xml.
 */
class WebServerInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        Log.i(TAG, "Auto-starting web management server")
        WebServerService.start(ctx)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "WebServerInitProvider"
    }
}
