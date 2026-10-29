package eu.studiodeanna.yans.sync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The account's folders on the server, to choose one to sync. Paths have '/' between folders; "" is the top. */
class RemoteFolders(private val client: OkHttpClient, private val account: Account) {
    private val auth = Credentials.basic(account.loginName, account.password)

    /** The names of the folders in [path], in the order a person would look for them. */
    fun list(path: String): List<String> {
        val url = url(path)
        val request = Request.Builder().url(url).header("Authorization", auth).header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).build()
        client.newCall(request).execute().use { response ->
            check(response, path)
            val own = url.pathSegments.filter { it.isNotEmpty() }
            return DavResponse.entries(response.body!!.string()).filter { it.isFolder }.mapNotNull { entry ->
                val segments = url.resolve(entry.href)?.pathSegments?.filter { it.isNotEmpty() } ?: return@mapNotNull null
                if (segments.size == own.size + 1 && segments.subList(0, own.size) == own) segments.last() else null
            }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    /** Makes the folder [name] in [path]; returns its path. */
    fun make(path: String, name: String): String {
        val folder = if (path.isEmpty()) name else "$path/$name"
        val request = Request.Builder().url(url(folder)).header("Authorization", auth).method("MKCOL", null).build()
        client.newCall(request).execute().use { response ->
            // 405: the folder is there already, which is as good.
            if (response.code != 405) check(response, folder)
        }
        return folder
    }

    private fun url(path: String): HttpUrl = path.split('/').filter { it.isNotEmpty() }
        .fold(account.davRoot) { url, name -> url.newBuilder().addPathSegment(name).build() }

    private fun check(response: Response, path: String) {
        if (response.isSuccessful) return
        if (response.code == 401) throw LoginRefusedException("The server refused the login")
        if (response.code == 404) throw SyncException("${path.ifEmpty { "The folder" }} is not on the server")
        throw SyncException("The server answered ${response.code} for ${path.ifEmpty { "the folder" }}")
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    }
}
