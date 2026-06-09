package su.leandr.watchdog.fully

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import su.leandr.watchdog.fully.FullyWatchdogConfig.JOB_ID
import kotlin.random.Random

object FullyScheduler {
    fun schedule(context: Context, delayMs: Long? = null, reason: String = "JOB"): Int {
        if (!WatchdogSettings.isEnabled(context)) {
            cancel(context)
            return JobScheduler.RESULT_FAILURE
        }

        val now = System.currentTimeMillis()
        context.getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS, now)
            .apply()

        WatchdogSettings.setTriggerReason(context, reason)

        val baseDelay = delayMs ?: WatchdogSettings.intervalMs(context)
        // Add jitter to avoid sync with system tasks
        val jitter = if (baseDelay > FullyWatchdogConfig.MAX_JITTER_MS) Random.nextLong(0, FullyWatchdogConfig.MAX_JITTER_MS) else 0L
        val finalDelay = baseDelay + jitter

        val component = ComponentName(context, FullyWatchJob::class.java)
        val overrideDeadlineMs = WatchdogSettings.overrideDeadlineMs(context)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .setMinimumLatency(finalDelay)
            .setOverrideDeadline(maxOf(finalDelay, overrideDeadlineMs))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)
            .build()

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val result = scheduler.schedule(jobInfo)
        
        FileLogger.log(context, "Scheduled job: reason=$reason, delay=${finalDelay}ms, deadline=${maxOf(finalDelay, overrideDeadlineMs)}ms, result=$result")
        return result
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(JOB_ID)
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        return scheduler.allPendingJobs.any { it.id == JOB_ID }
    }
}
