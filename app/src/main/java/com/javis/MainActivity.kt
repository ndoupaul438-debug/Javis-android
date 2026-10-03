package com.javis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javis.service.ListeningStatus
import com.javis.service.WakeWordService
import com.javis.service.WakeWordServiceState
import com.javis.ui.JavisViewModel
import kotlin.math.cos
import kotlin.math.sin

private val JavisBlue = Color(0xFF45C7FF)
private val JavisBlueBright = Color(0xFF78E2FF)
private val JavisViolet = Color(0xFF766CFF)
private val JavisGreen = Color(0xFF4DE3A2)
private val JavisBackground = Color(0xFF05070D)
private val JavisSurface = Color(0xFF0B0F18)
private val JavisSurface2 = Color(0xFF101621)
private val JavisText = Color(0xFFF4F7FB)
private val JavisMuted = Color(0xFF8A95A8)

@Composable
fun JavisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JavisBlue,
            secondary = JavisViolet,
            background = JavisBackground,
            surface = JavisSurface,
            onBackground = JavisText,
            onSurface = JavisText
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private val viewModel: JavisViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) WakeWordService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }

        setContent {
            JavisTheme {
                JavisApp(
                    viewModel = viewModel,
                    onToggleListening = { toggleListening() }
                )
            }
        }
    }

    private fun toggleListening() {
        if (WakeWordServiceState.isRunning.value) {
            WakeWordService.stop(this)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            WakeWordService.start(this)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOnlineStatus()
    }
}

@Composable
private fun JavisApp(
    viewModel: JavisViewModel,
    onToggleListening: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val running by WakeWordServiceState.isRunning.collectAsStateWithLifecycle()
    val status by WakeWordServiceState.status.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JavisBackground)
    ) {
        PremiumBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "JAVIS",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = JavisText
                    )

                    Text(
                        text = "PERSONAL AI ASSISTANT",
                        fontSize = 9.sp,
                        letterSpacing = 1.7.sp,
                        color = JavisMuted
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionPill(state.hasApiKey)

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = { showSettings = true }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = JavisText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            PremiumStatusCard(
                status = status,
                running = running
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                JavisCore(
                    status = status,
                    running = running
                )
            }

            Text(
                text = statusText(status, running),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = JavisText
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = secondaryStatus(status, running),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = JavisMuted
            )

            Spacer(modifier = Modifier.height(16.dp))

            PremiumMicButton(
                enabled = running,
                onClick = onToggleListening
            )

            Spacer(modifier = Modifier.height(16.dp))

            QuickActions()

            state.lastError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun PremiumBackground() {
    val transition = rememberInfiniteTransition(label = "background")

    val movement by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "movement"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val first = Offset(
            size.width * (0.20f + movement * 0.08f),
            size.height * 0.18f
        )

        val second = Offset(
            size.width * 0.82f,
            size.height * (0.72f - movement * 0.06f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    JavisBlue.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = first,
                radius = size.width * 0.55f
            ),
            radius = size.width * 0.55f,
            center = first
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    JavisViolet.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = second,
                radius = size.width * 0.60f
            ),
            radius = size.width * 0.60f,
            center = second
        )
    }
}

@Composable
private fun ConnectionPill(
    connected: Boolean
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(
                if (connected)
                    JavisGreen.copy(alpha = 0.10f)
                else
                    Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (connected)
                    JavisGreen.copy(alpha = 0.25f)
                else
                    Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(50.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = if (connected) JavisGreen else JavisMuted
        )

        Spacer(modifier = Modifier.width(5.dp))

        Text(
            text = if (connected) "AI READY" else "LOCAL",
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold,
            color = if (connected) JavisGreen else JavisMuted
        )
    }
}

@Composable
private fun PremiumStatusCard(
    status: ListeningStatus,
    running: Boolean
) {
    val active = running && status != ListeningStatus.IDLE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        JavisSurface.copy(alpha = 0.96f),
                        JavisSurface2.copy(alpha = 0.84f)
                    )
                )
            )
            .border(
                1.dp,
                if (active)
                    JavisBlue.copy(alpha = 0.28f)
                else
                    Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    if (active) JavisGreen else JavisMuted
                )
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (running)
                    "Javis is active"
                else
                    "Javis is offline",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = JavisText
            )

            Text(
                text = if (running)
                    "Voice control is ready"
                else
                    "Tap the microphone to activate",
                fontSize = 11.sp,
                color = JavisMuted
            )
        }

        Text(
            text = statusLabel(status),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = if (active) JavisBlueBright else JavisMuted
        )
    }
}

@Composable
private fun JavisCore(
    status: ListeningStatus,
    running: Boolean
) {
    val transition = rememberInfiniteTransition(label = "core")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 9000,
                easing = LinearEasing
            )
        ),
        label = "rotation"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val active = running && status != ListeningStatus.IDLE

    Box(
        modifier = Modifier
            .size(280.dp)
            .graphicsLayer {
                if (active) {
                    scaleX = pulse
                    scaleY = pulse
                }
            },
        contentAlignment = Alignment.Center
    ) {

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val center = Offset(
                size.width / 2f,
                size.height / 2f
            )

            if (active) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JavisBlue.copy(alpha = 0.22f),
                            JavisViolet.copy(alpha = 0.07f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.48f
                    ),
                    radius = size.minDimension * 0.48f,
                    center = center
                )
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.035f),
                radius = size.minDimension * 0.39f,
                center = center
            )

            drawCircle(
                color = if (active)
                    JavisBlue.copy(alpha = 0.55f)
                else
                    Color.White.copy(alpha = 0.09f),
                radius = size.minDimension * 0.37f,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )

            val radius = size.minDimension * 0.43f

            for (i in 0 until 36) {
                val angle = Math.toRadians(
                    (i * 10f + rotation).toDouble()
                )

                val outer = Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius
                )

                val innerRadius =
                    radius -
                        if (i % 3 == 0)
                            13.dp.toPx()
                        else
                            7.dp.toPx()

                val inner = Offset(
                    center.x + cos(angle).toFloat() * innerRadius,
                    center.y + sin(angle).toFloat() * innerRadius
                )

                drawLine(
                    color = if (active && i % 3 == 0)
                        JavisBlueBright.copy(alpha = 0.95f)
                    else
                        Color.White.copy(alpha = 0.10f),
                    start = inner,
                    end = outer,
                    strokeWidth =
                        if (i % 3 == 0)
                            2.5.dp.toPx()
                        else
                            1.dp.toPx()
                )
            }
        }

        Box(
            modifier = Modifier
                .size(180.dp)
                .shadow(
                    elevation = if (active) 32.dp else 14.dp,
                    shape = CircleShape,
                    ambientColor = JavisBlue,
                    spotColor = JavisBlue
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF192A40),
                            Color(0xFF0D1625),
                            Color(0xFF080C14)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            JavisBlue.copy(alpha = 0.75f),
                            JavisViolet.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "J",
                    fontSize = 60.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = JavisText
                )

                Text(
                    text = "INTELLIGENCE",
                    fontSize = 8.sp,
                    letterSpacing = 2.2.sp,
                    color = JavisBlueBright
                )
            }
        }
    }
}

@Composable
private fun PremiumMicButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .shadow(
                    elevation = if (enabled) 20.dp else 8.dp,
                    shape = CircleShape
                )
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        Brush.linearGradient(
                            colors = listOf(
                                JavisBlueBright,
                                JavisBlue,
                                JavisViolet
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                JavisSurface2,
                                JavisSurface
                            )
                        )
                    }
                )
                .border(
                    1.dp,
                    Color.White.copy(
                        alpha = if (enabled) 0.35f else 0.10f
                    ),
                    CircleShape
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = if (enabled)
                    "Stop listening"
                else
                    "Start listening",
                modifier = Modifier.size(31.dp),
                tint = if (enabled)
                    Color.White
                else
                    JavisMuted
            )
        }
    }
}

@Composable
private fun QuickActions() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickAction(
            modifier = Modifier.weight(1f),
            title = "VOICE",
            subtitle = "Hey Javis"
        )

        QuickAction(
            modifier = Modifier.weight(1f),
            title = "ASSIST",
            subtitle = "Ask anything"
        )

        QuickAction(
            modifier = Modifier.weight(1f),
            title = "ACTIONS",
            subtitle = "Control phone"
        )
    }
}

@Composable
private fun QuickAction(
    modifier: Modifier,
    title: String,
    subtitle: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Color.White.copy(alpha = 0.035f)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(16.dp)
            )
            .padding(
                horizontal = 11.dp,
                vertical = 12.dp
            )
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = JavisText
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = subtitle,
            fontSize = 9.sp,
            color = JavisMuted
        )
    }
}

private fun statusLabel(
    status: ListeningStatus
): String {
    return when (status) {
        ListeningStatus.LISTENING_FOR_WAKE -> "READY"
        ListeningStatus.LISTENING_FOR_COMMAND -> "LISTENING"
        ListeningStatus.THINKING -> "THINKING"
        ListeningStatus.SPEAKING -> "SPEAKING"
        ListeningStatus.IDLE -> "STANDBY"
    }
}

private fun statusText(
    status: ListeningStatus,
    running: Boolean
): String {
    if (!running) {
        return "Javis is ready when you are"
    }

    return when (status) {
        ListeningStatus.LISTENING_FOR_WAKE ->
            "Say “Hey Javis”"

        ListeningStatus.LISTENING_FOR_COMMAND ->
            "I'm listening"

        ListeningStatus.THINKING ->
            "Let me think"

        ListeningStatus.SPEAKING ->
            "I'm speaking"

        ListeningStatus.IDLE ->
            "Starting Javis"
    }
}

private fun secondaryStatus(
    status: ListeningStatus,
    running: Boolean
): String {
    if (!running) {
        return "Tap the microphone to activate voice mode"
    }

    return when (status) {
        ListeningStatus.LISTENING_FOR_WAKE ->
            "You don't need to touch your phone"

        ListeningStatus.LISTENING_FOR_COMMAND ->
            "Tell me what you need"

        ListeningStatus.THINKING ->
            "Processing your request"

        ListeningStatus.SPEAKING ->
            "You can continue the conversation"

        ListeningStatus.IDLE ->
            "Preparing voice control"
    }
}

@Composable
private fun SettingsDialog(
    viewModel: JavisViewModel,
    onDismiss: () -> Unit
) {
    var groqKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var anthropicKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JavisSurface,
        title = {
            Text(
                text = "Javis Settings",
                fontWeight = FontWeight.Bold,
                color = JavisText
            )
        },
        text = {
            Column {
                Text(
                    text = "AI PROVIDERS",
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    color = JavisBlueBright
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Groq API key") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gemini API key") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Anthropic API key") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Priority: Groq → Gemini → Anthropic",
                    fontSize = 11.sp,
                    color = JavisMuted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (groqKey.isNotBlank()) {
                        viewModel.saveGroqKey(groqKey)
                    }

                    if (geminiKey.isNotBlank()) {
                        viewModel.saveGeminiKey(geminiKey)
                    }

                    if (anthropicKey.isNotBlank()) {
                        viewModel.saveApiKey(anthropicKey)
                    }

                    onDismiss()
                }
            ) {
                Text("SAVE")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.clearGroqKey()
                    viewModel.clearGeminiKey()
                    viewModel.clearApiKey()
                    onDismiss()
                }
            ) {
                Text("CLEAR")
            }
        }
    )
}
