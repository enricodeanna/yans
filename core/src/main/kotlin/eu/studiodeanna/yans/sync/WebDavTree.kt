package eu.studiodeanna.yans.sync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A folder on a WebDAV server: on Nextcloud, [davRoot] is the account's files,
 * https://server/remote.php/dav/files/<user id>/, and [folder] a folder in them ("Games/ScummVM Saves").
 * The folder is made when the first file is written to it.
 */
class WebDavTree(
    private val client: OkHttpClient,
    private val davRoot: HttpUrl,
    folder: String,
    loginName: String,
    password: String,
) : Tree {
    private val auth = Credentials.basic(loginName, password)
    private val folderSegments = segments(folder)
    private val root = folderSegments.fold(davRoot) { url, name -> url.newBuilder().addPathSegment(name).build() }
    private val rootSegments = root.pathSegments.filter { it.isNotEmpty() }

    /** Folders known to be there, so that writing a file makes only the ones missing. */
    private val folders = mutableSetOf<String>()

    override fun list(): Map<String, FileInfo> {
        folders.clear()
        val files = mutableMapOf<String, FileInfo>()
        val pending = ArrayDeque(listOf(""))
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            val entries = propfind(folder) ?: if (folder.isEmpty()) return files else continue
            folders += folder
            for (entry in entries) {
                if (entry.path == folder) continue
                if (entry.isFolder) pending += entry.path
                else files[entry.path] = FileInfo(entry.path, entry.size, entry.modified, entry.etag)
            }
        }
        return files
    }

    override fun read(path: String, sink: OutputStream) {
        call(request(path).get()).use { response ->
            check(response, path)
            response.body!!.byteStream().use { it.copyTo(sink) }
        }
    }

    override fun write(path: String, source: InputStream, size: Long, modified: Long): FileInfo {
        makeFolders(path.substringBeforeLast('/', ""))
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeAll(source.source())
            }
        }
        // Nextcloud keeps the time given here as the file's, rather than the time it was uploaded.
        val etag = call(request(path).put(body).header("X-OC-Mtime", (modified / 1000).toString())).use { response ->
            check(response, path)
            response.header("OC-ETag") ?: response.header("ETag")
        }
        if (etag != null) return FileInfo(path, size, modified, unquote(etag))
        return propfind(path, depth = 0)?.firstOrNull()?.let { FileInfo(path, it.size, it.modified, it.etag) }
            ?: throw SyncException("The server did not keep $path")
    }

    override fun delete(path: String) {
        call(request(path).delete()).use { response ->
            if (response.code != 404) check(response, path)
        }
    }

    /** The folder's entries, itself included; null if it is not there. */
    private fun propfind(path: String, depth: Int = 1): List<DavEntry>? {
        val request = request(path).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", "$depth")
        return call(request).use { response ->
            if (response.code == 404) return null
            check(response, path)
            DavResponse.entries(response.body!!.string()).mapNotNull { entry ->
                val segments = root.resolve(entry.href)?.pathSegments?.filter { it.isNotEmpty() } ?: return@mapNotNull null
                if (segments.size < rootSegments.size || segments.subList(0, rootSegments.size) != rootSegments) {
                    return@mapNotNull null
                }
                entry.copy(path = segments.drop(rootSegments.size).joinToString("/"))
            }
        }
    }

    private fun makeFolders(path: String) {
        if (path in folders) return
        if (path.isEmpty()) {
            // The synced folder itself, and the folders it is in.
            var url = davRoot
            for (name in folderSegments) {
                url = url.newBuilder().addPathSegment(name).build()
                makeFolder(url, "")
            }
        } else {
            makeFolders(path.substringBeforeLast('/', ""))
            makeFolder(url(path), path)
        }
        folders += path
    }

    private fun makeFolder(url: HttpUrl, path: String) {
        call(Request.Builder().url(url).header("Authorization", auth).method("MKCOL", null)).use { response ->
            // 405: the folder is there already.
            if (response.code != 405) check(response, path)
        }
    }

    private fun url(path: String) =
        segments(path).fold(root) { url, name -> url.newBuilder().addPathSegment(name).build() }

    private fun request(path: String) = Request.Builder().url(url(path)).header("Authorization", auth)

    private fun call(request: Request.Builder): Response = client.newCall(request.build()).execute()

    private fun check(response: Response, path: String) {
        if (response.isSuccessful) return
        if (response.code == 401) throw LoginRefusedException("The server refused the login")
        throw SyncException("The server answered ${response.code} for ${path.ifEmpty { "the folder" }}")
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/></d:prop>
</d:propfind>"""

        fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
    }
}

/** One file or folder of a PROPFIND answer; [path] is filled in by the tree, from [href]. */
internal data class DavEntry(
    val href: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long,
    val etag: String,
    val path: String = "",
)

/** Reads PROPFIND answers, whatever prefix the server gives the DAV namespace, without an XML parser. */
internal object DavResponse {
    private val RESPONSE = Regex("""<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
    private val COLLECTION = Regex("""<(?:\w+:)?collection\s*/>""")

    private fun property(name: String) =
        Regex("""<(?:\w+:)?$name\b[^>]*>([^<]*)</(?:\w+:)?$name>""")

    private val HREF = property("href")
    private val ETAG = property("getetag")
    private val LENGTH = property("getcontentlength")
    private val MODIFIED = property("getlastmodified")

    fun entries(body: String): List<DavEntry> = RESPONSE.findAll(body).mapNotNull { match ->
        val entry = match.groupValues[1]
        val href = HREF.find(entry)?.groupValues?.get(1)?.let(::unescapeXml)?.trim() ?: return@mapNotNull null
        DavEntry(
            href = href,
            isFolder = COLLECTION.containsMatchIn(entry),
            size = LENGTH.find(entry)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0,
            modified = MODIFIED.find(entry)?.groupValues?.get(1)?.let(::parseTime) ?: 0,
            etag = ETAG.find(entry)?.groupValues?.get(1)?.let { unquote(unescapeXml(it)) } ?: "",
        )
    }.toList()

    private fun parseTime(text: String) =
        runCatching { ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()

    private fun unescapeXml(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}

/** An ETag without the quotes around it, as Nextcloud gives it in some answers and not in others. */
internal fun unquote(etag: String) = etag.trim().removePrefix("W/").trim('"')
