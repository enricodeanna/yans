package eu.studiodeanna.yans.sync

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Enough of a Nextcloud for the app: login flow v2, the user's id, and WebDAV on the files of "enrico" (who
 * logs in as "enrico@example.org"), under /nextcloud as on a server that is not at its address's root.
 */
class FakeNextcloud : Dispatcher() {
    private class Node(val bytes: ByteArray?, val modified: Long, val etag: String)

    val server = MockWebServer().apply { dispatcher = this@FakeNextcloud }
    val root = "/nextcloud/remote.php/dav/files/enrico"
    private val nodes = sortedMapOf<String, Node>(root to Node(null, 0, "root"))
    private var etags = 0

    /**
     * The login is approved once polled this many times. The server it then names is one behind a reverse
     * proxy, as Nextcloud can name itself there: a name the app cannot reach.
     */
    var pollsBeforeApproval = 1

    val url get() = server.url("/nextcloud").toString()

    fun file(path: String): String? = nodes["$root/$path"]?.bytes?.decodeToString()

    fun folderExists(path: String) = nodes["$root/$path"]?.let { it.bytes == null } == true

    /** Puts a file, making the folders it is in when missing. */
    fun put(path: String, text: String, modified: Long) {
        var folder = root
        for (name in path.split('/').dropLast(1)) {
            folder = "$folder/$name"
            nodes.getOrPut(folder) { Node(null, 0, "d${++etags}") }
        }
        nodes["$root/$path"] = Node(text.toByteArray(), modified, "e${++etags}")
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = URLDecoder.decode(request.requestUrl!!.encodedPath.replace("+", "%2B"), "UTF-8").trimEnd('/')
        if (path == "/nextcloud/index.php/login/v2") {
            return json("""{"poll":{"token":"t0k","endpoint":"${server.url("/nextcloud/login/v2/poll")}"},"login":"https:\/\/x\/login"}""")
        }
        if (path == "/nextcloud/login/v2/poll") {
            if (pollsBeforeApproval-- > 1) return MockResponse().setResponseCode(404)
            return json("""{"server":"https:\/\/nextcloud.internal\/nextcloud","loginName":"enrico@example.org","appPassword":"app-pass"}""")
        }
        if (request.getHeader("Authorization") != okhttp3.Credentials.basic("enrico@example.org", "app-pass")) {
            return MockResponse().setResponseCode(401)
        }
        if (path == "/nextcloud/ocs/v1.php/cloud/user") {
            return json("""{"ocs":{"meta":{"status":"ok"},"data":{"enabled":true,"id":"enrico","display-name":"Enrico"}}}""")
        }
        val node = nodes[path]
        return when (request.method) {
            "PROPFIND" -> {
                if (node == null) return MockResponse().setResponseCode(404)
                val depth = request.getHeader("Depth")
                val children = if (depth == "1" && node.bytes == null) {
                    nodes.filterKeys { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
                } else {
                    emptyMap()
                }
                val body = (mapOf(path to node) + children).entries.joinToString("") { (p, n) -> response(p, n) }
                MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">$body</d:multistatus>""")
            }
            "GET" -> if (node?.bytes == null) MockResponse().setResponseCode(404) else MockResponse().setBody(Buffer().write(node.bytes))
            "PUT" -> {
                if (nodes[path.substringBeforeLast('/')]?.bytes != null || path.substringBeforeLast('/') !in nodes) {
                    return MockResponse().setResponseCode(409)
                }
                val modified = request.getHeader("X-OC-Mtime")!!.toLong() * 1000
                val etag = "e${++etags}"
                nodes[path] = Node(request.body.readByteArray(), modified, etag)
                MockResponse().setResponseCode(201).setHeader("ETag", "\"$etag\"").setHeader("X-OC-MTime", "accepted")
            }
            "MKCOL" -> when {
                node != null -> MockResponse().setResponseCode(405)
                path.substringBeforeLast('/') !in nodes -> MockResponse().setResponseCode(409)
                else -> {
                    nodes[path] = Node(null, 0, "d${++etags}")
                    MockResponse().setResponseCode(201)
                }
            }
            "DELETE" -> {
                if (node == null) return MockResponse().setResponseCode(404)
                nodes.keys.removeIf { it == path || it.startsWith("$path/") }
                MockResponse().setResponseCode(204)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun response(path: String, node: Node): String {
        // Encoded as Nextcloud encodes its hrefs, and with an entity, as XML allows.
        val href = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            .replace("%26", "&amp;")
        val time = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(node.modified).atZone(ZoneOffset.UTC))
        val props = if (node.bytes == null) {
            "<d:resourcetype><d:collection/></d:resourcetype><d:getetag>&quot;${node.etag}&quot;</d:getetag>"
        } else {
            "<d:resourcetype/><d:getetag>&quot;${node.etag}&quot;</d:getetag>" +
                "<d:getcontentlength>${node.bytes.size}</d:getcontentlength><d:getlastmodified>$time</d:getlastmodified>"
        }
        return "<d:response><d:href>$href${if (node.bytes == null) "/" else ""}</d:href>" +
            "<d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
