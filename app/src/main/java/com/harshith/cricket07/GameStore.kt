package com.harshith.cricket07

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.SecureRandom

/**
 * What the player has set up: their own browser build (recomp.js / recomp.wasm, copied into app storage)
 * and their own Cricket 07 folder (read in place through the Storage Access Framework, never copied by
 * the app itself: the page copies it into its private browser storage once).
 *
 * Nothing from the game ships inside the APK.
 */
object GameStore {
    private const val PREFS = "cricket07"
    private const val KEY_GAME_TREE = "game_tree"
    private const val KEY_TOKEN = "token"

    data class Entry(val path: String, val uri: Uri, val size: Long)

    @Volatile private var cachedTree: String? = null
    @Volatile private var cachedFiles: Map<String, Entry> = emptyMap()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Random path prefix, so other apps on the phone can't read the files from the loopback server. */
    fun token(ctx: Context): String {
        prefs(ctx).getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    fun buildDir(ctx: Context) = File(ctx.filesDir, "build")

    fun buildReady(ctx: Context): Boolean {
        val d = buildDir(ctx)
        return File(d, "recomp.js").isFile && File(d, "recomp.wasm").isFile
    }

    fun gameTree(ctx: Context): Uri? = prefs(ctx).getString(KEY_GAME_TREE, null)?.let(Uri::parse)

    fun setGameTree(ctx: Context, uri: Uri) {
        prefs(ctx).edit().putString(KEY_GAME_TREE, uri.toString()).apply()
        cachedTree = null
    }

    fun gameReady(ctx: Context): Boolean {
        val tree = gameTree(ctx) ?: return false
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    }

    fun ready(ctx: Context) = buildReady(ctx) && gameReady(ctx)

    // ------------------------------------------------------------ Storage Access Framework helpers

    data class Child(val name: String, val docId: String, val isDir: Boolean, val size: Long)

    fun children(ctx: Context, tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Child>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: ""
                out += Child(
                    name = c.getString(0) ?: continue,
                    docId = c.getString(1) ?: continue,
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        }
        return out
    }

    fun topLevel(ctx: Context, tree: Uri): List<Child> =
        children(ctx, tree, DocumentsContract.getTreeDocumentId(tree))

    /** Every file under the game folder, keyed by its path relative to the folder ("Data/x.big"). */
    fun gameFiles(ctx: Context, refresh: Boolean = false): Map<String, Entry> {
        val tree = gameTree(ctx) ?: return emptyMap()
        if (!refresh && cachedTree == tree.toString()) return cachedFiles
        val out = LinkedHashMap<String, Entry>()
        fun walk(docId: String, prefix: String) {
            for (ch in children(ctx, tree, docId)) {
                if (ch.name.startsWith(".")) continue
                val rel = if (prefix.isEmpty()) ch.name else "$prefix/${ch.name}"
                if (ch.isDir) walk(ch.docId, rel)
                else out[rel] = Entry(rel, DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId), ch.size)
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        cachedFiles = out
        cachedTree = tree.toString()
        return out
    }
}
