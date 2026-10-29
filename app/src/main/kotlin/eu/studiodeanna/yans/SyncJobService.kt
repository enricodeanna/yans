package eu.studiodeanna.yans

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Syncs in the background, when Android runs the job: every 15 minutes, or once at the tile's tap. */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "sync") {
            try {
                Syncer.sync(applicationContext, background = true)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    // A sync stopped halfway loses nothing: the next one finishes it.
    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val EVERY_15_MINUTES = 1
        private const val ONCE = 2

        /**
         * Asks Android to sync every 15 minutes, the most often it allows, when there is a network; or stops
         * asking. Android may wait longer when the phone is asleep or the battery low.
         */
        fun schedule(context: Context, on: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!on) {
                scheduler.cancel(EVERY_15_MINUTES)
                return
            }
            // Asked again, the job would start counting its 15 minutes again.
            if (scheduler.getPendingJob(EVERY_15_MINUTES) != null) return
            scheduler.schedule(
                JobInfo.Builder(EVERY_15_MINUTES, ComponentName(context, SyncJobService::class.java))
                    .setPeriodic(TimeUnit.MINUTES.toMillis(15))
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
