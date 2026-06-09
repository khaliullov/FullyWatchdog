package su.leandr.watchdog.fully

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    fun log(context: Context, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] $message\n"
        
        // Also log to Logcat for convenience
        Log.d("FullyWatchdog", message)
        
        try {
            val logDir = context.getExternalFilesDir(null) ?: return
            val logFile = File(logDir, "watchdog.log")
            
            // Basic rotation
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                val oldFile = File(logDir, "watchdog.log.1")
                if (oldFile.exists()) oldFile.delete()
                logFile.renameTo(oldFile)
            }
            
            logFile.appendText(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }

    fun getLogPath(context: Context): String {
        return File(context.getExternalFilesDir(null), "watchdog.log").absolutePath
    }
}
