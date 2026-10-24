package com.harshith.cricket07

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * A tiny HTTP server on 127.0.0.1 that serves the launcher page, the player's own build and their own game
 * files with the COOP/COEP headers WebAssembly threads need. Loopback http is a secure context, so OPFS,
 * WebGPU and SharedArrayBuffer behave as on https. The port is fixed because the browser's private storage
 * (where the game files end up) belongs to the origin, and the origin includes the port.
 *
 * Routes, all under /t/<token>/:
 *   (empty), index.html     the launcher, with the Android shim and touch controls injected
 *   android/...             assets/android (shim, touch controls, import worker)
 *   build/...               the player's build, copied into app storage at setup
 *   gamefiles/manifest.json every file in the player's game folder, with sizes
 *   gamefiles/<path>        one of those files, streamed from the folder
 *   anything else           assets/web (the launcher's own scripts)
 */
class LocalServer private constructor(private val ctx: Context) {

    companion object {
        const val PORT = 47107
        private const val TAG = "Cricket07Server"
        @Volatile private var instance: LocalServer? = null

        fun ensure(ctx: Context): LocalServer = synchronized(this) {
            instance ?: LocalServer(ctx.applicationContext).also { it.start(); instance = it }
        }

        fun url(ctx: Context) = "http://127.0.0.1:$PORT/t/${GameStore.token(ctx)}/"
    }

    private val pool = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket

    private fun start() {
        server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 64)
        }
        Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                pool.execute { handle(s) }
            }
        }, "cricket07-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "listening on ${url(ctx)}")
    }

    // ------------------------------------------------------------------ HTTP

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val head = method == "HEAD"
                val out = s.getOutputStream().buffered(64 * 1024)
                if (method != "GET" && !head) send(out, 405, "text/plain", "method not allowed".toByteArray(), false)
                else route(out, parts[1].substringBefore('?').substringBefore('#'), head)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun route(out: OutputStream, rawPath: String, head: Boolean) {
        val prefix = "/t/${GameStore.token(ctx)}/"
        if (!rawPath.startsWith(prefix)) { send(out, 404, "text/plain", "not found".toByteArray(), head); return }
        val rel = URLDecoder.decode(rawPath.removePrefix(prefix), "UTF-8")
        if (rel.split('/').any { it == ".." || it == "." }) { send(out, 400, "text/plain", "bad path".toByteArray(), head); return }

        when {
            rel.isEmpty() || rel == "index.html" -> send(out, 200, "text/html; charset=utf-8", indexHtml(), head)
            rel.startsWith("android/") -> asset(out, rel, head)
            rel.startsWith("build/") -> {
                val f = File(GameStore.buildDir(ctx), rel.removePrefix("build/"))
                if (!f.isFile) send(out, 404, "text/plain", "no build file $rel: pick your build folder in the app's setup".toByteArray(), head)
                else stream(out, contentType(f.name), f.length(), head) { FileInputStream(f) }
            }
            rel == "gamefiles/manifest.json" -> {
                val files = GameStore.gameFiles(ctx, refresh = true)
                val arr = JSONArray()
                files.values.forEach { arr.put(JSONObject().put("path", it.path).put("size", it.size)) }
                send(out, 200, "application/json", JSONObject().put("files", arr).toString().toByteArray(), head)
            }
            rel.startsWith("gamefiles/") -> {
                val e = GameStore.gameFiles(ctx)[rel.removePrefix("gamefiles/")]
                if (e == null) send(out, 404, "text/plain", "not in the game folder".toByteArray(), head)
                else stream(out, "application/octet-stream", e.size, head) {
                    ctx.contentResolver.openInputStream(e.uri) ?: error("can't open ${e.path}")
                }
            }
            else -> asset(out, "web/$rel", head)
        }
    }

    private fun indexHtml(): ByteArray {
        var html = ctx.assets.open("web/index.html").bufferedReader().use { it.readText() }
        html = html.replace(
            "<script src=\"netplay.js\"></script>",
            "<link rel=\"stylesheet\" href=\"android/touch.css\">\n<script src=\"android/shim.js\"></script>\n<script src=\"netplay.js\"></script>",
        )
        html = html.replace(
            "<script src=\"loader.js\"></script>",
            "<script src=\"loader.js\"></script>\n<script src=\"android/touch.js\"></script>",
        )
        return html.toByteArray()
    }

    private fun asset(out: OutputStream, path: String, head: Boolean) {
        val bytes = try { ctx.assets.open(path).use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) send(out, 404, "text/plain", "not found".toByteArray(), head)
        else send(out, 200, contentType(path), bytes, head)
    }

    private fun contentType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "wasm" -> "application/wasm"
        "json" -> "application/json"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun headers(out: OutputStream, code: Int, type: String, length: Long) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val h = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            // what WebAssembly threads (SharedArrayBuffer) need: a cross-origin isolated page
            append("Cross-Origin-Opener-Policy: same-origin\r\n")
            append("Cross-Origin-Embedder-Policy: require-corp\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(h.toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, head: Boolean) {
        headers(out, code, type, body.size.toLong())
        if (!head) out.write(body)
    }

    private fun stream(out: OutputStream, type: String, length: Long, head: Boolean, open: () -> InputStream) {
        headers(out, 200, type, length)
        if (head) return
        open().use { it.copyTo(out, 256 * 1024) }
    }
}
