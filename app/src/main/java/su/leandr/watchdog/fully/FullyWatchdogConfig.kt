package su.leandr.watchdog.fully

object FullyWatchdogConfig {
    const val DEFAULT_FULLY_PACKAGE = "de.ozerov.fully"
    const val DEFAULT_FULLY_ACTIVITY_CLASS = "de.ozerov.fully.FullyActivity"
    const val JOB_ID_A = 1001
    const val JOB_ID_B = 1002
    const val CONTROL_TOKEN = "fully-watchdog-2580"

    const val ACTION_ENABLE = "su.leandr.watchdog.fully.action.ENABLE"
    const val ACTION_DISABLE = "su.leandr.watchdog.fully.action.DISABLE"
    const val ACTION_TOGGLE = "su.leandr.watchdog.fully.action.TOGGLE"
    const val ACTION_RUN_NOW = "su.leandr.watchdog.fully.action.RUN_NOW"
    const val ACTION_SET_CONFIG = "su.leandr.watchdog.fully.action.SET_CONFIG"
    const val ACTION_PUT_SYSTEM_SETTING = "su.leandr.watchdog.fully.action.PUT_SYSTEM_SETTING"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_FULLY_PACKAGE = "fully_package"
    const val EXTRA_FULLY_ACTIVITY_CLASS = "fully_activity_class"
    const val EXTRA_INTERVAL_MS = "interval_ms"
    const val EXTRA_OVERRIDE_DEADLINE_MS = "override_deadline_ms"
    const val EXTRA_SOFT_RELAUNCH_MS = "soft_relaunch_ms"
    const val EXTRA_MAX_MEMORY_MB = "max_memory_mb"
    const val EXTRA_SETTING_KEY = "setting_key"
    const val EXTRA_SETTING_VALUE = "setting_value"

    const val DEFAULT_WATCHDOG_INTERVAL_MS = 30 * 1000L
    const val DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS = 60 * 1000L
    const val MAX_JITTER_MS = 5000L
    const val STALE_THRESHOLD_PADDING_MS = 10000L

    // 4 hours: long enough to not disturb users, short enough to preempt daily WebView leak
    const val DEFAULT_SOFT_RELAUNCH_INTERVAL_MS = 4 * 60 * 60 * 1000L
    const val DEFAULT_MAX_MEMORY_MB = 250 // RESCUE: 250MB to prevent total system freeze

    // Storm protection — two tiers
    const val STORM_WINDOW_MS = 5 * 60 * 1000L
    const val STORM_SOFT_MAX = 20      // Increased to allow more attempts for aggressive launchers
    const val STORM_HARD_MAX = 5       // Increased

    // Keep old name as alias so nothing breaks if referenced elsewhere
    const val START_STORM_WINDOW_MS = STORM_WINDOW_MS
    const val START_STORM_MAX_ATTEMPTS = STORM_SOFT_MAX

    // UsageEvents lookback windows
    const val USAGE_EVENTS_SHORT_LOOKBACK_MS = 60_000L          // primary: last 60 s
    const val USAGE_EVENTS_LONG_LOOKBACK_MS = 24 * 60 * 60 * 1000L // fallback: last 24h

    const val PREFS_NAME = "fully_watchdog"
    const val PREF_ENABLED = "enabled"
    const val PREF_AUTO_CLOSE = "auto_close"
    const val DEFAULT_AUTO_CLOSE = true
    const val PREF_FULLY_PACKAGE = "fully_package"
    const val PREF_FULLY_ACTIVITY_CLASS = "fully_activity_class"
    const val PREF_INTERVAL_MS = "interval_ms"
    const val PREF_OVERRIDE_DEADLINE_MS = "override_deadline_ms"
    const val PREF_SOFT_RELAUNCH_MS = "soft_relaunch_ms"
    const val PREF_MAX_MEMORY_MB = "max_memory_mb"
    const val PREF_LAST_START_ATTEMPTS = "last_start_attempts"
    const val PREF_LAST_KILL_ATTEMPTS = "last_kill_attempts"        // NEW: hard-kill storm counter
    const val PREF_LAST_START_ATTEMPTED_MS = "last_start_attempted_ms" // NEW: for repeat-fail detection
    const val PREF_LAST_SCHEDULE_MS = "last_schedule_ms"           // NEW: for JobScheduler health monitoring
    const val PREF_LAST_CHECK_MS = "last_check_ms"
    const val PREF_LAST_ACTION = "last_action"
    const val PREF_LAST_TRIGGER_REASON = "last_trigger_reason"      // NEW: Reason for the last job start
    const val PREF_LAST_TOP_ACTIVITY = "last_top_activity"
    const val PREF_LAST_TOP_SOURCE = "last_top_source"
    const val PREF_LAST_SOFT_RELAUNCH_MS = "last_soft_relaunch_ms"
    const val PREF_LAST_RELAUNCH_MS = "last_relaunch_ms"            // NEW: Timestamp of last recovery
    const val PREF_STAT_CHECKS = "stat_checks"
    const val PREF_STAT_OK = "stat_ok"
    const val PREF_STAT_FOREGROUND_STARTS = "stat_foreground_starts"
    const val PREF_STAT_SOFT_RELAUNCHES = "stat_soft_relaunches"
    const val PREF_STAT_TOTAL_RELAUNCHES = "stat_total_relaunches" // NEW: Total recoveries
    const val PREF_STAT_SUPPRESSED = "stat_suppressed"
    const val PREF_STAT_UI_SKIPS = "stat_ui_skips"
    const val PREF_STAT_ERRORS = "stat_errors"
    const val PREF_STAT_REBOOT_ATTEMPTS = "stat_reboot_attempts"
    const val PREF_STAT_CRASH_RESTARTS = "stat_crash_restarts"     // NEW: cold restarts after crash
    const val PREF_STAT_MEM_RESTARTS = "stat_mem_restarts"         // NEW: restarts due to memory leak
    const val PREF_LAST_LOG_CLEAR_MS = "last_log_clear_ms"         // NEW: weekly log rotation timestamp

    // YaOS / Android TV system packages that shouldn't be interrupted by watchdog
    val SYSTEM_WHITELIST = setOf(
        "com.android.settings",
        "com.android.systemui",
        // "com.spocky.projengmenu", // Projector engineering menu
        // "com.yandex.tv.launcher", // Yandex home
        "com.yandex.tv.setupwizard",
        "com.yandex.ott" // Yandex.TV app sometimes acts as home
    )
}
