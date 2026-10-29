package eu.studiodeanna.yans

import android.content.Context
import eu.studiodeanna.yans.sync.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** A folder on the phone synced with one on the server. [treeUri] is the phone's folder as Android lent it. */
data class FolderPair(val id: String, val treeUri: String, val localName: String, val remoteFolder: String)

/** How a folder's last sync went: when, whether all went well, and what it did or why it failed. */
data class LastRun(val time: Long, val ok: Boolean, val message: String)

/**
 * What the app keeps: the account, the folders and how their last sync went, and whether to sync every 15
 * minutes. Kept in the app's private settings, which the system keeps from other apps; the app allows no
 * backup, so the app password stays on the phone.
 */
class Store private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("yans", Context.MODE_PRIVATE)
    private val stateFolder = File(context.filesDir, "state")

    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<Account?> = _account

    private val _pairs = MutableStateFlow(readPairs())
    val pairs: StateFlow<List<FolderPair>> = _pairs

    private val _lastRuns = MutableStateFlow(_pairs.value.mapNotNull { pair -> readLastRun(pair.id)?.let { pair.id to it } }.toMap())
    val lastRuns: StateFlow<Map<String, LastRun>> = _lastRuns

    private val _autoSync = MutableStateFlow(prefs.getBoolean("autoSync", false))
    val autoSync: StateFlow<Boolean> = _autoSync

    /** The server last typed, to offer again. */
    var lastServer: String
        get() = prefs.getString("lastServer", "") ?: ""
        set(value) = prefs.edit().putString("lastServer", value).apply()

    fun saveAccount(account: Account) {
        prefs.edit().putString("server", account.server).putString("loginName", account.loginName)
            .putString("userId", account.userId).putString("password", account.password).apply()
        _account.value = account
    }

    /**
     * Forgets the account, and what the folders were like when they last agreed with its server: another
     * account's folders may hold other files, and those must not be taken for files deleted.
     */
    fun logOut() {
        prefs.edit().remove("server").remove("loginName").remove("userId").remove("password").apply()
        stateFolder.deleteRecursively()
        _account.value = null
    }

    fun addPair(treeUri: String, localName: String, remoteFolder: String): FolderPair {
        val pair = FolderPair(UUID.randomUUID().toString(), treeUri, clean(localName), clean(remoteFolder.trim('/', ' ')))
        writePairs(_pairs.value + pair)
        return pair
    }

    fun removePair(pair: FolderPair) {
        writePairs(_pairs.value.filter { it.id != pair.id })
        stateFile(pair).delete()
        prefs.edit().remove("lastRun.${pair.id}").apply()
        _lastRuns.value -= pair.id
    }

    fun stateFile(pair: FolderPair) = File(stateFolder, pair.id)

    fun saveLastRun(pair: FolderPair, run: LastRun) {
        prefs.edit().putString("lastRun.${pair.id}", "${run.time}\t${run.ok}\t${run.message}").apply()
        _lastRuns.value += pair.id to run
    }

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean("autoSync", on).apply()
        _autoSync.value = on
    }

    private fun readAccount(): Account? {
        val server = prefs.getString("server", null) ?: return null
        return Account(
            server = server,
            loginName = prefs.getString("loginName", null) ?: return null,
            userId = prefs.getString("userId", null) ?: return null,
            password = prefs.getString("password", null) ?: return null,
        )
    }

    private fun readPairs(): List<FolderPair> = (prefs.getString("pairs", "") ?: "").lines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size == 4) FolderPair(fields[0], fields[1], fields[2], fields[3]) else null
    }

    private fun writePairs(pairs: List<FolderPair>) {
        prefs.edit().putString("pairs", pairs.joinToString("\n") { "${it.id}\t${it.treeUri}\t${it.localName}\t${it.remoteFolder}" }).apply()
        _pairs.value = pairs
    }

    private fun readLastRun(id: String): LastRun? {
        val fields = (prefs.getString("lastRun.$id", null) ?: return null).split('\t', limit = 3)
        if (fields.size != 3) return null
        return LastRun(fields[0].toLongOrNull() ?: return null, fields[1] == "true", fields[2])
    }

    /** Tabs and line breaks would split the fields they are kept in. */
    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
