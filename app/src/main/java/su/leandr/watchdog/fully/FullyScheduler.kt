package su.leandr.watchdog.fully

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import kotlin.random.Random

object FullyScheduler {
    /**
     * Планирует выполнение задачи Watchdog.
     *
     * @param reason Причина запуска. Специальные значения для отладки:
     *   - "DEBUG:SIMULATE_LEAK": Симулирует превышение лимита памяти (PSS), чтобы проверить
     *     реакцию планировщика и корректность работы ADB-команд force-stop/crash.
     *   - "CONTROL:KILL": Вызывает немедленную остановку целевого пакета через ADB Local Shell.
     *   - "RECOVERY_FROM_BLOCK": Используется для восстановления цепочки после обнаружения зависшего JobId.
     */
    fun schedule(context: Context, delayMs: Long? = null, reason: String = "JOB", currentJobId: Int? = null, targetJobId: Int? = null): Int {
        val now = System.currentTimeMillis()
        val isRoutine = (reason == "JOB" || reason == "RECOVERY_FROM_BLOCK")
        
        // Always log to file for debugging scheduler continuity
        FileLogger.log(context, "--- Scheduler: schedule call (reason=$reason, current=#$currentJobId, target=#$targetJobId) ---", toFile = true)
        
        if (!WatchdogSettings.isEnabled(context)) {
            FileLogger.log(context, "Scheduler: DISABLED")
            cancelAll(context)
            return JobScheduler.RESULT_FAILURE
        }

        val prefs = context.getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, Context.MODE_PRIVATE)
        
        // Anti-storm guard: prevent multiple schedules within a very short window.
        // We allow bypass if it's a routine job chain or if it has a specific targetJobId.
        val lastSchedule = prefs.getLong(FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS, 0)
        val minInterval = if (isRoutine) 500L else 5000L
        if (now - lastSchedule < minInterval && currentJobId == null && targetJobId == null && reason != "RECOVERY_FROM_BLOCK") {
            FileLogger.log(context, "Scheduler: SKIP (storm protection, diff=${now - lastSchedule}ms)", toFile = false)
            return JobScheduler.RESULT_SUCCESS
        }

        // Determine next ID: Ping-Pong 1001 <-> 1002
        val nextId = targetJobId ?: when (currentJobId) {
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

        // Only update the global reason if it's NOT a routine background check,
        // so we don't overwrite specific triggers like KILL or SIMULATE_LEAK
        if (!isRoutine) {
            WatchdogSettings.setTriggerReason(context, reason)
        }

        val baseDelay = delayMs ?: WatchdogSettings.intervalMs(context)
        val jitter = if (baseDelay > FullyWatchdogConfig.MAX_JITTER_MS) Random.nextLong(0, FullyWatchdogConfig.MAX_JITTER_MS) else 0L
        val finalDelay = maxOf(0L, baseDelay + jitter)

        val component = ComponentName(context, FullyWatchJob::class.java)
        
        // Margin for override deadline to prevent "stuck" jobs
        // Use a tighter deadline to force execution on TV systems
        val deadline = baseDelay + 5000L
        
        return try {
            val extras = PersistableBundle().apply {
                putString(FullyWatchdogConfig.EXTRA_REASON, reason)
            }

            val jobInfo = JobInfo.Builder(nextId, component)
                .setMinimumLatency(finalDelay)
                .setOverrideDeadline(deadline)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .setExtras(extras)
                .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()

            val scheduler = context.getSystemService(JobScheduler::class.java)
            val result = scheduler?.schedule(jobInfo) ?: JobScheduler.RESULT_FAILURE
            
            // IMPORTANT: We do NOT call scheduler.cancel() for the other ID here.
            // This prevents the "canceled" status that was killing the currently running job.
            // Ping-pong IDs naturally overwrite themselves in the scheduler queue.
            
            FileLogger.log(context, "Scheduler: OK - #$nextId scheduled. Result=$result, delay=${finalDelay}ms", toFile = true)
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
