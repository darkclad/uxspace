package com.uxspace.privileged

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream

/**
 * Starts [PrivilegedServer] inside an `app_process` invocation over an open ADB shell stream.
 *
 * The returned [AdbStream] is the server's lifeline — as long as UxSpace holds it open the
 * server keeps running; closing the stream tears the server down. That is how the helper's
 * lifecycle is bound to UxSpace's: no orphan processes left behind.
 */
object ServerBootstrap {

    private const val TAG = "UxSpace/Privileged"

    /** Name shown for the helper process in `ps` / `top`. */
    private const val SERVER_NAME = "uxspace_privileged"

    /** Fully-qualified class with the `@JvmStatic fun main` `app_process` will invoke. */
    private const val SERVER_CLASS = "com.uxspace.privileged.PrivilegedServer"

    /**
     * Launch [PrivilegedServer] over [adb]. Returns the live shell stream (keep it open) or
     * `null` if the APK path could not be resolved or the stream could not be opened.
     */
    fun start(
        context: Context,
        adb: AdbConnectionManager,
        launchToken: ByteArray,
    ): AdbStream? {
        require(launchToken.size == PrivilegedLaunchToken.BYTE_COUNT)
        val apk = context.applicationInfo.sourceDir
        if (apk.isNullOrEmpty()) {
            Log.e(TAG, "no APK path on applicationInfo — cannot start server")
            return null
        }
        // `CLASSPATH=APK app_process /system/bin --nice-name=NAME CLASS` is the long-standing
        // pattern for running an app's class as a privileged process: the shell sets
        // CLASSPATH, app_process boots the runtime against it, and the named class's
        // `main(String[])` becomes the entry point.
        val command = "CLASSPATH=$apk app_process /system/bin --nice-name=$SERVER_NAME $SERVER_CLASS"
        Log.i(TAG, "starting privileged server: $command")
        val stream = runCatching { adb.openStream("shell:$command") }
            .onFailure { Log.e(TAG, "openStream failed", it) }
            .getOrNull()
            ?: return null

        // Deliver the launch authenticator over the shell's stdin so it is never exposed in
        // the helper's command line, environment, or logs.
        val delivered = runCatching {
            stream.openOutputStream().apply {
                write(launchToken)
                flush()
            }
        }.onFailure { Log.e(TAG, "launch-token delivery failed", it) }.isSuccess
        if (!delivered) {
            runCatching { stream.close() }
            return null
        }
        return stream
    }
}
