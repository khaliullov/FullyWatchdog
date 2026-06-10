package su.leandr.watchdog.fully

import android.app.ActivityManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_SOFT_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_START_ATTEMPTED_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_CHECK_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_ACTION
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TOP_ACTIVITY
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TOP_SOURCE
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CHECKS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_UI_SKIPS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SOFT_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_OK
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SUPPRESSED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_ERRORS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_FOREGROUND_STARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_WINDOW_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_SOFT_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_HARD_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.USAGE_EVENTS_SHORT_LOOKBACK_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.USAGE_EVENTS_LONG_LOOKBACK_MS

class FullyWatchJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (!WatchdogSettings.isEnabled(this)) {
            FullyScheduler.cancelAll(this)
            jobFinished(params, false)
            return false
        }

        // Schedule NEXT job immediately to ensure continuity even if this process is killed
        FullyScheduler.schedule(this)

        Thread {
            try {
                val result = runCatching { checkAndRecoverFully() }
                    .getOrElse {
                        val errorMsg = "watchdog error: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                        FileLogger.log(this, "CRITICAL ERROR: $errorMsg")
                        WatchdogResult(
                            action = errorMsg,
                            topActivity = "unknown",
                            topSource = "error"
                        )
                    }

                FileLogger.log(this, "Final Result: ${result.action} | Top: ${result.topActivity}")
            } finally {
                jobFinished(params, false)
            }
        }.start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params?.stopReason.toString()
        } else {
            "unknown"
        }
        FileLogger.log(this, "onStopJob: reason=$reason")
        return false
    }

    private fun checkAndRecoverFully(): WatchdogResult {
        WatchdogSettings.increment(this, PREF_STAT_CHECKS)
        val topActivity = detectTopActivity()
        val targetPackage = WatchdogSettings.fullyPackage(this)
        
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // Write status early so we can see it even if we crash/kill later
        prefs.edit()
            .putLong(PREF_LAST_CHECK_MS, now)
            .putString(PREF_LAST_TOP_ACTIVITY, topActivity.displayName)
            .putString(PREF_LAST_TOP_SOURCE, topActivity.source)
            .apply()

        val isFullyOnTop = topActivity.packageName == targetPackage
        val isSelfOnTop = topActivity.packageName == packageName
        val isSystemOnTop = FullyWatchdogConfig.SYSTEM_WHITELIST.contains(topActivity.packageName)
        
        Log.d("FullyWatchdog", "Check: top=${topActivity.packageName}/${topActivity.className} source=${topActivity.source}")
        FileLogger.log(this, "Check: top=${topActivity.packageName} source=${topActivity.source}")

        val lastSoftRelaunchMs = prefs.getLong(PREF_LAST_SOFT_RELAUNCH_MS, 0L)
        val softRelaunchMs = WatchdogSettings.softRelaunchMs(this)

        val action = when {
            isSelfOnTop -> {
                WatchdogSettings.increment(this, PREF_STAT_UI_SKIPS)
                "skip: watchdog UI is foreground"
            }

            isSystemOnTop -> {
                WatchdogSettings.increment(this, PREF_STAT_UI_SKIPS)
                "skip: system app on top (${topActivity.packageName})"
            }

            !isFullyOnTop -> {
                val isProcessAlive = isFullyProcessAlive()
                Log.w("FullyWatchdog", "Fully NOT on top. isProcessAlive=$isProcessAlive")
                
                val lastStartAttemptedMs = WatchdogSettings.lastStartAttemptedMs(this)
                val wasJustAttempted = lastStartAttemptedMs > 0 && (now - lastStartAttemptedMs < WatchdogSettings.intervalMs(this) * 2)

                if (isProcessAlive && wasJustAttempted) {
                    val logMsg = "Process alive but window missing after attempt. Triggering hard kill."
                    Log.e("FullyWatchdog", logMsg)
                    FileLogger.log(this, logMsg)
                    killAndRestartFully()
                } else {
                    val logMsg = "Starting Fully (cleanStart=${!isProcessAlive})"
                    Log.i("FullyWatchdog", logMsg)
                    FileLogger.log(this, logMsg)
                    val startResult = tryStartFully(cleanStart = !isProcessAlive)
                    
                    // Reset soft relaunch timer on clean start to avoid immediate relaunch
                    if (!isProcessAlive && !startResult.contains("suppressed") && !startResult.contains("failed")) {
                        prefs.edit().putLong(PREF_LAST_SOFT_RELAUNCH_MS, now).apply()
                    }
                    startResult
                }
            }

            softRelaunchMs > 0L && now - lastSoftRelaunchMs >= softRelaunchMs -> {
                val startResult = tryStartFully(cleanStart = false)
                if (!startResult.startsWith("restart suppressed") && !startResult.startsWith("start failed")) {
                    prefs.edit().putLong(PREF_LAST_SOFT_RELAUNCH_MS, now).apply()
                    WatchdogSettings.increment(this, PREF_STAT_SOFT_RELAUNCHES)
                    "soft relaunch: $startResult"
                } else {
                    startResult
                }
            }

            else -> {
                WatchdogSettings.increment(this, PREF_STAT_OK)
                "ok: Fully is foreground"
            }
        }
        
        // Final action write
        prefs.edit().putString(PREF_LAST_ACTION, action).apply()

        return WatchdogResult(
            action = action,
            topActivity = topActivity.displayName,
            topSource = topActivity.source
        )
    }

    private fun isFullyProcessAlive(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses
            ?.any { it.processName == WatchdogSettings.fullyPackage(this) } == true
    }

    private fun killAndRestartFully(): String {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastKillAttempts(this)
            .filter { now - it < STORM_WINDOW_MS }

        if (attempts.size >= STORM_HARD_MAX) {
            WatchdogSettings.increment(this, PREF_STAT_SUPPRESSED)
            return "kill suppressed (hard storm protection)"
        }

        val newAttempts = attempts + now
        WatchdogSettings.setLastKillAttempts(this, newAttempts)

        try {
            // Use su force-stop for other processes
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "am force-stop ${WatchdogSettings.fullyPackage(this)}")
            ).waitFor()
        } catch (_: Exception) {
        }
        
        Thread.sleep(1500L)
        val result = tryStartFully(cleanStart = true)
        return "kill+restart: $result"
    }

    private fun tryStartFully(cleanStart: Boolean): String {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastStartAttempts(this)
            .filter { now - it < STORM_WINDOW_MS }

        if (attempts.size >= STORM_SOFT_MAX) {
            WatchdogSettings.increment(this, PREF_STAT_SUPPRESSED)
            return "restart suppressed (soft storm protection)"
        }

        val newAttempts = attempts + now
        WatchdogSettings.setLastStartAttempts(this, newAttempts)
        WatchdogSettings.setLastStartAttemptedMs(this, now)

        val targetPackage = WatchdogSettings.fullyPackage(this)
        val targetClass = WatchdogSettings.fullyActivityClass(this)

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        val intent = if (launchIntent != null && (targetClass.isBlank() || launchIntent.component?.className == targetClass)) {
            launchIntent
        } else {
            Intent().setComponent(ComponentName(targetPackage, targetClass))
        }

        if (intent == null) {
            WatchdogSettings.increment(this, PREF_STAT_ERRORS)
            return "start failed: no activity found for $targetPackage"
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) // Always clear task for robustness on YaOS
        if (!cleanStart) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        if (intent.action == null) intent.action = Intent.ACTION_MAIN
        if (intent.categories == null || intent.categories.isEmpty()) intent.addCategory(Intent.CATEGORY_LAUNCHER)

        return try {
            FileLogger.log(this, "Executing startActivity for ${intent.component?.flattenToShortString()}")
            startActivity(intent)
            
            // Fallback for YaOS: try am start via su to bypass background restrictions
            try {
                val comp = intent.component?.flattenToShortString() ?: "$targetPackage/$targetClass"
                Runtime.getRuntime().exec(arrayOf("su", "-c", "am start -n $comp")).waitFor()
            } catch (e: Exception) {
                // ignore root failure
            }

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putLong(PREF_LAST_RELAUNCH_MS, now).apply()
            WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)

            if (cleanStart) {
                WatchdogSettings.increment(this, PREF_STAT_CRASH_RESTARTS)
            } else {
                WatchdogSettings.increment(this, PREF_STAT_FOREGROUND_STARTS)
            }
            "start target app (${if (cleanStart) "clean" else "soft"}): top=${intent.component?.flattenToShortString() ?: targetPackage}"
        } catch (e: Exception) {
            WatchdogSettings.increment(this, PREF_STAT_ERRORS)
            "start failed: ${e.message}"
        }
    }

    private fun detectTopActivity(): TopActivity {
        if (WatchdogSettings.isUsageStatsAvailable(this)) {
            detectTopActivityWithUsageEvents(USAGE_EVENTS_SHORT_LOOKBACK_MS)?.let { return it }
            detectTopActivityWithUsageEvents(USAGE_EVENTS_LONG_LOOKBACK_MS)?.let { return it }
        }
        detectTopActivityWithRunningTasks()?.let { return it }
        return TopActivity(
            packageName = null,
            className = null,
            source = "unavailable"
        )
    }

    @Suppress("DEPRECATION")
    private fun detectTopActivityWithRunningTasks(): TopActivity? {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val componentName = runCatching {
            activityManager.getRunningTasks(1).firstOrNull()?.topActivity
        }.getOrNull() ?: return null

        return TopActivity(
            packageName = componentName.packageName,
            className = componentName.className,
            source = "ActivityManager.getRunningTasks"
        )
    }

    private fun detectTopActivityWithUsageEvents(lookbackMs: Long): TopActivity? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = runCatching {
            usageStatsManager.queryEvents(now - lookbackMs, now)
        }.getOrNull() ?: return null

        val event = UsageEvents.Event()
        var latest: TopActivity? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)

            if (isForegroundEvent) {
                latest = TopActivity(
                    packageName = event.packageName,
                    className = event.className,
                    source = "UsageStatsManager.queryEvents (${lookbackMs / 1000}s)"
                )
            }
        }

        return latest
    }

    private data class TopActivity(
        val packageName: String?,
        val className: String?,
        val source: String
    ) {
        val displayName: String
            get() = when {
                packageName.isNullOrBlank() -> "unknown"
                className.isNullOrBlank() -> packageName
                else -> "$packageName/$className"
            }
    }

    private data class WatchdogResult(
        val action: String,
        val topActivity: String,
        val topSource: String
    )

    private companion object {
    }
}
