package su.leandr.watchdog.fully

import android.content.Context
import android.os.Build
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_ENABLED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_AUTO_CLOSE
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_AUTO_CLOSE
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_FULLY_PACKAGE
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_FULLY_PACKAGE
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_FULLY_ACTIVITY_CLASS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_FULLY_ACTIVITY_CLASS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_WATCHDOG_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_OVERRIDE_DEADLINE_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_SOFT_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_SOFT_RELAUNCH_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_START_ATTEMPTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_KILL_ATTEMPTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_START_ATTEMPTED_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TRIGGER_REASON
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CHECKS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_OK
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_FOREGROUND_STARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SOFT_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SUPPRESSED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_UI_SKIPS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_ERRORS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_REBOOT_ATTEMPTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS

object WatchdogSettings {
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun isAutoClose(context: Context): Boolean =
        prefs(context).getBoolean(PREF_AUTO_CLOSE, DEFAULT_AUTO_CLOSE)

    fun setAutoClose(context: Context, autoClose: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTO_CLOSE, autoClose).apply()
    }

    fun fullyPackage(context: Context): String =
        prefs(context).getString(PREF_FULLY_PACKAGE, DEFAULT_FULLY_PACKAGE).orEmpty()
            .ifBlank { DEFAULT_FULLY_PACKAGE }

    fun fullyActivityClass(context: Context): String =
        prefs(context).getString(PREF_FULLY_ACTIVITY_CLASS, DEFAULT_FULLY_ACTIVITY_CLASS).orEmpty()
            .ifBlank { DEFAULT_FULLY_ACTIVITY_CLASS }

    fun intervalMs(context: Context): Long =
        prefs(context).getLong(PREF_INTERVAL_MS, DEFAULT_WATCHDOG_INTERVAL_MS)

    fun overrideDeadlineMs(context: Context): Long =
        prefs(context).getLong(PREF_OVERRIDE_DEADLINE_MS, DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS)

    fun softRelaunchMs(context: Context): Long =
        prefs(context).getLong(FullyWatchdogConfig.PREF_SOFT_RELAUNCH_MS, FullyWatchdogConfig.DEFAULT_SOFT_RELAUNCH_INTERVAL_MS)

    fun maxMemoryMb(context: Context): Int =
        prefs(context).getInt(FullyWatchdogConfig.PREF_MAX_MEMORY_MB, FullyWatchdogConfig.DEFAULT_MAX_MEMORY_MB)

    fun lastStartAttempts(context: Context): List<Long> =
        prefs(context).getString(PREF_LAST_START_ATTEMPTS, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }

    fun setLastStartAttempts(context: Context, attempts: List<Long>) {
        prefs(context).edit()
            .putString(PREF_LAST_START_ATTEMPTS, attempts.joinToString(","))
            .apply()
    }

    fun lastKillAttempts(context: Context): List<Long> =
        prefs(context).getString(PREF_LAST_KILL_ATTEMPTS, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }

    fun setLastKillAttempts(context: Context, attempts: List<Long>) {
        prefs(context).edit()
            .putString(PREF_LAST_KILL_ATTEMPTS, attempts.joinToString(","))
            .apply()
    }

    fun lastStartAttemptedMs(context: Context): Long =
        prefs(context).getLong(PREF_LAST_START_ATTEMPTED_MS, 0L)

    fun setLastStartAttemptedMs(context: Context, timeMs: Long) {
        prefs(context).edit().putLong(PREF_LAST_START_ATTEMPTED_MS, timeMs).apply()
    }

    fun setTriggerReason(context: Context, reason: String) {
        prefs(context).edit().putString(PREF_LAST_TRIGGER_REASON, reason).apply()
    }

    fun getTriggerReason(context: Context): String =
        prefs(context).getString(PREF_LAST_TRIGGER_REASON, "unknown").orEmpty()

    fun setConfig(
        context: Context,
        fullyPackage: String? = null,
        fullyActivityClass: String? = null,
        intervalMs: Long? = null,
        overrideDeadlineMs: Long? = null,
        softRelaunchMs: Long? = null,
        maxMemoryMb: Int? = null
    ) {
        val editor = prefs(context).edit()
        fullyPackage?.let { editor.putString(FullyWatchdogConfig.PREF_FULLY_PACKAGE, it.trim()) }
        fullyActivityClass?.let { editor.putString(FullyWatchdogConfig.PREF_FULLY_ACTIVITY_CLASS, it.trim()) }
        intervalMs?.let { editor.putLong(FullyWatchdogConfig.PREF_INTERVAL_MS, it.coerceAtLeast(MIN_DELAY_MS)) }
        overrideDeadlineMs?.let { editor.putLong(FullyWatchdogConfig.PREF_OVERRIDE_DEADLINE_MS, it.coerceAtLeast(MIN_DELAY_MS)) }
        softRelaunchMs?.let { editor.putLong(FullyWatchdogConfig.PREF_SOFT_RELAUNCH_MS, it.coerceAtLeast(0L)) }
        maxMemoryMb?.let { editor.putInt(FullyWatchdogConfig.PREF_MAX_MEMORY_MB, it.coerceAtLeast(0)) }
        editor.apply()
    }

    fun resetDefaults(context: Context) {
        prefs(context).edit()
            .putString(FullyWatchdogConfig.PREF_FULLY_PACKAGE, FullyWatchdogConfig.DEFAULT_FULLY_PACKAGE)
            .putString(FullyWatchdogConfig.PREF_FULLY_ACTIVITY_CLASS, FullyWatchdogConfig.DEFAULT_FULLY_ACTIVITY_CLASS)
            .putLong(FullyWatchdogConfig.PREF_INTERVAL_MS, FullyWatchdogConfig.DEFAULT_WATCHDOG_INTERVAL_MS)
            .putLong(FullyWatchdogConfig.PREF_OVERRIDE_DEADLINE_MS, FullyWatchdogConfig.DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS)
            .putLong(FullyWatchdogConfig.PREF_SOFT_RELAUNCH_MS, FullyWatchdogConfig.DEFAULT_SOFT_RELAUNCH_INTERVAL_MS)
            .putInt(FullyWatchdogConfig.PREF_MAX_MEMORY_MB, FullyWatchdogConfig.DEFAULT_MAX_MEMORY_MB)
            .putBoolean(FullyWatchdogConfig.PREF_AUTO_CLOSE, FullyWatchdogConfig.DEFAULT_AUTO_CLOSE)
            .apply()
    }

    fun increment(context: Context, key: String) {
        val sharedPreferences = prefs(context)
        sharedPreferences.edit()
            .putLong(key, sharedPreferences.getLong(key, 0L) + 1L)
            .apply()
    }

    fun stats(context: Context): WatchdogStats {
        val sharedPreferences = prefs(context)
        return WatchdogStats(
            checks = sharedPreferences.getLong(PREF_STAT_CHECKS, 0L),
            ok = sharedPreferences.getLong(PREF_STAT_OK, 0L),
            foregroundStarts = sharedPreferences.getLong(PREF_STAT_FOREGROUND_STARTS, 0L),
            softRelaunches = sharedPreferences.getLong(PREF_STAT_SOFT_RELAUNCHES, 0L),
            suppressed = sharedPreferences.getLong(PREF_STAT_SUPPRESSED, 0L),
            uiSkips = sharedPreferences.getLong(PREF_STAT_UI_SKIPS, 0L),
            errors = sharedPreferences.getLong(PREF_STAT_ERRORS, 0L),
            rebootAttempts = sharedPreferences.getLong(PREF_STAT_REBOOT_ATTEMPTS, 0L),
            crashRestarts = sharedPreferences.getLong(PREF_STAT_CRASH_RESTARTS, 0L),
            totalRelaunches = sharedPreferences.getLong(FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES, 0L),
            memRestarts = sharedPreferences.getLong(FullyWatchdogConfig.PREF_STAT_MEM_RESTARTS, 0L),
            lastRelaunchMs = sharedPreferences.getLong(FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS, 0L)
        )
    }

    fun resetStats(context: Context) {
        prefs(context).edit()
            .putLong(FullyWatchdogConfig.PREF_STAT_CHECKS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_OK, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_FOREGROUND_STARTS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_SOFT_RELAUNCHES, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_MEM_RESTARTS, 0L)
            .putLong(FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_SUPPRESSED, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_UI_SKIPS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_ERRORS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_REBOOT_ATTEMPTS, 0L)
            .putLong(FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS, 0L)
            .apply()
    }

    fun getWebViewVersion(context: Context): String {
        val webViewPackages = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.amazon.webview"
        )
        for (pkg in webViewPackages) {
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                return "${info.packageName} ${info.versionName}"
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            for (p in packages) {
                if (p.packageName.contains("webview", ignoreCase = true)) {
                    return "${p.packageName} ${p.versionName}"
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "Not found"
    }

    fun isUsageStatsAvailable(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    fun isRunningTasksAvailable(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return try {
            am.getRunningTasks(1).isNotEmpty()
        } catch (e: SecurityException) {
            false
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val MIN_DELAY_MS = 1_000L
}

data class WatchdogStats(
    val checks: Long,
    val ok: Long,
    val foregroundStarts: Long,
    val softRelaunches: Long,
    val suppressed: Long,
    val uiSkips: Long,
    val errors: Long,
    val rebootAttempts: Long,
    val crashRestarts: Long,
    val totalRelaunches: Long,
    val memRestarts: Long,
    val lastRelaunchMs: Long
)
