package su.leandr.watchdog.fully

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FileLogger.log(context, "BootReceiver: action=${intent.action} data=${intent.data}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                FullyScheduler.schedule(context, reason = "BOOT_COMPLETED")
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                FullyScheduler.schedule(context, reason = "LOCKED_BOOT")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                FullyScheduler.schedule(context, reason = "PACKAGE_SELF_UPDATE")
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = intent.data?.schemeSpecificPart
                val targetPkg = WatchdogSettings.fullyPackage(context)
                if (pkg == targetPkg) {
                    FullyScheduler.schedule(context, delayMs = 0L, reason = "PACKAGE_FULLY_UPDATE")
                }
            }
        }
    }
}
