package su.leandr.watchdog.fully

import android.app.ActivityManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_CHECK_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_SOFT_RELAUNCH_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CHECKS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_TOTAL_RELAUNCHES
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_OK
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_SUPPRESSED
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_CRASH_RESTARTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_WINDOW_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_HARD_MAX
import su.leandr.watchdog.fully.FullyWatchdogConfig.STORM_SOFT_MAX

class FullyWatchJob : JobService() {
    companion object {
        @Volatile
        private var activeJobId: Int = -1

        @Volatile
        private var cachedHasRoot: Boolean? = null
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: -1
        val reason = params?.extras?.getString(FullyWatchdogConfig.EXTRA_REASON) ?: "JOB"
        
        synchronized(FullyWatchJob) {
            if (activeJobId != -1 && activeJobId != jobId) {
                FileLogger.log(this, "!!! Job #$jobId: BLOCKED by #$activeJobId (reason=$reason) !!!", toFile = true)
                jobFinished(params, false)
                return false
            }
            activeJobId = jobId
        }

        // Immediate log to track scheduler health
        FileLogger.log(this, "--- Job Execution Start: #$jobId (reason=$reason) ---", toFile = true)

        if (!WatchdogSettings.isEnabled(this)) {
            FileLogger.log(this, "Job #$jobId: Watchdog disabled, stopping.")
            synchronized(FullyWatchJob) { if (activeJobId == jobId) activeJobId = -1 }
            jobFinished(params, false)
            return false
        }

        Thread {
            try {
                performRescueCycle(jobId, reason)
            } catch (e: Exception) {
                FileLogger.log(this, "Job #$jobId Error: ${e.message}")
            } finally {
                val shouldReschedule = synchronized(FullyWatchJob) {
                    val wasActive = (activeJobId == jobId)
                    if (wasActive) activeJobId = -1
                    wasActive // Only reschedule if we weren't stopped by system/blocked
                }
                
                if (shouldReschedule && WatchdogSettings.isEnabled(this)) {
                    FullyScheduler.schedule(this, currentJobId = jobId, reason = "JOB")
                }

                jobFinished(params, false)
            }
        }.start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: -1
        FileLogger.log(this, "!!! Job #$jobId STOPPED by system (activeJobId=$activeJobId) !!!", toFile = true)
        if (activeJobId == jobId) activeJobId = -1
        return false
    }

    private fun performRescueCycle(jobId: Int, triggerReason: String) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val usedMb = totalMb - availMb
        val memStatsStr = "[RAM: ${usedMb}MB/Free: ${availMb}MB]"

        val target = WatchdogSettings.fullyPackage(this).trim()
        
        // 1. Get PIDs first
        val pids = getAllPids(target)
        
        // 2. Get Memory usage. Aggregate across all processes (main, remote, webview)
        var targetPssMb = 0
        
        // TEST MODE: If a specific trigger is set, simulate a leak
        if (triggerReason == "DEBUG:SIMULATE_LEAK") {
            targetPssMb = WatchdogSettings.maxMemoryMb(this) + 50
            FileLogger.log(this, "[#$jobId] DEBUG: Simulating memory leak (${targetPssMb}MB)", toFile = true)
        } else if (pids.isNotEmpty()) {
            for (p in pids) {
                val pss = getPssFromAm(p)
                if (pss > 0) targetPssMb += pss
            }
        }

        // Fallback to dumpsys if AM returned nothing or as a cross-check for YaOS
        if (targetPssMb <= 0) {
            targetPssMb = getPssViaDumpsys(target, -1)
        }

        FileLogger.log(this, "[#$jobId] Stats: $memStatsStr | App Total: ${if (targetPssMb >= 0) "${targetPssMb}MB" else "N/A"} (pids=${pids.joinToString()})", toFile = true)

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        if (triggerReason == "CONTROL:KILL") {
            fastKillTarget(target, "User requested kill")
            return
        }

        val lastCheck = prefs.getLong(PREF_LAST_CHECK_MS, 0L)
        val interval = WatchdogSettings.intervalMs(this)
        val deadmanThreshold = maxOf(60000L, interval * 10) // 10 missed checks instead of 5
        val deadmanLimit = 2 * 3600_000L // 2 hours
        
        if (lastCheck > 0 && (now - lastCheck) in (deadmanThreshold + 1) until deadmanLimit) {
            FileLogger.log(this, "[#$jobId] DEADMAN: Frozen for ${(now - lastCheck) / 1000}s. Cleaning up.", toFile = true)
            prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply()
            
            am.runningAppProcesses?.forEach {
                if (it.processName != packageName && it.processName != target) {
                    runCatching { am.killBackgroundProcesses(it.processName) }
                }
            }
            fastKillTarget(target, "Deadman rescue")
            return
        }
        
        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply()

        if (memInfo.lowMemory || availMb < 200) {
            FileLogger.log(this, "[#$jobId] PANIC: Low memory $memStatsStr. Killing $target.")
            fastKillTarget(target, "Low memory panic")
            WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
            return
        }

        val maxMb = WatchdogSettings.maxMemoryMb(this)
        if (maxMb in 1 until targetPssMb) {
            FileLogger.log(this, "[#$jobId] Memory Limit: ${targetPssMb}MB > ${maxMb}MB. Restarting.")
            fastKillTarget(target, "Memory limit exceeded")
            WatchdogSettings.increment(this, FullyWatchdogConfig.PREF_STAT_MEM_RESTARTS)
            WatchdogSettings.increment(this, PREF_STAT_TOTAL_RELAUNCHES)
            return
        }

        runCatching { checkAndRecoverFully(jobId, triggerReason, memStatsStr) }
    }

    private fun checkAndRecoverFully(jobId: Int, triggerReason: String, memStatsStr: String) {
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
            triggerReason == "USER_RESTART_RESCUE" -> {
                fastKillTarget(targetPackage, "User rescue request")
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
                    resultDetail = "ok $memStatsStr (soft relaunch deferred)"
                    null
                } else {
                    resultDetail = "ok $memStatsStr"
                    null
                }
            }
            else -> {
                if (isFullyProcessAlive() && (now - WatchdogSettings.lastStartAttemptedMs(this) < 60000)) {
                    fastKillTarget(targetPackage, "Hard recovery (loop protection)")
                    resultDetail = "hard recovery"
                    "hard recovery"
                } else {
                    val clean = !isFullyProcessAlive()
                    val startReason = if (clean) "Process not running" else "App not in foreground (top: $currentTop)"
                    val res = tryStartFully(cleanStart = clean, reason = startReason)
                    resultDetail = "$res ($startReason)"
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

    private fun fastKillTarget(packageName: String, reason: String) {
        val now = System.currentTimeMillis()
        val attempts = WatchdogSettings.lastKillAttempts(this).filter { now - it < STORM_WINDOW_MS }
        if (attempts.size >= STORM_HARD_MAX) {
            FileLogger.log(this, "Kill suppressed: storm protection", toFile = true)
            return
        }
        WatchdogSettings.setLastKillAttempts(this, attempts + now)
        
        val isRoot = checkRoot()
        FileLogger.log(this, "Executing hard kill for $packageName (Reason: $reason, Root: $isRoot)...", toFile = true)
        
        // 1. Try standard AM/CMD via regular shell
        val pids = getAllPids(packageName)
        if (pids.isNotEmpty()) {
            pids.forEach { pid ->
                executeShell("kill -9 $pid", root = isRoot)
            }
        }
        
        executeShell("am force-stop $packageName", root = isRoot)
        executeShell("am crash $packageName", root = isRoot)
        
        // 2. Fallback to official API
        runCatching { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(packageName) }
        
        // 3. ADB Fallback (Local ADB Shell)
        if (!isRoot && isFullyProcessAlive()) {
            FileLogger.log(this, "Standard kill failed or incomplete, trying Local ADB Shell...", toFile = true)
            executeAdbCommand("shell:am force-stop $packageName")
            executeAdbCommand("shell:am crash $packageName")
        }
        
        Thread.sleep(2000) 
        val stillAlive = isFullyProcessAlive(lookbackMs = 2000) // Tight window for verification
        FileLogger.log(this, "Kill verification: stillAlive=$stillAlive", toFile = true)
        
        // Always attempt to restart after a kill
        tryStartFully(cleanStart = true, reason = "Post-kill restart ($reason)")
    }

    private fun getPssFromAm(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memInfo = am.getProcessMemoryInfo(intArrayOf(pid))
            if (memInfo.isNotEmpty()) {
                val pss = memInfo[0].totalPss
                if (pss > 0) return pss / 1024
                
                val sumPss = memInfo[0].dalvikPss + memInfo[0].nativePss + memInfo[0].otherPss
                if (sumPss > 0) return sumPss / 1024
            }
            -1
        } catch (e: Exception) { -1 }
    }

    private fun getPssViaDumpsys(packageName: String, pid: Int = -1): Int {
        val query = if (pid > 0) pid.toString() else packageName
        
        fun parsePss(output: String?): Int {
            if (output.isNullOrBlank()) return -1
            var pss = -1
            output.lines().forEach { line ->
                val l = line.trim()
                // Try "TOTAL PSS: 1234" or "TOTAL: 1234"
                if (l.contains("TOTAL PSS:", ignoreCase = true) || l.contains("TOTAL:", ignoreCase = true)) {
                    val pPart = l.substringAfter(":").trim().split(Regex("\\s+"))[0].replace(",", "").toIntOrNull()
                    if (pPart != null && pPart > 0) pss = pPart
                }
                // Fallback to "TOTAL  1234  5678 ..."
                if (pss <= 0 && l.startsWith("TOTAL") && l.split(Regex("\\s+")).size > 1) {
                    val pPart = l.substringAfter("TOTAL").trim().split(Regex("\\s+"))[0].replace(",", "").toIntOrNull()
                    if (pPart != null && pPart > 0) pss = pPart
                }
            }
            return if (pss > 0) pss / 1024 else -1
        }

        // 1. Try regular shell (fastest)
        var result = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys meminfo $query"))
            parsePss(p.inputStream.bufferedReader().readText())
        } catch (e: Exception) { -1 }

        // 2. Try Local ADB (bypasses restrictions on Android 11+)
        if (result <= 0) {
            val adbOutput = executeAdbCommand("shell:dumpsys meminfo $query")
            result = parsePss(adbOutput)
        }
        
        return result
    }

    private fun isFullyProcessAlive(lookbackMs: Long = 30000): Boolean {
        val target = WatchdogSettings.fullyPackage(this)
        if (getAllPids(target).isNotEmpty()) return true
        
        // Android 11 Visibility Fallback: Check UsageStats as a process heart-beat
        if (lookbackMs <= 0) return false
        
        return try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - lookbackMs, now)
            val event = UsageEvents.Event()
            var found = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == target) {
                    found = true
                    break
                }
            }
            found
        } catch (e: Exception) { false }
    }

    private fun getAllPids(packageName: String): Set<Int> {
        val pids = mutableSetOf<Int>()

        // 1. ActivityManager (fastest, but restricted on 11+)
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.forEach { 
                if (it.processName == packageName || it.processName.startsWith("$packageName:")) {
                    pids.add(it.pid)
                }
            }
        } catch (e: Exception) {}

        // 2. cmd activity dump processes
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd activity dump processes $packageName"))
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.contains("PID #")) {
                        val pid = line.substringAfter("PID #").trim().split(Regex("[:\\s]"))[0].toIntOrNull()
                        if (pid != null && pid > 0) pids.add(pid)
                    }
                }
            }
        }

        // 3. ps -A
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.contains(packageName) && !line.contains(this.packageName)) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size > 1) {
                            val pid = parts[1].toIntOrNull()
                            if (pid != null && pid > 1) pids.add(pid)
                        }
                    }
                }
            }
        }

        // 4. Fallback via Local ADB (if enabled)
        executeAdbCommand("shell:pidof $packageName")?.trim()?.split(Regex("\\s+"))?.forEach {
            it.toIntOrNull()?.let { pid -> if (pid > 0) pids.add(pid) }
        }

        return pids
    }

    private fun checkRoot(): Boolean {
        if (cachedHasRoot == null) {
            // Silently check for root without logging an error if su is missing
            cachedHasRoot = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                p.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
        return cachedHasRoot == true
    }

    private fun executeShell(command: String, root: Boolean = false): Int {
        return try {
            val p = if (root) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            val exitCode = p.waitFor()
            
            if (exitCode != 0) {
                val err = p.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
                if (err.isNotEmpty()) FileLogger.log(this, "Shell Err ($exitCode): $err for $command")
            }

            if (exitCode != 0 && command.startsWith("am ")) {
                val fallbackCommand = if (command.startsWith("am ")) command.replaceFirst("am ", "cmd activity ") else command
                if (fallbackCommand != command) {
                    FileLogger.log(this, "Shell: 'am' failed ($exitCode), trying fallback: $fallbackCommand")
                    
                    val secondP = if (root) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", fallbackCommand))
                    } else {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", fallbackCommand))
                    }
                    val secondCode = secondP.waitFor()
                    if (secondCode == 0) return 0
                }
                
                // If standard fallbacks failed, try Local ADB
                FileLogger.log(this, "Standard shell failed ($exitCode), attempting via Local ADB...")
                val adbRes = executeAdbCommand("shell:$command")
                return if (adbRes != null) 0 else exitCode
            }
            
            exitCode
        } catch (e: Exception) { 
            if (!root || cachedHasRoot == true) {
                FileLogger.log(this, "Shell Error: ${e.message} for command: $command")
            }
            -1 
        }
    }

    private fun executeAdbCommand(adbCommand: String): String? {
        val result = StringBuilder()
        try {
            java.net.Socket().use { socket ->
                socket.soTimeout = 10000 // Increased to 10s for slow TV systems
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 5000)
                val out = socket.getOutputStream()
                val ins = socket.getInputStream()

                // 1. Connect (CNXN)
                out.write(adbMessage("CNXN", 0x01000000, 4096, "host::\u0000"))
                out.flush()
                
                val header = ByteArray(24)
                readFully(ins, header)
                val cmd = String(header, 0, 4)
                val pLen = java.nio.ByteBuffer.wrap(header, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                
                if (cmd == "AUTH") {
                    FileLogger.log(this, "ADB Local: AUTH_REQUIRED. Please check 'Always allow' dialog on screen.", toFile = true)
                    return null
                }
                if (cmd != "CNXN") {
                    FileLogger.log(this, "ADB Local: Unexpected CNXN response: $cmd", toFile = true)
                    return null
                }
                
                // Consume CNXN payload (important to keep stream in sync)
                if (pLen > 0) {
                    val p = ByteArray(pLen)
                    readFully(ins, p)
                }

                // 2. Open Stream (OPEN)
                val myLocalId = (1..65535).random()
                out.write(adbMessage("OPEN", myLocalId, 0, "$adbCommand\u0000"))
                out.flush()
                
                // 3. Response Loop
                var deviceLocalId = 0
                while (true) {
                    val head = ByteArray(24)
                    readFully(ins, head)
                    val rCmd = String(head, 0, 4)
                    val arg0 = java.nio.ByteBuffer.wrap(head, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    val payLen = java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

                    when (rCmd) {
                        "OKAY" -> {
                            deviceLocalId = arg0 // Device's local ID for this stream
                        }
                        "WRTE" -> {
                            val payload = ByteArray(payLen)
                            readFully(ins, payload)
                            result.append(String(payload, Charsets.UTF_8))
                            // Acknowledge WRTE
                            out.write(adbMessage("OKAY", myLocalId, deviceLocalId, ""))
                            out.flush()
                        }
                        "CLSE" -> {
                            // Send CLSE back if we haven't already to properly close the stream
                            return result.toString().trim()
                        }
                        "FAIL" -> {
                            val payload = ByteArray(payLen)
                            readFully(ins, payload)
                            FileLogger.log(this, "ADB Local: FAIL: ${String(payload, Charsets.UTF_8)}")
                            return null
                        }
                        else -> {
                            if (payLen > 0) readFully(ins, ByteArray(payLen))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.log(this, "ADB Connection Error: ${e.message}")
        }
        return null
    }

    private fun readFully(ins: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = ins.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.IOException("ADB: Stream closed prematurely")
            offset += read
        }
    }

    private fun adbMessage(command: String, arg0: Int, arg1: Int, data: String): ByteArray {
        val cmd = command.toByteArray()
        val payload = data.toByteArray()
        val msg = java.nio.ByteBuffer.allocate(24 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        msg.put(cmd)
        msg.putInt(arg0)
        msg.putInt(arg1)
        msg.putInt(payload.size)
        
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        msg.putInt(checksum)
        
        val magic = ByteArray(4) { cmd[it].toInt().inv().toByte() }
        msg.put(magic)
        msg.put(payload)
        return msg.array()
    }

    private fun tryStartFully(cleanStart: Boolean, reason: String): String {
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
        
        var startedViaActivity = false
        try {
            startActivity(intent)
            startedViaActivity = true
        } catch (e: Exception) {
            FileLogger.log(this, "startActivity failed: ${e.message}", toFile = true)
        }
            
        val isRoot = checkRoot()
        val comp = intent.component?.flattenToShortString() ?: targetPackage
        val shellResult = executeShell("am start -n $comp", root = isRoot)
        
        FileLogger.log(this, "Start attempt: reason=$reason, shell_result=$shellResult, activity_success=$startedViaActivity, root=$isRoot", toFile = true)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putLong(PREF_LAST_RELAUNCH_MS, now).apply()
        if (cleanStart) WatchdogSettings.increment(this, PREF_STAT_CRASH_RESTARTS)
        
        return if (startedViaActivity || shellResult == 0) "started" else "failed"
    }

    private fun detectTopActivity(): TopActivity {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Use 24h lookback to ensure we find the last foreground transition, 
        // preventing the "5-minute fallback" to unreliable RunningTasks.
        val lookback = FullyWatchdogConfig.USAGE_EVENTS_LONG_LOOKBACK_MS
        
        val events = runCatching { usm.queryEvents(now - lookback, now) }.getOrNull()
        if (events != null) {
            val event = UsageEvents.Event()
            var latest: TopActivity? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latest = TopActivity(event.packageName, event.className, "UsageEvents")
                }
            }
            if (latest != null) return latest
        }

        // Fallback 2: UsageStats (Last Time Used). This is more persistent than Events buffer.
        val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - lookback, now) }.getOrNull()
        if (!stats.isNullOrEmpty()) {
            val latest = stats.filter { it.lastTimeUsed > 0 }.maxByOrNull { it.lastTimeUsed }
            if (latest != null) {
                return TopActivity(latest.packageName, null, "UsageStats")
            }
        }
        
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val top = runCatching { am.getRunningTasks(1).firstOrNull()?.topActivity }.getOrNull()
        return TopActivity(top?.packageName, top?.className, "RunningTasks")
    }

    private data class TopActivity(val packageName: String?, val className: String?, val source: String) {
        val displayName: String get() = packageName ?: "unknown"
    }
}
