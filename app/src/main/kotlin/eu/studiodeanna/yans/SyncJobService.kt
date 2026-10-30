package eu.studiodeanna.yans

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Syncs in the background, when Android runs the job: the folders whose time has come, or all of them at the
 * tile's tap. A single job waits for the next folder due, and is set again after every sync.
 */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "sync") {
            val synced = try {
                val due = if (params.jobId == PLANNED) due(applicationContext) else null
                Syncer.sync(applicationContext, due, background = true)
            } finally {
                jobFinished(params, false)
            }
            // Only once this job is over: setting its own id again while it runs would stop it.
            if (synced) plan(applicationContext)
        }
        return true
    }

    // A sync stopped halfway loses nothing: the next one finishes it.
    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val PLANNED = 1
        private const val ONCE = 2

        /** A folder due within this much is synced along with the one due now, rather than a minute later. */
        private const val TOGETHER = 60_000L

        /** When a folder is next due by its schedule; null if it syncs only by hand. */
        fun nextSync(context: Context, pair: FolderPair, now: Long = System.currentTimeMillis()): Long? {
            val store = Store.get(context)
            return store.scheduleOf(pair).next(store.lastRuns.value[pair.id]?.time, now, ZoneId.systemDefault())
        }

        private fun due(context: Context): List<FolderPair> {
            val now = System.currentTimeMillis()
            return Store.get(context).pairs.value.filter { pair -> nextSync(context, pair, now)?.let { it <= now + TOGETHER } == true }
        }

        /**
         * Sets the job for when the next folder is due, with a network; or drops it if none syncs by itself.
         * Android may run it later than asked while the phone sleeps away from the charger, or the battery is
         * low. A sync going sets it again when it ends.
         */
        fun plan(context: Context) {
            if (Syncer.busy) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val store = Store.get(context)
            val now = System.currentTimeMillis()
            val next = if (store.account.value == null) null else store.pairs.value.mapNotNull { nextSync(context, it, now) }.minOrNull()
            if (next == null) {
                scheduler.cancel(PLANNED)
                return
            }
            scheduler.schedule(
                JobInfo.Builder(PLANNED, ComponentName(context, SyncJobService::class.java))
                    .setMinimumLatency((next - now).coerceAtLeast(0))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Syncs as soon as there is a network, even with the app closed. */
        fun syncNow(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ONCE, ComponentName(context, SyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
    }
}
