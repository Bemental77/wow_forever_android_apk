package com.wowforever.wow

import android.content.Context
import com.wowforever.ui.util.SnackbarManager
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Imports an existing World of Warcraft folder (copied from a PC or another device) so it doesn't
 * have to be downloaded again: Android/data/com.wowforever/files/import/World of Warcraft is moved
 * into the container. Each file is copied, size-checked, then removed from the import folder, so
 * an interrupted import resumes on the next launch.
 */
object WowImport {
    private const val TAG = "WowImport"
    private const val IMPORT_DIR = "import/World of Warcraft"

    fun importDir(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, IMPORT_DIR) }

    /** Moves the import folder into the container; returns the number of files imported. */
    fun runIfPresent(ctx: Context, c: Container): Int {
        val src = importDir(ctx)?.takeIf { it.isDirectory } ?: return 0
        val files = src.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            src.deleteRecursively()
            return 0
        }
        val dst = Wow.winToHost(c, Wow.WOW_ROOT)
        val total = files.sumOf { it.length() }
        Timber.tag(TAG).i("Importing %d files (%d MB) from %s", files.size, total shr 20, src)
        SnackbarManager.show("Importing World of Warcraft files (${total shr 30} GB). This takes a few minutes.")
        var done = 0
        for (f in files) {
            val rel = f.relativeTo(src).path
            val out = File(dst, rel)
            out.parentFile?.mkdirs()
            if (!(out.isFile && out.length() == f.length()) && !f.renameTo(out)) {
                f.inputStream().use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
            }
            if (out.length() != f.length()) error("Import failed for $rel (size mismatch)")
            f.delete()
            done++
            if (done % 200 == 0) Timber.tag(TAG).i("Imported %d/%d", done, files.size)
        }
        src.deleteRecursively()
        Timber.tag(TAG).i("Import finished: %d files", done)
        SnackbarManager.show("World of Warcraft import finished.")
        return done
    }
}
