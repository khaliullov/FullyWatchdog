package su.leandr.watchdog.fully

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREFS_NAME
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_CHECK_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_SCHEDULE_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_ACTION
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TOP_ACTIVITY
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_LAST_TOP_SOURCE
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_FULLY_PACKAGE
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_FULLY_ACTIVITY_CLASS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_WATCHDOG_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_SOFT_RELAUNCH_INTERVAL_MS
import su.leandr.watchdog.fully.FullyWatchdogConfig.DEFAULT_AUTO_CLOSE
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_REBOOT_ATTEMPTS
import su.leandr.watchdog.fully.FullyWatchdogConfig.PREF_STAT_ERRORS
import su.leandr.watchdog.fully.ui.theme.FullyWatchdogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isDebugIntent = intent?.action == "su.leandr.watchdog.fully.action.DEBUG" ||
                intent?.getBooleanExtra("debug", false) == true

        setContent {
            FullyWatchdogTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19)
                ) {
                    WatchdogApp(isDebugIntent = isDebugIntent)
                }
            }
        }
    }
}

@Composable
fun WatchdogApp(isDebugIntent: Boolean) {
    val context = LocalContext.current
    var isSplashVisible by remember { mutableStateOf(true) }
    var autoCloseBypassed by remember { mutableStateOf(false) }

    val autoCloseEnabled = WatchdogSettings.isAutoClose(context) && !isDebugIntent

    if (isSplashVisible) {
        SplashScreen(
            autoCloseEnabled = autoCloseEnabled,
            onCancelAutoClose = {
                autoCloseBypassed = true
                isSplashVisible = false
            },
            onFinished = {
                if (autoCloseEnabled && !autoCloseBypassed) {
                    FullyScheduler.schedule(context)
                    (context as? Activity)?.finish()
                } else {
                    isSplashVisible = false
                }
            }
        )
    } else {
        WatchdogScreen()
    }
}

@Composable
fun SplashScreen(
    autoCloseEnabled: Boolean,
    onCancelAutoClose: () -> Unit,
    onFinished: () -> Unit
) {
    var countdownSeconds by remember { mutableStateOf(3) }
    
    LaunchedEffect(Unit) {
        if (autoCloseEnabled) {
            while (countdownSeconds > 0) {
                delay(1000L)
                countdownSeconds--
            }
            onFinished()
        } else {
            delay(1200L)
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF090D16)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(Color(0xFF312E81).copy(alpha = 0.5f))
                    .border(2.dp, Color(0xFF6366F1), RoundedCornerShape(50.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size((60 * scale).dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0xFF6366F1).copy(alpha = 0.4f))
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Fully Watchdog",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )

            Text(
                text = "Kiosk Protection Service",
                color = Color(0xFF6366F1),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (autoCloseEnabled) {
                Text(
                    text = "Starting service in $countdownSeconds...",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onCancelAutoClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                ) {
                    Text("Cancel & Open Settings")
                }
            } else {
                CircularProgressIndicator(
                    color = Color(0xFF6366F1),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
fun WatchdogScreen() {
    val context = LocalContext.current
    // Track enabled as Compose state so the button and badge react immediately
    var enabled by remember { mutableStateOf(WatchdogSettings.isEnabled(context)) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var lastUpdateTick by remember { mutableLongStateOf(0L) }

    // Auto-refresh UI every 5 seconds to show live stats and health
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            lastUpdateTick = System.currentTimeMillis()
        }
    }

    val prefs = remember(lastUpdateTick) { context.getSharedPreferences(PREFS_NAME, ComponentActivity.MODE_PRIVATE) }
    val lastCheckMs = prefs.getLong(PREF_LAST_CHECK_MS, 0L)
    val lastScheduleMs = prefs.getLong(PREF_LAST_SCHEDULE_MS, 0L)
    val lastAction = prefs.getString(PREF_LAST_ACTION, "not checked yet").orEmpty()
    val lastTopActivity = prefs.getString(PREF_LAST_TOP_ACTIVITY, "unknown").orEmpty()
    val lastTopSource = prefs.getString(PREF_LAST_TOP_SOURCE, "unknown").orEmpty()
    val lastTriggerReason = WatchdogSettings.getTriggerReason(context)

    val intervalMs = WatchdogSettings.intervalMs(context)
    val now = System.currentTimeMillis()
    val isStale = lastCheckMs > 0 && (now - lastCheckMs > intervalMs * 2 + 10000)
    val isStalled = lastScheduleMs > 0 && (now - lastScheduleMs > intervalMs * 3)

    val lastCheck = if (lastCheckMs == 0L) {
        "never"
    } else {
        DateFormat.format("HH:mm:ss", lastCheckMs).toString()
    }

    val lastScheduled = if (lastScheduleMs == 0L) {
        "never"
    } else {
        DateFormat.format("HH:mm:ss", lastScheduleMs).toString()
    }

    var packageVal by remember { mutableStateOf(WatchdogSettings.fullyPackage(context)) }
    var activityVal by remember { mutableStateOf(WatchdogSettings.fullyActivityClass(context)) }
    var intervalVal by remember { mutableStateOf(WatchdogSettings.intervalMs(context).toString()) }
    var deadlineVal by remember { mutableStateOf(WatchdogSettings.overrideDeadlineMs(context).toString()) }
    var relaunchVal by remember { mutableStateOf(WatchdogSettings.softRelaunchMs(context).toString()) }
    var autoCloseVal by remember { mutableStateOf(WatchdogSettings.isAutoClose(context)) }

    var useDefaultPackage by remember { mutableStateOf(packageVal == DEFAULT_FULLY_PACKAGE) }
    var useDefaultActivity by remember { mutableStateOf(activityVal == DEFAULT_FULLY_ACTIVITY_CLASS) }
    var useDefaultInterval by remember { mutableStateOf(intervalVal == DEFAULT_WATCHDOG_INTERVAL_MS.toString()) }
    var useDefaultDeadline by remember { mutableStateOf(deadlineVal == DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS.toString()) }
    var useDefaultRelaunch by remember { mutableStateOf(relaunchVal == DEFAULT_SOFT_RELAUNCH_INTERVAL_MS.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Fully Watchdog Settings",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Watchdog Health", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val healthColor = when {
                            !enabled -> Color(0xFF94A3B8)
                            isStalled -> Color(0xFFF59E0B) // Orange for stalled scheduler
                            isStale -> Color(0xFFEF4444)   // Red for stale check
                            else -> Color(0xFF10B981)      // Green for healthy
                        }
                        val healthText = when {
                            !enabled -> "DISABLED"
                            isStalled -> "STALLED (Scheduler lag)"
                            isStale -> "STALE (Check missed)"
                            else -> "HEALTHY"
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(healthColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = healthText,
                            color = healthColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                
                HorizontalDivider(color = Color(0xFF1E293B))

                val isScheduled = FullyScheduler.isScheduled(context)
                DiagnosticRow("Job Status", if (isScheduled) "Scheduled" else "Idle")
                DiagnosticRow("Last Scheduled", lastScheduled)
                DiagnosticRow("Last Check", lastCheck)
                DiagnosticRow("Last Reason", lastTriggerReason)
                DiagnosticRow("Last Result", lastAction)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val nextEnabled = !enabled
                    WatchdogSettings.setEnabled(context, nextEnabled)
                    if (nextEnabled) {
                        FullyScheduler.schedule(context, delayMs = 0L)
                    } else {
                        FullyScheduler.cancel(context)
                    }
                    // Update state immediately so button and badge re-compose
                    enabled = nextEnabled
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color(0xFFEF4444) else Color(0xFF10B981)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (enabled) "Disable Watchdog" else "Enable Watchdog", maxLines = 1, textAlign = TextAlign.Center)
            }

            Button(
                onClick = { FullyScheduler.schedule(context, delayMs = 0L) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Run Check Now", maxLines = 1, textAlign = TextAlign.Center)
            }

            Button(
                onClick = { showRebootDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Restart TV", maxLines = 1, textAlign = TextAlign.Center)
            }

            Button(
                onClick = { WatchdogSettings.resetStats(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7280)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Stats", maxLines = 1, textAlign = TextAlign.Center)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Configurations",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                SettingsFieldRow(
                    label = "Target Package",
                    value = packageVal,
                    onValueChange = { packageVal = it },
                    useDefault = useDefaultPackage,
                    onUseDefaultChange = {
                        useDefaultPackage = it
                        if (it) packageVal = DEFAULT_FULLY_PACKAGE
                    },
                    defaultValue = DEFAULT_FULLY_PACKAGE
                )

                SettingsFieldRow(
                    label = "Target Activity Class",
                    value = activityVal,
                    onValueChange = { activityVal = it },
                    useDefault = useDefaultActivity,
                    onUseDefaultChange = {
                        useDefaultActivity = it
                        if (it) activityVal = DEFAULT_FULLY_ACTIVITY_CLASS
                    },
                    defaultValue = DEFAULT_FULLY_ACTIVITY_CLASS
                )

                SettingsFieldRow(
                    label = "Check Interval (ms)",
                    value = intervalVal,
                    onValueChange = { intervalVal = it },
                    useDefault = useDefaultInterval,
                    onUseDefaultChange = {
                        useDefaultInterval = it
                        if (it) intervalVal = DEFAULT_WATCHDOG_INTERVAL_MS.toString()
                    },
                    defaultValue = DEFAULT_WATCHDOG_INTERVAL_MS.toString(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                SettingsFieldRow(
                    label = "Override Deadline (ms)",
                    value = deadlineVal,
                    onValueChange = { deadlineVal = it },
                    useDefault = useDefaultDeadline,
                    onUseDefaultChange = {
                        useDefaultDeadline = it
                        if (it) deadlineVal = DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS.toString()
                    },
                    defaultValue = DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS.toString(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                SettingsFieldRow(
                    label = "Soft Relaunch Interval (ms)",
                    value = relaunchVal,
                    onValueChange = { relaunchVal = it },
                    useDefault = useDefaultRelaunch,
                    onUseDefaultChange = {
                        useDefaultRelaunch = it
                        if (it) relaunchVal = DEFAULT_SOFT_RELAUNCH_INTERVAL_MS.toString()
                    },
                    defaultValue = DEFAULT_SOFT_RELAUNCH_INTERVAL_MS.toString(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Auto-close UI on Launch", color = Color.White, fontSize = 16.sp)
                        Text("Finish activity automatically after countdown", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    Switch(
                        checked = autoCloseVal,
                        onCheckedChange = { autoCloseVal = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF6366F1),
                            checkedTrackColor = Color(0xFF312E81)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val interval = intervalVal.toLongOrNull() ?: DEFAULT_WATCHDOG_INTERVAL_MS
                            val deadline = deadlineVal.toLongOrNull() ?: DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS
                            val relaunch = relaunchVal.toLongOrNull() ?: DEFAULT_SOFT_RELAUNCH_INTERVAL_MS

                            WatchdogSettings.setConfig(
                                context = context,
                                fullyPackage = if (useDefaultPackage) DEFAULT_FULLY_PACKAGE else packageVal,
                                fullyActivityClass = if (useDefaultActivity) DEFAULT_FULLY_ACTIVITY_CLASS else activityVal,
                                intervalMs = interval,
                                overrideDeadlineMs = deadline,
                                softRelaunchMs = relaunch
                            )
                            WatchdogSettings.setAutoClose(context, autoCloseVal)

                            if (WatchdogSettings.isEnabled(context)) {
                                FullyScheduler.schedule(context, delayMs = 0L)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save & Apply")
                    }

                    Button(
                        onClick = {
                            WatchdogSettings.resetDefaults(context)
                            packageVal = DEFAULT_FULLY_PACKAGE
                            activityVal = DEFAULT_FULLY_ACTIVITY_CLASS
                            intervalVal = DEFAULT_WATCHDOG_INTERVAL_MS.toString()
                            deadlineVal = DEFAULT_WATCHDOG_OVERRIDE_DEADLINE_MS.toString()
                            relaunchVal = DEFAULT_SOFT_RELAUNCH_INTERVAL_MS.toString()
                            autoCloseVal = DEFAULT_AUTO_CLOSE

                            useDefaultPackage = true
                            useDefaultActivity = true
                            useDefaultInterval = true
                            useDefaultDeadline = true
                            useDefaultRelaunch = true

                            if (WatchdogSettings.isEnabled(context)) {
                                FullyScheduler.schedule(context, delayMs = 0L)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restore All Defaults")
                    }
                }
            }
        }

        Text("Statistics", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        val stats = WatchdogSettings.stats(context)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("Total Recoveries", stats.totalRelaunches.toString(), modifier = Modifier.weight(1f))
                StatCard("Crash Restarts", stats.crashRestarts.toString(), modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("In Focus (OK)", stats.ok.toString(), modifier = Modifier.weight(1f))
                StatCard("Soft Relaunches", stats.softRelaunches.toString(), modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("Total Checks", stats.checks.toString(), modifier = Modifier.weight(1f))
                StatCard("Starts from BG", stats.foregroundStarts.toString(), modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("Suppressed (Storm)", stats.suppressed.toString(), modifier = Modifier.weight(1f))
                StatCard("UI Skips", stats.uiSkips.toString(), modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("Errors", stats.errors.toString(), modifier = Modifier.weight(1f))
                StatCard("TV Reboot Attempts", stats.rebootAttempts.toString(), modifier = Modifier.weight(1f))
            }
        }

        Text("System Diagnostics", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticRow("WebView Version", WatchdogSettings.getWebViewVersion(context))
                DiagnosticRow(
                    "Usage Stats Permission",
                    if (WatchdogSettings.isUsageStatsAvailable(context)) "Granted" else "Not Granted",
                    if (WatchdogSettings.isUsageStatsAvailable(context)) Color(0xFF10B981) else Color(0xFFEF4444)
                )
                DiagnosticRow(
                    "Running Tasks Available",
                    if (WatchdogSettings.isRunningTasksAvailable(context)) "Yes" else "No",
                    if (WatchdogSettings.isRunningTasksAvailable(context)) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
                DiagnosticRow("Detection Method", lastTopSource)
                DiagnosticRow("Detected Top App", lastTopActivity)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Log Path: ${FileLogger.getLogPath(context)}",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text("Restart TV") },
            text = { Text("Are you sure you want to reboot the TV now?") },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootDialog = false
                        rebootDevice(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Reboot")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showRebootDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569))
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    useDefault: Boolean,
    onUseDefaultChange: (Boolean) -> Unit,
    defaultValue: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = if (useDefault) defaultValue else value,
            onValueChange = { if (!useDefault) onValueChange(it) },
            enabled = !useDefault,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF475569),
                disabledBorderColor = Color(0xFF1E293B),
                disabledTextColor = Color(0xFF94A3B8)
            ),
            keyboardOptions = keyboardOptions,
            singleLine = true
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onUseDefaultChange(!useDefault) }
        ) {
            Checkbox(
                checked = useDefault,
                onCheckedChange = onUseDefaultChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF6366F1)
                )
            )
            Text("Default", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

fun rebootDevice(context: android.content.Context) {
    WatchdogSettings.increment(context, PREF_STAT_REBOOT_ATTEMPTS)

    // Attempt 1: PowerManager.reboot() — works only if app is signed with system signature
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
    try {
        pm?.reboot(null)
        return // succeeded — nothing more to do
    } catch (_: Exception) { }

    // Attempt 2: root shell
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        process.waitFor()
        if (process.exitValue() == 0) return
    } catch (_: Exception) { }

    // Attempt 3: plain shell (some TV firmwares allow it without root)
    try {
        Runtime.getRuntime().exec("reboot")
        return
    } catch (_: Exception) { }

    // All attempts failed — guide the user
    WatchdogSettings.increment(context, PREF_STAT_ERRORS)
    val adbCmd = "adb shell reboot"
    Toast.makeText(
        context,
        "Reboot requires system privileges.\nRun via ADB:\n$adbCmd",
        Toast.LENGTH_LONG
    ).show()
}
