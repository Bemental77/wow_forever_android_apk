package com.wowforever.wow

import android.content.Context
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Copies the newest Battle.net, WoW and DXVK logs from the container to
 * Android/data/com.wowforever/files/logs, where they can be read over USB or attached to a bug
 * report. The container itself is private app storage.
 */
object WowLogExport {
    private const val TAG = "WowLogExport"
    private const val KEEP_PER_DIR = 12
    private const val MAX_FILE_BYTES = 8L shl 20

    private val SOURCES = listOf(
        "battlenet" to "C:\\users\\xuser\\AppData\\Local\\Battle.net\\Logs",
        "battlenet-agent" to "C:\\ProgramData\\Battle.net\\Agent\\Logs",
        "wow-logs" to "${Wow.WOW_DIR}\\Logs",
        "wow-errors" to "${Wow.WOW_DIR}\\Errors",
        "dxvk" to "C:\\dxvklog",
    )

    fun run(ctx: Context, c: Container) {
        val outRoot = ctx.getExternalFilesDir("logs") ?: return
        for ((name, winDir) in SOURCES) {
            try {
                val dir = Wow.winToHost(c, winDir).takeIf { it.isDirectory } ?: continue
                val newest = dir.listFiles { f -> f.isFile && !f.name.endsWith(".dmp", true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(KEEP_PER_DIR)
                    ?: continue
                val out = File(outRoot, name).apply { deleteRecursively(); mkdirs() }
                for (f in newest) copyTail(f, File(out, f.name))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Log export failed for %s", name)
            }
        }
    }

    /** Copies [src], keeping only its last [MAX_FILE_BYTES] if it is larger. */
    private fun copyTail(src: File, dst: File) {
        src.inputStream().use { input ->
            val skip = (src.length() - MAX_FILE_BYTES).coerceAtLeast(0)
            if (skip > 0) input.skip(skip)
            dst.outputStream().use { input.copyTo(it) }
        }
        dst.setLastModified(src.lastModified())
    }
}
