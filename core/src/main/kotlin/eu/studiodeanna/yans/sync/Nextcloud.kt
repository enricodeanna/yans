package eu.studiodeanna.yans.sync

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A Nextcloud account: [loginName] and [password] log in, [userId] names its files on WebDAV. */
data class Account(val server: String, val loginName: String, val userId: String, val password: String) {
    /** The account's files on WebDAV. */
    val davRoot: HttpUrl
        get() = server.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegments("remote.php/dav/files").addPathSegment(userId).addPathSegment("").build()
}

/**
 * Nextcloud's own way for an app to log in: the user approves it in the browser, and the server hands the
 * app a password of its own, which can be taken back from the server's security settings at any time.
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2
 */
class Nextcloud(private val client: OkHttpClient) {
    /** A login on [server], as the user typed it, waiting for the user's approval at [loginUrl]. */
    data class Login(val server: String, val loginUrl: String, val pollUrl: String, val token: String)

    /** Starts a login on [server]; open its [Login.loginUrl] in the browser, then [poll]. */
    fun startLogin(server: String): Login {
        val url = serverUrl(server) ?: throw SyncException("Not a server address: $server")
        val request = Request.Builder().url(url.newBuilder().addPathSegments("index.php/login/v2").build())
            .header("User-Agent", USER_AGENT).post(FormBody.Builder().build()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncException("No Nextcloud found at $url (${response.code})")
            val body = response.body!!.string()
            return Login(
                server = url.toString(),
                loginUrl = Json.string(body, "login") ?: throw SyncException("No Nextcloud found at $url"),
                pollUrl = Json.string(body, "endpoint") ?: throw SyncException("No Nextcloud found at $url"),
                token = Json.string(body, "token") ?: throw SyncException("No Nextcloud found at $url"),
            )
        }
    }

    /**
     * The account, once the user has approved the login; null while they have not yet. The account is on the
     * server as the user typed it, which the login reached: the address the server gives back is made from
     * what reached it, which behind a reverse proxy can be a name known only inside the proxy's network.
     */
    fun poll(login: Login): Account? {
        val request = Request.Builder().url(login.pollUrl).header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("token", login.token).build()).build()
        val body = client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw SyncException("The server answered ${response.code}")
            response.body!!.string()
        }
        val loginName = Json.string(body, "loginName") ?: throw SyncException("The server's answer is not understood")
        val password = Json.string(body, "appPassword") ?: throw SyncException("The server's answer is not understood")
        return account(login.server, loginName, password)
    }

    /**
     * The account of [loginName] on [server], checking the password. Its user id, which its WebDAV address is
     * made of, can differ from the name it logs in with (an email address, say), so it is asked for.
     */
    fun account(server: String, loginName: String, password: String): Account {
        val url = serverUrl(server) ?: throw SyncException("Not a server address: $server")
        val request = Request.Builder()
            .url(url.newBuilder().addPathSegments("ocs/v1.php/cloud/user").addQueryParameter("format", "json").build())
            .header("Authorization", Credentials.basic(loginName, password))
            .header("OCS-APIRequest", "true").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw LoginRefusedException("The server refused the login")
            if (!response.isSuccessful) throw SyncException("No Nextcloud found at $url (${response.code})")
            val userId = Json.string(response.body!!.string(), "id") ?: throw SyncException("No Nextcloud found at $url")
            return Account(url.toString().trimEnd('/'), loginName, userId, password)
        }
    }

    companion object {
        /** Shown in Nextcloud's list of the devices logged in. */
        const val USER_AGENT = "YANS (Android)"

        /** "cloud.example.org/nextcloud" as https://cloud.example.org/nextcloud; null if it is no address. */
        fun serverUrl(text: String): HttpUrl? {
            var address = text.trim().trimEnd('/')
            if (address.isEmpty()) return null
            if (!address.contains("://")) address = "https://$address"
            address = address.substringBefore("/index.php").substringBefore("/remote.php")
            return address.toHttpUrlOrNull()
        }
    }
}

/** Reads string fields out of small JSON answers, without a JSON library. */
internal object Json {
    fun string(body: String, key: String): String? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body) ?: return null
        return unescape(match.groupValues[1])
    }

    private fun unescape(text: String) = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\' || i == text.length) {
                append(c)
                continue
            }
            when (val next = text[i++]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'u' -> {
                    append(text.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                else -> append(next)
            }
        }
    }
}
