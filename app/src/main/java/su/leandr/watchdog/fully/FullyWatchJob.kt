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
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_ACTION
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TOP_ACTIVITY
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CHECKS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SOFT_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_OK
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SUPPRESSED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_FOREGROUND_STARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_WINDOW_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_SOFT_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_HARD_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.USAGE_EVENTS_SHORT_LOOKBACK_MS

class FullyWatchJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: -1
        
        // 1. NON-BLOCKING Chain survival: must be instant!
        if (WatchdogSettings.isEnabled(this)) {
            FullyScheduler.schedule(this, currentJobId = jobId)
        } else {
            jobFinished(params, false)
            return false
        }

        // 2. Everything else happens in a background thread
        Thread {
            try {
                performRescueCycle()
            } catch (e: Exception) {
                FileLogger.log(this, "Thread Error: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun performRescueCycle() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val availMb = memInfo.availMem / 1024 / 1024
        val target = WatchdogSettings.fullyPackage(this)
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, MODE_PRIVATE)
        
        // Deadman Switch: If the system was so stuck that we missed multiple cycles, reboot.
        val lastCheck = prefs.getLong(FullyWatchdogConfig.PREF_LAST_CHECK_MS, 0L)
        val interval = WatchdogSettings.intervalMs(this)
        val deadmanThreshold = maxOf(60000L, interval * 3)
        
        if (lastCheck > 0 && (now - lastCheck) > deadmanThreshold) {
            FileLogger.log(this, "DEADMAN: Last check was ${(now - lastCheck)/1000}s ago (threshold ${deadmanThreshold/1000}s). System is frozen. Rebooting.")
            // Update timestamp even before reboot to avoid instant reboot loop if reboot fails
            prefs.edit().putLong(FullyWatchdogConfig.PREF_LAST_CHECK_MS, now).apply()
            rebootDevice(this)
            return
        }
        
        // Mark as alive
        prefs.edit().putLong(FullyWatchdogConfig.PREF_LAST_CHECK_MS, now).apply()

        // Panic Check: System is gasping for air (threshold 200MB for TV)
        if (memInfo.lowMemory || availMb < 200) {
            FileLogger.log(this, "PANIC: System memory critical (${availMb}MB free). Forced kill of $target.")
            fastKillTarget(target)
            WatchdogSettings.increment(this, FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES)
            return
        }

        // Memory Check via fast RSS approximation
        val maxMb = WatchdogSettings.maxMemoryMb(this)
        if (maxMb > 0) {
            val proc = am.runningAppProcesses?.find { it.processName == target }
            if (proc != null) {
                val rssMb = getRssMb(proc.pid)
                if (rssMb > maxMb) {
                    FileLogger.log(this, "Memory Limit: ${rssMb}MB RSS > ${maxMb}MB. Restarting.")
                    fastKillTarget(target)
                    WatchdogSettings.increment(this, FullyWatchdogConfig.PREF_STAT_MEM_RESTARTS)
                    WatchdogSettings.increment(this, FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES)
                    return
                }
            }
        }

        // Normal activity check
        runCatching { checkAndRecoverFully() }
    }

    private fun fastKillTarget(packageName: String) {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastKillAttempts(this).filter { now - it < STORM_WINDOW_MS }
        if (attempts.size >= STORM_HARD_MAX) return

        WatchdogSettings.setLastKillAttempts(this, attempts + now)
        
        // Try various methods to stop the process, from most aggressive to least
        runCatching {
            // 1. Try pkill directly (might work if shell has enough perms or it's our own child)
            Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", packageName)).waitFor()
        }
        runCatching {
            // 2. Try ActivityManager to at least request background death
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
        }
        runCatching {
            // 3. Try "am force-stop" without su (some firmwares allow this via shell if debuggable or via certain perms)
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName)).waitFor()
        }
        
        Thread.sleep(300)
        tryStartFully(cleanStart = true)
    }

    private fun getRssMb(pid: Int): Int {
        return try {
            // Without Root, reading /proc/[pid]/statm of another process is blocked on Android 7+.
            // We fall back to getProcessMemoryInfo, which is safe to call here since we are in a background thread.
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memInfo = am.getProcessMemoryInfo(intArrayOf(pid))
            if (memInfo.isNotEmpty()) {
                memInfo[0].totalPss / 1024 // Return in MB
            } else -1
        } catch (e: Exception) { -1 }
    }

    private fun checkAndRecoverFully(): WatchdogResult {
        WatchdogSettings.increment(this, PREF_STAT_CHECKS)
        val topActivity = detectTopActivity()
        val targetPackage = WatchdogSettings.fullyPackage(this)
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val isFullyOnTop = topActivity.packageName == targetPackage
        val isSystemOnTop = FullyWatchdogConfig.SYSTEM_WHITELIST.contains(topActivity.packageName)
        val lastSoftRelaunchMs = prefs.getLong(PREF_LAST_SOFT_RELAUNCH_MS, 0L)
        val softRelaunchMs = WatchdogSettings.softRelaunchMs(this)

        val action = when {
            topActivity.packageName == packageName -> "skip: self"
            isSystemOnTop -> "skip: system"
            !isFullyOnTop -> {
                if (isFullyProcessAlive() && (now - WatchdogSettings.lastStartAttemptedMs(this) < 60000)) {
                    fastKillTarget(targetPackage)
                    "hard recovery"
                } else {
                    tryStartFully(cleanStart = !isFullyProcessAlive())
                }
            }
            softRelaunchMs > 0L && now - lastSoftRelaunchMs >= softRelaunchMs -> {
                tryStartFully(cleanStart = false)
                prefs.edit().putLong(PREF_LAST_SOFT_RELAUNCH_MS, now).apply()
                WatchdogSettings.increment(this, PREF_STAT_SOFT_RELAUNCHES)
                "soft relaunch"
            }
            else -> {
                WatchdogSettings.increment(this, PREF_STAT_OK)
                null // Use null to indicate "OK" and skip file logging
            }
        }
        
        val finalAction = action ?: "ok"
        
        if (action != null) {
            FileLogger.log(this, "Result: $finalAction | Top: ${topActivity.displayName} (${topActivity.source})")
            if (finalAction != "skip: self" && finalAction != "skip: system") {
                WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
            }
        }

        return WatchdogResult(finalAction, topActivity.displayName, topActivity.source)
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
            runCatching {
                // Secondary attempt via am start (standard shell might have enough perms for some intents)
                val comp = intent.component?.flattenToShortString() ?: targetPackage
                Runtime.getRuntime().exec(arrayOf("am", "start", "-n", comp)).waitFor()
            }

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putLong(PREF_LAST_RELAUNCH_MS, now).apply()
            if (cleanStart) WatchdogSettings.increment(this, PREF_STAT_CRASH_RESTARTS)
            "started"
        } catch (e: Exception) {
            "failed"
        }
    }

    private fun detectTopActivity(): TopActivity {
        if (WatchdogSettings.isUsageStatsAvailable(this)) {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // Optimization: check events since last check only
            val lastCheck = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(PREF_LAST_CHECK_MS, 0L)
            val lookback = if (lastCheck > 0) maxOf(USAGE_EVENTS_SHORT_LOOKBACK_MS, now - lastCheck + 1000) else USAGE_EVENTS_SHORT_LOOKBACK_MS
            
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
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val top = runCatching { am.getRunningTasks(1).firstOrNull()?.topActivity }.getOrNull()
        return TopActivity(top?.packageName, top?.className, "RunningTasks")
    }

    private data class TopActivity(val packageName: String?, val className: String?, val source: String) {
        val displayName: String get() = packageName ?: "unknown"
    }
    private data class WatchdogResult(val action: String, val topActivity: String, val topSource: String)
}
