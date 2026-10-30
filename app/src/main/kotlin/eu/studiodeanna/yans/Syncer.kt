package eu.studiodeanna.yans

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import eu.studiodeanna.yans.sync.EmptySideException
import eu.studiodeanna.yans.sync.LoginRefusedException
import eu.studiodeanna.yans.sync.Report
import eu.studiodeanna.yans.sync.Sync
import eu.studiodeanna.yans.sync.SyncException
import eu.studiodeanna.yans.sync.SyncState
import eu.studiodeanna.yans.sync.WebDavTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Syncs the folders, one sync at a time, however it was asked for: in the app, from the tile, or on their schedule. */
object Syncer {
    private val lock = ReentrantLock()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _running = MutableStateFlow<String?>(null)

    /** The id of the folder being synced now, if any. */
    val running: StateFlow<String?> = _running

    /** Whether a sync is going. */
    val busy get() = lock.isLocked

    /**
     * Syncs [only] these folders, or all of them; does nothing, and returns false, if a sync is going already.
     * In the [background], conflicts and failures are told in a notification too.
     */
    fun sync(context: Context, only: List<FolderPair>? = null, background: Boolean = false): Boolean {
        if (!lock.tryLock()) return false
        try {
            val store = Store.get(context)
            val account = store.account.value ?: return true
            val problems = mutableListOf<String>()
            val ids = only?.map { it.id }?.toSet()
            // The folders as they are now, in case one was changed or removed since it was asked for.
            for (pair in store.pairs.value.filter { ids == null || it.id in ids }) {
                _running.value = pair.id
                val run = syncPair(context, store, pair, account)
                store.saveLastRun(pair, run.first)
                if (run.second) problems += "${pair.localName}: ${run.first.message}"
            }
            if (background && problems.isNotEmpty()) notify(context, problems)
            return true
        } finally {
            _running.value = null
            lock.unlock()
        }
    }

    /** How the sync went, and whether that is worth a notification. */
    private fun syncPair(context: Context, store: Store, pair: FolderPair, account: eu.studiodeanna.yans.sync.Account): Pair<LastRun, Boolean> {
        val now = System.currentTimeMillis()
        val state = SyncState.read(store.stateFile(pair))
        return try {
            val local = SafTree(context, Uri.parse(pair.treeUri))
            val remote = WebDavTree(client, account.davRoot, pair.remoteFolder, account.loginName, account.password)
            val report = Sync(local, remote, state, File(context.cacheDir, "carry"), direction = pair.direction, keep = pair.keep).run()
            LastRun(now, report.failed.isEmpty(), summary(context, report)) to (report.conflicts.isNotEmpty() || report.failed.isNotEmpty())
        } catch (e: Exception) {
            LastRun(now, false, failure(context, e)) to true
        } finally {
            SyncState.write(store.stateFile(pair), state)
        }
    }

    private fun summary(context: Context, report: Report): String {
        val res = context.resources
        val parts = buildList {
            if (report.uploaded.isNotEmpty()) add(res.getQuantityString(R.plurals.uploaded, report.uploaded.size, report.uploaded.size))
            if (report.downloaded.isNotEmpty()) add(res.getQuantityString(R.plurals.downloaded, report.downloaded.size, report.downloaded.size))
            val deleted = report.deletedHere.size + report.deletedThere.size
            if (deleted > 0) add(res.getQuantityString(R.plurals.deleted, deleted, deleted))
            if (report.conflicts.isNotEmpty()) {
                add(res.getQuantityString(R.plurals.conflicts, report.conflicts.size, report.conflicts.size, report.conflicts.joinToString()))
            }
            if (report.failed.isNotEmpty()) {
                val (path, why) = report.failed.entries.first()
                add(res.getQuantityString(R.plurals.failed, report.failed.size, report.failed.size, "$path: $why"))
            }
        }
        return if (parts.isEmpty()) context.getString(R.string.up_to_date) else parts.joinToString(" · ")
    }

    private fun failure(context: Context, e: Exception): String = when (e) {
        is EmptySideException -> context.getString(if (e.here) R.string.empty_here else R.string.empty_there)
        is LoginRefusedException -> context.getString(R.string.login_refused)
        is SyncException -> e.message ?: e.javaClass.simpleName
        is IOException -> context.getString(R.string.cant_reach, e.message ?: e.javaClass.simpleName)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun notify(context: Context, problems: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = problems.joinToString("\n")
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }

    private const val CHANNEL = "problems"
}
