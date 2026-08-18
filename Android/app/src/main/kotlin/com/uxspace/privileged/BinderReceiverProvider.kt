package com.uxspace.privileged

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Receives the [PrivilegedServer]'s Binder back from the shell-uid process and hands it to
 * the manager in the UxSpace app process.
 *
 * The server (started via `app_process`, runs as shell) cannot easily reach back into the
 * app — Binder hand-offs over a `ContentProvider.call` is the standard mechanism, since a
 * Binder survives a `Bundle` across the process boundary. Shizuku's own server uses the
 * same trick to publish itself to apps that use it.
 *
 * The provider is `exported` so that the shell-uid server can call it; we restrict callers
 * to the shell and the app's own uid.
 */
class BinderReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SET_BINDER) return null
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != Process.myUid()) {
            Log.w(TAG, "rejecting setBinder call from uid $callingUid")
            return null
        }
        val binder = extras?.getBinder(EXTRA_BINDER)
        val launchToken = extras?.getByteArray(EXTRA_LAUNCH_TOKEN)
        if (binder == null || launchToken == null) {
            Log.w(TAG, "setBinder called without binder or launch token")
            return null
        }
        Log.i(TAG, "received privileged binder from uid $callingUid")
        PrivilegedService.onPrivilegedBinder(binder, launchToken)
        return null
    }

    // ContentProvider's other entry points are unused — this provider is a Binder hand-off
    // channel, not a data store.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Provider authority — `content://com.uxspace.privileged`. */
        const val AUTHORITY = "com.uxspace.privileged"

        /** `ContentResolver.call` method name the server uses to hand its Binder over. */
        const val METHOD_SET_BINDER = "setBinder"

        /** Bundle key under which the Binder travels. */
        const val EXTRA_BINDER = "binder"

        /** Bundle key for the one-use token delivered to the helper over its ADB stdin. */
        const val EXTRA_LAUNCH_TOKEN = "launchToken"

        private const val TAG = "UxSpace/Privileged"
    }
}
