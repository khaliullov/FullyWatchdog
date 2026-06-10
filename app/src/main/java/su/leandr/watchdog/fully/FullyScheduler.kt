package su.leandr.watchdog.fully

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlin.random.Random

object FullyScheduler {
    fun schedule(context: Context, delayMs: Long? = null, reason: String = "JOB"): Int {
        val now = System.currentTimeMillis()
        FileLogger.log(context, "--- Scheduler: start call (reason=$reason, delay=$delayMs) ---")
        
        if (!WatchdogSettings.isEnabled(context)) {
            FileLogger.log(context, "Scheduler: watchdog is DISABLED in settings")
            cancelAll(context)
            return JobScheduler.RESULT_FAILURE
        }

        val prefs = context.getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, Context.MODE_PRIVATE)
        
        // Ping-pong between two IDs to avoid "stuck" jobs on YaOS
        val lastId = prefs.getInt("last_job_id", FullyWatchdogConfig.JOB_ID_B)
        val nextId = if (lastId == FullyWatchdogConfig.JOB_ID_A) FullyWatchdogConfig.JOB_ID_B else FullyWatchdogConfig.JOB_ID_A
        
        prefs.edit()
            .putLong(FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS, now)
            .putInt("last_job_id", nextId)
            .apply()

        WatchdogSettings.setTriggerReason(context, reason)

        val baseDelay = delayMs ?: WatchdogSettings.intervalMs(context)
        val jitter = if (baseDelay > FullyWatchdogConfig.MAX_JITTER_MS) Random.nextLong(0, FullyWatchdogConfig.MAX_JITTER_MS) else 0L
        val finalDelay = maxOf(0L, baseDelay + jitter)

        val component = ComponentName(context, FullyWatchJob::class.java)
        val overrideDeadlineMs = WatchdogSettings.overrideDeadlineMs(context)
        
        return try {
            val jobInfo = JobInfo.Builder(nextId, component)
                .setMinimumLatency(finalDelay)
                .setOverrideDeadline(maxOf(finalDelay + 1000L, overrideDeadlineMs))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build()

            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler == null) {
                FileLogger.log(context, "Scheduler: ERROR - JobScheduler is NULL")
                return JobScheduler.RESULT_FAILURE
            }
            
            val result = scheduler.schedule(jobInfo)
            FileLogger.log(context, "Scheduler: OK - job #$nextId scheduled. Result=$result, delay=${finalDelay}ms")
            result
        } catch (e: Exception) {
            val errorMsg = "Scheduler: CRASH - ${e.javaClass.simpleName}: ${e.message}"
            FileLogger.log(context, errorMsg)
            Log.e("FullyScheduler", errorMsg, e)
            JobScheduler.RESULT_FAILURE
        }
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(FullyWatchdogConfig.JOB_ID_A)
        scheduler.cancel(FullyWatchdogConfig.JOB_ID_B)
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val pending = scheduler.allPendingJobs
        return pending.any { it.id == FullyWatchdogConfig.JOB_ID_A || it.id == FullyWatchdogConfig.JOB_ID_B }
    }
}
