package ca.hoeve.flash

import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest

/** GET with timeouts (URL.readText/readBytes can hang forever on a stalled network). */
internal fun httpGet(url: String): ByteArray =
    URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }.getInputStream().use { it.readBytes() }

class Image(j: JSONObject) {
    val name: String = j.getString("name")
    val size = j.getLong("size")
    val hasAB = j.getBoolean("has_ab")
    val gptLun = j.optJSONObject("gpt")?.getInt("lun")
    val url: String = j.getString("url")
    /** SHA-256 of the unpacked image (unsparsified if [sparse]), as comma's agnos.py verifies it. */
    val hash: String = j.getString("hash_raw")
    val sparse = j.optBoolean("sparse")
    /** Everything except persist (never flashed) is needed; which userdata is used depends on the UFS chip. */
    val needed get() = name != "persist"
}

/** Local AGNOS store for one device type: release manifest plus unpacked images, named by hash so stale ones are obvious. */
class Images(base: File, val type: Int, private val status: (String, Double) -> Unit, private val log: (String) -> Unit) {
    private val dir = File(base, "agnos/$type")
    private val saved = File(dir, "manifest.json")

    private fun parse(text: String) = JSONArray(text).let { a -> List(a.length()) { Image(a.getJSONObject(it)) } }
        .also { check(it.isNotEmpty()) { "Manifest is empty" } }

    /** all-partitions.json with entries replaced by same-named ones from agnos.json (as flash.comma.ai does). */
    private fun fetch(): String {
        val url = MANIFESTS.getValue(type)
        val all = JSONArray(httpGet(url).decodeToString())
        val agnos = JSONArray(httpGet(url.replace("all-partitions", "agnos")).decodeToString())
        val byName = List(agnos.length(), agnos::getJSONObject).associateBy { it.getString("name") }
        return JSONArray(List(all.length(), all::getJSONObject).map { byName[it.getString("name")] ?: it }).toString()
    }

    fun file(im: Image) = File(dir, "${im.name}-${im.hash}.img")

    /** Latest manifest, saved for offline use; falls back to the saved one if offline. Drops images it no longer lists. */
    fun manifest(): List<Image> {
        dir.mkdirs()
        val text = runCatching { fetch() }.onSuccess(saved::writeText).getOrElse { e ->
            if (!saved.exists()) throw e
            log("Could not fetch the latest manifest (${e.message}); using the downloaded release")
            saved.readText()
        }
        return parse(text).also { list ->
            val keep = list.map { file(it).name }.toSet() + saved.name
            dir.listFiles()?.filter { it.name !in keep }?.forEach(File::delete)
        }
    }

    /** Local copy of [im], downloading and unpacking it first if needed. */
    fun ensure(im: Image): File {
        val f = file(im)
        if (f.exists()) return f.also { log("debug: using downloaded ${f.name}") }
        val part = File(dir, f.name + ".part")
        val label = "Downloading ${im.name}"
        status(label, 0.0)
        log("debug: GET ${im.url}")
        val http = HttpIn(im.url, log) { status(label, it) }
        // Sparse hashes cover the expanded image (up to ~96 GB), so only non-sparse images are hashed; xz's CRC covers the rest.
        val md = if (im.sparse) null else MessageDigest.getInstance("SHA-256")
        (if (im.url.endsWith(".xz")) XZInputStream(http.buffered(1 shl 16)) else http).use { inp ->
            part.outputStream().let { if (md == null) it else DigestOutputStream(it, md) }.use { inp.copyTo(it, 1 shl 20) }
        }
        if (md != null) {
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(im.hash, ignoreCase = true)) { part.delete(); error("${im.name}: SHA-256 mismatch (got $got)") }
        }
        check(part.renameTo(f)) { "Could not save ${f.name}" }
        return f
    }

    fun downloadAll() {
        val list = manifest().filter { it.needed }
        list.forEach(::ensure)
        log("AGNOS for ${NAMES[type]} downloaded: ${list.size} images, ${gb(bytes())}")
    }

    /** Images of the latest release that aren't downloaded yet (0 = up to date). Doesn't change the store. */
    fun missing() = parse(fetch()).count { it.needed && !file(it).exists() }

    /** Offline summary for the UI. */
    fun summary(): String {
        val list = runCatching { parse(saved.readText()).filter { it.needed } }.getOrNull() ?: return "Not downloaded"
        val have = list.count { file(it).exists() }
        return if (have == list.size) "Downloaded (${gb(bytes())})" else "$have of ${list.size} images downloaded (${gb(bytes())})"
    }

    fun bytes() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() = dir.deleteRecursively()

    /** Resumes with HTTP Range requests on network errors (like stream.js). */
    private class HttpIn(private val url: String, private val log: (String) -> Unit, private val onProgress: (Double) -> Unit) : InputStream() {
        private var pos = 0L
        private var total = -1L
        private var s: InputStream? = null

        private fun open(): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (pos > 0) c.setRequestProperty("Range", "bytes=$pos-")
            val code = c.responseCode
            if (code != 200 && code != 206 || pos > 0 && code != 206) throw IOException("HTTP $code for $url")
            if (total < 0) total = c.contentLengthLong
            return c.inputStream.also { s = it }
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var attempt = 0
            while (true) {
                try {
                    val n = (s ?: open()).read(b, off, len)
                    if (n > 0) {
                        pos += n
                        if (total > 0) onProgress(pos.toDouble() / total)
                    }
                    return n
                } catch (e: IOException) {
                    runCatching { s?.close() }
                    s = null
                    if (++attempt > 3) throw e
                    log("debug: download error at byte $pos (${e.message}), retry $attempt/3")
                    Thread.sleep(1000L * attempt)
                }
            }
        }

        override fun close() { s?.close() }
    }

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/commaai/openpilot"
        // Same as upstream flash.comma.ai src/config.js
        private val MANIFESTS = mapOf(
            32 to "$BASE/release-tici/system/hardware/tici/all-partitions.json",
            33 to "$BASE/release-tizi/openpilot/common/hardware/comma/all-partitions.json",
            34 to "$BASE/release-mici/openpilot/common/hardware/comma/all-partitions.json",
        )
        val NAMES = mapOf(32 to "comma 3", 33 to "comma 3X", 34 to "comma four")

        fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)
    }
}

/** Firehose programmer, downloaded from comma's flash repo (the URL flash.comma.ai uses) and kept for offline flashing. */
object Loader {
    private const val URL_ = "https://raw.githubusercontent.com/commaai/flash/master/src/QDL/programmer.bin"

    fun get(dir: File, log: (String) -> Unit): ByteArray {
        val f = File(dir, "programmer.bin")
        return try {
            val b = httpGet(URL_)
            check(b.size > 4 && b[0].toInt() == 0x7f && String(b, 1, 3) == "ELF") { "not an ELF file" }
            if (!f.exists() || !f.readBytes().contentEquals(b)) {
                val part = File(dir, "programmer.part").apply { writeBytes(b) }
                check(part.renameTo(f)) { "could not save it" }
                log("Loader updated (${b.size} bytes)")
            }
            b
        } catch (e: Exception) {
            if (!f.exists()) throw IllegalStateException("Could not download the flashing loader (${e.message}). Connect to the internet and try again.", e)
            log("Could not download the loader (${e.message}); using the saved copy")
            f.readBytes()
        }
    }
}
