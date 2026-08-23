package com.javis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javis.assistant.ConversationTurn
import com.javis.ui.JavisUiState
import com.javis.ui.JavisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: JavisViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
        // If denied, the UI simply keeps working with text input — no crash, no nagging.
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

// ---------- Top-level screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavisApp(viewModel: JavisViewModel, onRequestMic: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }

    Scaffold(containerColor = JavisBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            HeaderBar(state)
            Spacer(Modifier.height(16.dp))
            StatusIndicator(state)
            Spacer(Modifier.height(16.dp))

            ConversationList(
                messages = state.messages,
                modifier = Modifier.weight(1f)
            )

            if (state.lastError != null) {
                Text(
                    state.lastError ?: "",
                    color = Color(0xFFFF6B6B),
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
        }
    }

    state.pendingConfirmation?.let { pending ->
        ConfirmationDialog(
            message = pending.summary,
            onConfirm = { viewModel.confirmPendingAction() },
            onCancel = { viewModel.cancelPendingAction() }
        )
    }
}

@Composable
private fun HeaderBar(state: JavisUiState) {
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
                    .background(if (state.isOnline) JavisGreen else Color(0xFFFF6B6B))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.isOnline) "ONLINE" else "OFFLINE",
                color = if (state.isOnline) JavisGreen else Color(0xFFFF6B6B),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
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
    Text(label, color = JavisCyan, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
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
            .background(background)
            .then(Modifier),
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
