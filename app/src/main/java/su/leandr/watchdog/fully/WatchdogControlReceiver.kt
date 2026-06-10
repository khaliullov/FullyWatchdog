package su.leandr.watchdog.fully

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_DISABLE
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_ENABLE
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_PUT_SYSTEM_SETTING
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_RUN_NOW
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_SET_CONFIG
import su.leandr.watchdog.fully.FullyWatchdogConfig.ACTION_TOGGLE
import su.leandr.watchdog.fully.FullyWatchdogConfig.CONTROL_TOKEN
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_OVERRIDE_DEADLINE_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_SOFT_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_SETTING_KEY
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_SETTING_VALUE
import su.leandr.watchdog.fully.FullyWatchdogConfig.EXTRA_TOKEN
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_ACTION

class WatchdogControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(EXTRA_TOKEN) != CONTROL_TOKEN) {
            saveAction(context, "control denied: bad token")
            return
        }

        val action = when (intent.action) {
            ACTION_ENABLE -> {
                WatchdogSettings.setEnabled(context, true)
                FullyScheduler.schedule(context, delayMs = 0L, reason = "CONTROL:ENABLE")
                "control: watchdog enabled"
            }

            ACTION_DISABLE -> {
                WatchdogSettings.setEnabled(context, false)
                FullyScheduler.cancelAll(context)
                "control: watchdog disabled"
            }

            ACTION_TOGGLE -> {
                val enabled = !WatchdogSettings.isEnabled(context)
                WatchdogSettings.setEnabled(context, enabled)
                if (enabled) {
                    FullyScheduler.schedule(context, delayMs = 0L, reason = "CONTROL:TOGGLE")
                    "control: watchdog toggled on"
                } else {
                    FullyScheduler.cancelAll(context)
                    "control: watchdog toggled off"
                }
            }

            ACTION_RUN_NOW -> {
                FullyScheduler.schedule(context, delayMs = 0L, reason = "CONTROL:RUN_NOW")
                "control: watchdog run requested"
            }

            ACTION_SET_CONFIG -> {
                WatchdogSettings.setConfig(
                    context = context,
                    intervalMs = intent.optionalLongExtra(EXTRA_INTERVAL_MS),
                    overrideDeadlineMs = intent.optionalLongExtra(EXTRA_OVERRIDE_DEADLINE_MS),
                    softRelaunchMs = intent.optionalLongExtra(EXTRA_SOFT_RELAUNCH_MS)
                )
                if (WatchdogSettings.isEnabled(context)) {
                    FullyScheduler.schedule(context, delayMs = 0L, reason = "CONTROL:CONFIG")
                }
                "control: config updated interval=${WatchdogSettings.intervalMs(context)}ms deadline=${WatchdogSettings.overrideDeadlineMs(context)}ms softRelaunch=${WatchdogSettings.softRelaunchMs(context)}ms"
            }

            ACTION_PUT_SYSTEM_SETTING -> putSystemSetting(context, intent)

            else -> "control ignored: unknown action=${intent.action.orEmpty()}"
        }

        saveAction(context, action)
    }

    private fun Intent.optionalLongExtra(name: String): Long? =
        if (hasExtra(name)) getLongExtra(name, 0L) else null

    private fun putSystemSetting(context: Context, intent: Intent): String {
        if (!Settings.System.canWrite(context)) {
            return "control denied: WRITE_SETTINGS is not granted"
        }

        val key = intent.getStringExtra(EXTRA_SETTING_KEY).orEmpty()
        val value = intent.getStringExtra(EXTRA_SETTING_VALUE).orEmpty()
        if (key.isBlank()) {
            return "control ignored: empty system setting key"
        }

        val ok = Settings.System.putString(context.contentResolver, key, value)
        return if (ok) {
            "control: Settings.System $key=$value"
        } else {
            "control failed: Settings.System $key"
        }
    }

    private fun saveAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_ACTION, action)
            .apply()
    }
}
