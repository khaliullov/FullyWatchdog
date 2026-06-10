package su.leandr.watchdog.fully

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlin.random.Random

object FullyScheduler {
    fun schedule(context: Context, delayMs: Long? = null, reason: String = "JOB", currentJobId: Int? = null): Int {
        val now = System.currentTimeMillis()
        val isRoutine = (reason == "JOB")
        FileLogger.log(context, "--- Scheduler: start (reason=$reason, current=#$currentJobId) ---", toFile = !isRoutine)
        
        if (!WatchdogSettings.isEnabled(context)) {
            FileLogger.log(context, "Scheduler: DISABLED")
            cancelAll(context)
            return JobScheduler.RESULT_FAILURE
        }

        val prefs = context.getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, Context.MODE_PRIVATE)
        
        // Anti-storm guard: prevent multiple schedules within a very short window
        // But DON'T skip if we are in a regular JOB cycle, unless it's suspiciously fast (< 1s)
        val lastSchedule = prefs.getLong(FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS, 0)
        val minInterval = if (isRoutine) 1000L else 5000L
        if (now - lastSchedule < minInterval) {
            FileLogger.log(context, "Scheduler: SKIP (storm protection, diff=${now - lastSchedule}ms)", toFile = false)
            return JobScheduler.RESULT_SUCCESS
        }

        // Determine next ID: Ping-Pong 1001 <-> 1002
        val nextId = when (currentJobId) {
            FullyWatchdogConfig.JOB_ID_A -> FullyWatchdogConfig.JOB_ID_B
            FullyWatchdogConfig.JOB_ID_B -> FullyWatchdogConfig.JOB_ID_A
            else -> {
                // External trigger: pick the one that is NOT currently running/pending if possible
                val scheduler = context.getSystemService(JobScheduler::class.java)
                val pending = try { scheduler?.allPendingJobs } catch (e: Exception) { null }
                if (pending?.any { it.id == FullyWatchdogConfig.JOB_ID_A } == true) {
                    FullyWatchdogConfig.JOB_ID_B
                } else {
                    FullyWatchdogConfig.JOB_ID_A
                }
            }
        }
        
        prefs.edit()
            .putLong(FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS, now)
            .putInt("last_job_id", nextId)
            .apply()

        WatchdogSettings.setTriggerReason(context, reason)

        val baseDelay = delayMs ?: WatchdogSettings.intervalMs(context)
        val jitter = if (baseDelay > FullyWatchdogConfig.MAX_JITTER_MS) Random.nextLong(0, FullyWatchdogConfig.MAX_JITTER_MS) else 0L
        val finalDelay = maxOf(0L, baseDelay + jitter)

        val component = ComponentName(context, FullyWatchJob::class.java)
        
        // Margin for override deadline to prevent "stuck" jobs
        // Use a generous deadline to allow the system some flexibility
        val deadline = maxOf(finalDelay * 3, FullyWatchdogConfig.DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS)
        
        return try {
            val jobInfo = JobInfo.Builder(nextId, component)
                .setMinimumLatency(finalDelay)
                .setOverrideDeadline(deadline)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()

            val scheduler = context.getSystemService(JobScheduler::class.java)
            val result = scheduler?.schedule(jobInfo) ?: JobScheduler.RESULT_FAILURE
            
            // IMPORTANT: We do NOT call scheduler.cancel() for the other ID here.
            // This prevents the "canceled" status that was killing the currently running job.
            // Ping-pong IDs naturally overwrite themselves in the scheduler queue.
            
            FileLogger.log(context, "Scheduler: OK - #$nextId scheduled. Result=$result, delay=${finalDelay}ms", toFile = !isRoutine)
            result
        } catch (e: Exception) {
            FileLogger.log(context, "Scheduler: ERROR - ${e.message}")
            JobScheduler.RESULT_FAILURE
        }
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler?.cancel(FullyWatchdogConfig.JOB_ID_A)
        scheduler?.cancel(FullyWatchdogConfig.JOB_ID_B)
        FileLogger.log(context, "Scheduler: ALL CANCELLED")
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val pending = try { scheduler.allPendingJobs } catch (e: Exception) { emptyList() }
        return pending.any { it.id == FullyWatchdogConfig.JOB_ID_A || it.id == FullyWatchdogConfig.JOB_ID_B }
    }
}
