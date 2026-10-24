package com.harshith.cricket07

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves any crash to crash.txt (with the phone model and Android version) before the app closes, so the
 * next start can show it with a Copy button instead of just closing again.
 */
class App : Application() {

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "crash.txt")
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = packageManager.getPackageInfo(packageName, 0)
                crashFile(this).writeText(
                    "Cricket 07 ${info.versionName} (${info.longVersionCode})\n" +
                        "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${thread.name}\n\n$sw",
                )
            } catch (_: Throwable) { /* never fail while reporting */ }
            previous?.uncaughtException(thread, e)
        }
    }
}
