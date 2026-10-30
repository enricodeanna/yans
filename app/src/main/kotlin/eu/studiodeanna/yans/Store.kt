package eu.studiodeanna.yans

import android.content.Context
import eu.studiodeanna.yans.sync.Account
import eu.studiodeanna.yans.sync.Direction
import eu.studiodeanna.yans.sync.Keep
import eu.studiodeanna.yans.sync.Schedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/**
 * A folder on the phone synced with one on the server. [treeUri] is the phone's folder as Android lent it;
 * [keep] counts only for a one-way [direction].
 */
data class FolderPair(
    val id: String,
    val treeUri: String,
    val localName: String,
    val remoteFolder: String,
    val direction: Direction = Direction.BOTH,
    val schedule: Schedule = Schedule.Default,
    val keep: Keep = Keep(),
)

/** How a folder's last sync went: when, whether all went well, and what it did or why it failed. */
data class LastRun(val time: Long, val ok: Boolean, val message: String)

/**
 * What the app keeps: the account, the folders and how their last sync went, and when folders sync by
 * themselves. Kept in the app's private settings, which the system keeps from other apps; the app allows no
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

    private val _defaultSchedule = MutableStateFlow(readDefaultSchedule())

    /** When the folders that have no schedule of their own sync by themselves. */
    val defaultSchedule: StateFlow<Schedule> = _defaultSchedule

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

    fun addPair(treeUri: String, localName: String, remoteFolder: String, direction: Direction, schedule: Schedule, keep: Keep): FolderPair {
        val pair = FolderPair(
            UUID.randomUUID().toString(), treeUri, clean(localName), clean(remoteFolder.trim('/', ' ')), direction, schedule, keep,
        )
        writePairs(_pairs.value + pair)
        return pair
    }

    /**
     * Changes how a folder syncs. A change of direction forgets what the two sides last agreed on: what one
     * way left behind (files the receiving side deleted, or never got) would read as deletions to another.
     * The next sync compares the files on both sides again, as the first did.
     */
    fun updatePair(pair: FolderPair, direction: Direction, schedule: Schedule, keep: Keep) {
        val old = _pairs.value.find { it.id == pair.id } ?: return
        if (old.direction != direction) stateFile(pair).delete()
        writePairs(_pairs.value.map { if (it.id == pair.id) it.copy(direction = direction, schedule = schedule, keep = keep) else it })
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

    fun setDefaultSchedule(schedule: Schedule) {
        prefs.edit().putString("defaultSchedule", schedule.encode()).apply()
        _defaultSchedule.value = schedule
    }

    /** The schedule a folder follows: its own, or the default. */
    fun scheduleOf(pair: FolderPair): Schedule = if (pair.schedule == Schedule.Default) _defaultSchedule.value else pair.schedule

    /** Before there was a schedule to choose, a switch synced every 15 minutes. */
    private fun readDefaultSchedule(): Schedule = prefs.getString("defaultSchedule", null)?.let(Schedule::decode)
        ?.takeIf { it != Schedule.Default }
        ?: if (prefs.getBoolean("autoSync", false)) Schedule.Every(15) else Schedule.ByHand

    private fun readAccount(): Account? {
        val server = prefs.getString("server", null) ?: return null
        return Account(
            server = server,
            loginName = prefs.getString("loginName", null) ?: return null,
            userId = prefs.getString("userId", null) ?: return null,
            password = prefs.getString("password", null) ?: return null,
        )
    }

    /** A line for each folder; those kept before there were directions and schedules have only the first four fields. */
    private fun readPairs(): List<FolderPair> = (prefs.getString("pairs", "") ?: "").lines().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 4) return@mapNotNull null
        FolderPair(
            fields[0], fields[1], fields[2], fields[3],
            direction = fields.getOrNull(4)?.let { name -> Direction.entries.find { it.name == name } } ?: Direction.BOTH,
            schedule = fields.getOrNull(5)?.let(Schedule::decode) ?: Schedule.Default,
            keep = Keep(fields.getOrNull(6)?.toIntOrNull(), fields.getOrNull(7)?.toIntOrNull()),
        )
    }

    private fun writePairs(pairs: List<FolderPair>) {
        val text = pairs.joinToString("\n") {
            listOf(
                it.id, it.treeUri, it.localName, it.remoteFolder, it.direction.name, it.schedule.encode(),
                it.keep.newest?.toString().orEmpty(), it.keep.days?.toString().orEmpty(),
            ).joinToString("\t")
        }
        prefs.edit().putString("pairs", text).apply()
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
