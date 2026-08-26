package com.javis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javis.assistant.ConversationTurn
import com.javis.ui.JavisUiState
import com.javis.ui.JavisViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: JavisViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op; ShowNotificationTool re-checks permission itself before posting */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            JavisTheme {
                JavisApp(
                    viewModel = viewModel,
                    onRequestMic = { requestMicAndListen() }
                )
            }
        }
    }

    private fun requestMicAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOnlineStatus()
    }
}

// ---------- Theme ----------

private val JavisCyan = Color(0xFF2FD8FF)
private val JavisBg = Color(0xFF050A12)
private val JavisPanel = Color(0xFF0A1420)
private val JavisTextDim = Color(0xFF6F93AB)
private val JavisGreen = Color(0xFF33E08A)
private val JavisRed = Color(0xFFFF6B6B)

@Composable
fun JavisTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = JavisCyan,
        background = JavisBg,
        surface = JavisPanel,
        onBackground = Color(0xFFE8F6FF),
        onSurface = Color(0xFFE8F6FF),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ---------- Real device stats (no fabricated numbers) ----------

private data class DeviceStats(
    val batteryPercent: Int?,
    val storageUsedPercent: Int?,
    val isOnline: Boolean
)

private fun readDeviceStats(context: Context, isOnline: Boolean): DeviceStats {
    val battery = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (e: Exception) { null }

    val storage = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        if (total > 0) (((total - free) * 100) / total).toInt() else null
    } catch (e: Exception) { null }

    return DeviceStats(battery, storage, isOnline)
}

// ---------- Top-level screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavisApp(viewModel: JavisViewModel, onRequestMic: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var deviceStats by remember { mutableStateOf(readDeviceStats(context, state.isOnline)) }

    // Refresh real device stats periodically — no fake/simulated numbers.
    LaunchedEffect(state.isOnline) {
        while (true) {
            deviceStats = readDeviceStats(context, state.isOnline)
            delay(15_000)
        }
    }

    Scaffold(containerColor = JavisBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            HeaderBar(state, onSettingsClick = { showSettings = true })
            Spacer(Modifier.height(20.dp))

            AiCoreVisual(isThinking = state.isThinking, isListening = state.isListening)
            Spacer(Modifier.height(8.dp))
            StatusIndicator(state)
            Spacer(Modifier.height(16.dp))

            DeviceStatusCard(deviceStats)
            Spacer(Modifier.height(12.dp))

            QuickToolsRow(onToolTap = { command -> viewModel.sendText(command) })
            Spacer(Modifier.height(12.dp))

            Text("JAVIS CHAT", color = JavisCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            ConversationList(
                messages = state.messages,
                modifier = Modifier.weight(1f)
            )

            if (state.lastError != null) {
                Text(
                    state.lastError ?: "",
                    color = JavisRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            InputRow(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendText(inputText)
                    inputText = ""
                },
                isListening = state.isListening,
                onMicClick = onRequestMic
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap the mic to talk, or type a command",
                color = JavisTextDim,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    state.pendingConfirmation?.let { pending ->
        ConfirmationDialog(
            message = pending.summary,
            onConfirm = { viewModel.confirmPendingAction() },
            onCancel = { viewModel.cancelPendingAction() }
        )
    }

    if (showSettings) {
        SettingsDialog(
            hasApiKey = state.hasApiKey,
            onSaveGroq = { key ->
                viewModel.saveGroqKey(key)
                showSettings = false
            },
            onClearGroq = { viewModel.clearGroqKey() },
            onSaveGemini = { key ->
                viewModel.saveGeminiKey(key)
                showSettings = false
            },
            onClearGemini = { viewModel.clearGeminiKey() },
            onSaveAnthropic = { key ->
                viewModel.saveApiKey(key)
                showSettings = false
            },
            onClearAnthropic = { viewModel.clearApiKey() },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun HeaderBar(state: JavisUiState, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("JAVIS", color = JavisCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your AI Assistant", color = JavisTextDim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isOnline) JavisGreen else JavisRed)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.isOnline) "ONLINE" else "OFFLINE",
                color = if (state.isOnline) JavisGreen else JavisRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onSettingsClick) {
                Text("⚙", color = JavisTextDim, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun AiCoreVisual(isThinking: Boolean, isListening: Boolean) {
    val ringColor = when {
        isThinking -> JavisCyan
        isListening -> JavisGreen
        else -> JavisCyan.copy(alpha = 0.6f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .border(1.dp, ringColor.copy(alpha = 0.4f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(JavisPanel)
                .border(2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("JAVIS", color = ringColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("AI CORE", color = JavisTextDim, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun StatusIndicator(state: JavisUiState) {
    val label = when {
        state.isThinking -> "Thinking..."
        state.isListening -> "Listening..."
        else -> state.statusText
    }
    Text(
        label,
        color = JavisCyan,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun DeviceStatusCard(stats: DeviceStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(JavisPanel)
            .border(1.dp, Color(0xFF163049), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text("DEVICE STATUS", color = JavisCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        StatRow("Battery", stats.batteryPercent?.let { "$it%" } ?: "—")
        StatRow("Storage used", stats.storageUsedPercent?.let { "$it%" } ?: "—")
        StatRow("Network", if (stats.isOnline) "Connected" else "Offline")
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = JavisTextDim, fontSize = 13.sp)
        Text(value, color = Color(0xFFE8F6FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private data class QuickTool(val label: String, val command: String)

private val QUICK_TOOLS = listOf(
    QuickTool("Time", "what time is it"),
    QuickTool("Calculator", "calculate "),
    QuickTool("Note", "read my note"),
    QuickTool("Wi-Fi", "open wifi settings"),
)

@Composable
private fun QuickToolsRow(onToolTap: (String) -> Unit) {
    Column {
        Text("QUICK TOOLS", color = JavisCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUICK_TOOLS.forEach { tool ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(JavisPanel)
                        .border(1.dp, Color(0xFF163049), RoundedCornerShape(8.dp))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.TextButton(onClick = { onToolTap(tool.command) }) {
                        Text(tool.label, color = JavisTextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationList(messages: List<ConversationTurn>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages) { turn -> MessageBubble(turn) }
    }
}

@Composable
private fun MessageBubble(turn: ConversationTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isUser) JavisCyan.copy(alpha = 0.08f) else JavisCyan.copy(alpha = 0.04f))
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Text(
                text = (if (isUser) "You: " else "JAVIS: ") + turn.text,
                color = Color(0xFFE8F6FF),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun InputRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isListening: Boolean,
    onMicClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command...", color = JavisTextDim) },
            singleLine = true,
            shape = RoundedCornerShape(30.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JavisCyan,
                unfocusedBorderColor = Color(0xFF163049),
                focusedTextColor = Color(0xFFE8F6FF),
                unfocusedTextColor = Color(0xFFE8F6FF),
            )
        )
        Spacer(Modifier.width(8.dp))
        IconButtonCircle(onClick = onSend, background = JavisCyan) {
            Text("➤", color = Color(0xFF041016))
        }
        Spacer(Modifier.width(8.dp))
        IconButtonCircle(
            onClick = onMicClick,
            background = if (isListening) JavisCyan else JavisPanel
        ) {
            Text("🎤", fontSize = 16.sp)
        }
    }
}

@Composable
private fun IconButtonCircle(onClick: () -> Unit, background: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.IconButton(onClick = onClick) { content() }
    }
}
@Composable
private fun ConfirmationDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("JAVIS wants to perform this action") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm", color = JavisCyan) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        containerColor = JavisPanel,
        titleContentColor = Color(0xFFE8F6FF),
        textContentColor = Color(0xFFE8F6FF),
    )
}

@Composable
private fun SettingsDialog(
    hasApiKey: Boolean,
    onSaveGroq: (String) -> Unit,
    onClearGroq: () -> Unit,
    onSaveGemini: (String) -> Unit,
    onClearGemini: () -> Unit,
    onSaveAnthropic: (String) -> Unit,
    onClearAnthropic: () -> Unit,
    onDismiss: () -> Unit
) {
    var groqInput by remember { mutableStateOf("") }
    var geminiInput by remember { mutableStateOf("") }
    var anthropicInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("JAVIS Settings") },
        text = {
            Column {
                Text(
                    "Groq (free, no card needed)",
                    color = JavisGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Get a free key at console.groq.com",
                    color = JavisTextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = groqInput,
                    onValueChange = { groqInput = it },
                    placeholder = { Text("gsk_...", color = JavisTextDim) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JavisGreen,
                        unfocusedBorderColor = Color(0xFF163049),
                        focusedTextColor = Color(0xFFE8F6FF),
                        unfocusedTextColor = Color(0xFFE8F6FF),
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearGroq) { Text("Clear", color = JavisRed, fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (groqInput.isNotBlank()) onSaveGroq(groqInput) },
                        enabled = groqInput.isNotBlank()
                    ) { Text("Save", color = JavisGreen, fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Gemini (free, may be region-locked)",
                    color = JavisCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Get a free key at aistudio.google.com/apikey",
                    color = JavisTextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = geminiInput,
                    onValueChange = { geminiInput = it },
                    placeholder = { Text("Paste Gemini key", color = JavisTextDim) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JavisCyan,
                        unfocusedBorderColor = Color(0xFF163049),
                        focusedTextColor = Color(0xFFE8F6FF),
                        unfocusedTextColor = Color(0xFFE8F6FF),
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearGemini) { Text("Clear", color = JavisRed, fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (geminiInput.isNotBlank()) onSaveGemini(geminiInput) },
                        enabled = geminiInput.isNotBlank()
                    ) { Text("Save", color = JavisCyan, fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Anthropic (paid, pay-per-use)",
                    color = JavisTextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Get a key at console.anthropic.com",
                    color = JavisTextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = anthropicInput,
                    onValueChange = { anthropicInput = it },
                    placeholder = { Text("sk-ant-...", color = JavisTextDim) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JavisCyan,
                        unfocusedBorderColor = Color(0xFF163049),
                        focusedTextColor = Color(0xFFE8F6FF),
                        unfocusedTextColor = Color(0xFFE8F6FF),
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearAnthropic) { Text("Clear", color = JavisRed, fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (anthropicInput.isNotBlank()) onSaveAnthropic(anthropicInput) },
                        enabled = anthropicInput.isNotBlank()
                    ) { Text("Save", color = JavisTextDim, fontSize = 12.sp) }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Priority if more than one is set: Groq, then Gemini, then Anthropic.",
                    color = JavisTextDim,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = JavisCyan) }
        },
        containerColor = JavisPanel,
        titleContentColor = Color(0xFFE8F6FF),
        textContentColor = Color(0xFFE8F6FF),
    )
}
