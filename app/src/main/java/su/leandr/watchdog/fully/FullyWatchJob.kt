package su.leandr.watchdog.fully

import android.app.ActivityManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_SOFT_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_START_ATTEMPTED_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_CHECK_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CHECKS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SOFT_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_OK
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SUPPRESSED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_WINDOW_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_HARD_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_SOFT_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.USAGE_EVENTS_SHORT_LOOKBACK_MS

class FullyWatchJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: -1
        
        // Immediate log to track scheduler health
        FileLogger.log(this, "--- Job Execution Start: #$jobId ---", toFile = true)

        if (WatchdogSettings.isEnabled(this)) {
            FullyScheduler.schedule(this, currentJobId = jobId)
        } else {
            FileLogger.log(this, "Job #$jobId: Watchdog disabled, stopping.")
            jobFinished(params, false)
            return false
        }

        Thread {
            try {
                performRescueCycle(jobId)
            } catch (e: Exception) {
                FileLogger.log(this, "Job #$jobId Error: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        FileLogger.log(this, "!!! Job #${params?.jobId} STOPPED by system !!!", toFile = true)
        return false
    }

    private fun performRescueCycle(jobId: Int) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val availMb = memInfo.availMem / 1024 / 1024
        val target = WatchdogSettings.fullyPackage(this).trim()
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val reason = WatchdogSettings.getTriggerReason(this)
        
        val lastCheck = prefs.getLong(PREF_LAST_CHECK_MS, 0L)
        val interval = WatchdogSettings.intervalMs(this)
        val deadmanThreshold = maxOf(60000L, interval * 5)
        
        if (lastCheck > 0 && (now - lastCheck) > deadmanThreshold) {
            FileLogger.log(this, "[#$jobId] DEADMAN: Frozen for ${(now - lastCheck) / 1000}s. Cleaning up.")
            prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply()
            
            am.runningAppProcesses?.forEach {
                if (it.processName != packageName && it.processName != target) {
                    runCatching { am.killBackgroundProcesses(it.processName) }
                }
            }
            fastKillTarget(target)
            return
        }
        
        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply()

        if (memInfo.lowMemory || availMb < 200) {
            FileLogger.log(this, "[#$jobId] PANIC: Low memory (${availMb}MB). Killing $target.")
            fastKillTarget(target)
            WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
            return
        }

        val maxMb = WatchdogSettings.maxMemoryMb(this)
        if (maxMb > 0) {
            val proc = am.runningAppProcesses?.find { it.processName == target }
            if (proc != null) {
                val rssMb = getRssMb(proc.pid)
                if (rssMb > maxMb) {
                    FileLogger.log(this, "[#$jobId] Memory Limit: ${rssMb}MB > ${maxMb}MB. Restarting.")
                    fastKillTarget(target)
                    WatchdogSettings.increment(this, FullyWatchdogConfig.PREF_STAT_MEM_RESTARTS)
                    WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
                    return
                }
            }
        }

        runCatching { checkAndRecoverFully(jobId, reason) }
    }

    private fun checkAndRecoverFully(jobId: Int, reason: String) {
        WatchdogSettings.increment(this, PREF_STAT_CHECKS)
        val topActivity = detectTopActivity()
        val targetPackage = WatchdogSettings.fullyPackage(this).trim()
        val currentTop = topActivity.packageName?.trim() ?: "null"
        
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val isFullyOnTop = currentTop.equals(targetPackage, ignoreCase = true)
        val isSystemOnTop = FullyWatchdogConfig.SYSTEM_WHITELIST.any { it.equals(currentTop, ignoreCase = true) }
        val lastSoftRelaunchMs = prefs.getLong(PREF_LAST_SOFT_RELAUNCH_MS, 0L)
        val softRelaunchMs = WatchdogSettings.softRelaunchMs(this)

        var resultDetail: String
        val action = when {
            reason == "USER_RESTART_RESCUE" -> {
                fastKillTarget(targetPackage)
                resultDetail = "user rescue"
                "user rescue"
            }
            currentTop == packageName -> {
                resultDetail = "skip: self"
                "skip: self"
            }
            isSystemOnTop -> {
                resultDetail = "skip: system"
                "skip: system"
            }
            isFullyOnTop -> {
                WatchdogSettings.increment(this, PREF_STAT_OK)
                if (softRelaunchMs > 0L && now - lastSoftRelaunchMs >= softRelaunchMs) {
                    prefs.edit().putLong(PREF_LAST_SOFT_RELAUNCH_MS, now).apply()
                    resultDetail = "ok: Fully is foreground (soft relaunch deferred)"
                    null
                } else {
                    resultDetail = "ok: Fully is foreground"
                    null
                }
            }
            else -> {
                if (isFullyProcessAlive() && (now - WatchdogSettings.lastStartAttemptedMs(this) < 60000)) {
                    fastKillTarget(targetPackage)
                    resultDetail = "hard recovery"
                    "hard recovery"
                } else {
                    val res = tryStartFully(cleanStart = !isFullyProcessAlive())
                    resultDetail = res
                    res
                }
            }
        }
        
        val logMessage = "[#$jobId] Final Result: $resultDetail | Top: ${topActivity.packageName}/${topActivity.className} (${topActivity.source}) | FullyOnTop=$isFullyOnTop | SystemOnTop=$isSystemOnTop"
        
        // Log EVERYTHING to file for better visibility
        FileLogger.log(this, logMessage, toFile = true)

        if (action != null && action != "skip: self" && action != "skip: system") {
            WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
        }
    }

    private fun fastKillTarget(packageName: String) {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastKillAttempts(this).filter { now - it < STORM_WINDOW_MS }
        if (attempts.size >= STORM_HARD_MAX) {
            FileLogger.log(this, "Kill suppressed: storm protection", toFile = true)
            return
        }
        WatchdogSettings.setLastKillAttempts(this, attempts + now)
        
        FileLogger.log(this, "Executing hard kill for $packageName...", toFile = true)
        
        val p1 = runCatching { Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", packageName)).waitFor() }.getOrDefault(-1)
        runCatching { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(packageName) }
        val p2 = runCatching { Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName)).waitFor() }.getOrDefault(-1)
        
        Thread.sleep(500)
        val stillAlive = isFullyProcessAlive()
        FileLogger.log(this, "Kill results: pkill=$p1, am force-stop=$p2 | stillAlive=$stillAlive", toFile = true)
        
        tryStartFully(cleanStart = true)
    }

    private fun getRssMb(pid: Int): Int {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memInfo = am.getProcessMemoryInfo(intArrayOf(pid))
            if (memInfo.isNotEmpty()) memInfo[0].totalPss / 1024 else -1
        } catch (e: Exception) { -1 }
    }

    private fun isFullyProcessAlive(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val target = WatchdogSettings.fullyPackage(this)
        return am.runningAppProcesses?.any { it.processName == target } == true
    }

    private fun tryStartFully(cleanStart: Boolean): String {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastStartAttempts(this).filter { now - it < STORM_WINDOW_MS }
        if (attempts.size >= STORM_SOFT_MAX) {
            WatchdogSettings.increment(this, PREF_STAT_SUPPRESSED)
            return "start suppressed"
        }

        WatchdogSettings.setLastStartAttempts(this, attempts + now)
        WatchdogSettings.setLastStartAttemptedMs(this, now)

        val targetPackage = WatchdogSettings.fullyPackage(this)
        val intent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return "no intent"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        
        return try {
            startActivity(intent)
            val shellResult = runCatching {
                val comp = intent.component?.flattenToShortString() ?: targetPackage
                Runtime.getRuntime().exec(arrayOf("am", "start", "-n", comp)).waitFor()
            }.getOrDefault(-1)
            
            FileLogger.log(this, "Start attempt: shell_result=$shellResult", toFile = true)

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putLong(PREF_LAST_RELAUNCH_MS, now).apply()
            if (cleanStart) WatchdogSettings.increment(this, PREF_STAT_CRASH_RESTARTS)
            "started"
        } catch (e: Exception) { "failed" }
    }

    private fun detectTopActivity(): TopActivity {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Use a longer lookback to find the latest foreground change
        val lookback = 5 * 60_000L 
        
        val events = runCatching { usm.queryEvents(now - lookback, now) }.getOrNull()
        if (events != null) {
            val event = UsageEvents.Event()
            var latest: TopActivity? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = TopActivity(event.packageName, event.className, "UsageEvents")
                }
            }
            if (latest != null) return latest
        }
        
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val top = runCatching { am.getRunningTasks(1).firstOrNull()?.topActivity }.getOrNull()
        return TopActivity(top?.packageName, top?.className, "RunningTasks")
    }

    private data class TopActivity(val packageName: String?, val className: String?, val source: String) {
        val displayName: String get() = packageName ?: "unknown"
    }
}
