package su.leandr.watchdog.fully

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    
    private val executor = Executors.newSingleThreadExecutor()

    fun log(context: Context, message: String, toFile: Boolean = true) {
        val now = System.currentTimeMillis()
        
        // Log to logcat immediately
        Log.d("FullyWatchdog", message)

        if (!toFile) return

        // Offload file I/O to a background thread
        val appContext = context.applicationContext
        executor.execute {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
                val prefs = appContext.getSharedPreferences(FullyWatchdogConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val lastClear = prefs.getLong(FullyWatchdogConfig.PREF_LAST_LOG_CLEAR_MS, 0L)

                // Weekly rotation
                val shouldClearWeekly = lastClear == 0L || (now - lastClear > WEEK_MS)
                val logLine = "[$timestamp] $message\n"
                
                val logDir = appContext.getExternalFilesDir(null) ?: return@execute
                val logFile = File(logDir, "watchdog.log")
                
                if (shouldClearWeekly) {
                    if (logFile.exists()) logFile.delete()
                    prefs.edit().putLong(FullyWatchdogConfig.PREF_LAST_LOG_CLEAR_MS, now).apply()
                    logFile.appendText("[$timestamp] --- Weekly log reset ---\n")
                }

                // Basic size-based rotation
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    val oldFile = File(logDir, "watchdog.log.1")
                    if (oldFile.exists()) oldFile.delete()
                    logFile.renameTo(oldFile)
                }
                
                logFile.appendText(logLine)
            } catch (e: Exception) {
                // Can't do much if logging fails
            }
        }
    }

    fun getLogPath(context: Context): String {
        return File(context.getExternalFilesDir(null), "watchdog.log").absolutePath
    }
}
