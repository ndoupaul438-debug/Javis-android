package com.javis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val viewModel: JavisViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op; the app still works without it, just no reminder notifications */ }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) WakeWordService.start(this)
    }

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
                    onToggleListening = { toggleListening() }
                )
            }
        }
    }

    private fun toggleListening() {
        val isRunning = WakeWordServiceState.isRunning.value
        if (isRunning) {
            WakeWordService.stop(this)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                WakeWordService.start(this)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOnlineStatus()
    }
}

private val JavisCyan = Color(0xFF2FD8FF)
private val JavisGreen = Color(0xFF33E08A)
private val JavisTextDim = Color(0xFF7A9AC0)
private val SpaceDeep = Color(0xFF02040C)
private val SpaceMid = Color(0xFF060B1F)
private val SpacePanel = Color(0xFF0A1226)

@Composable
fun JavisTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = JavisCyan,
        background = SpaceDeep,
        surface = SpacePanel,
        onBackground = Color(0xFFE8F6FF),
        onSurface = Color(0xFFE8F6FF),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private data class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

@Composable
private fun SpaceBackground() {
    val stars = remember {
        List(90) {
            Star(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 1.6f + 0.4f,
                alpha = Random.nextFloat() * 0.6f + 0.25f
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(SpaceMid, SpaceDeep),
                    radius = 1400f
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (star in stars) {
                drawCircle(
                    color = Color.White.copy(alpha = star.alpha),
                    radius = star.radius.dp.toPx(),
                    center = Offset(star.x * size.width, star.y * size.height)
                )
            }
        }
    }
}

@Composable
fun JavisApp(viewModel: JavisViewModel, onToggleListening: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRunning by WakeWordServiceState.isRunning.collectAsState()
    val status by WakeWordServiceState.status.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        SpaceBackground()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            IconButton(onClick = { showSettings = true }) {
                Text("⚙", color = JavisTextDim, fontSize = 20.sp)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AiCoreVisual(status = status)
            Spacer(Modifier.height(16.dp))
            Text(
                text = captionFor(status, isRunning),
                color = JavisTextDim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(36.dp))
            MicButton(isRunning = isRunning, status = status, onTap = onToggleListening)
        }

        if (state.lastError != null) {
            Text(
                state.lastError ?: "",
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            hasApiKey = state.hasApiKey,
            onSaveGroq = { key -> viewModel.saveGroqKey(key) },
            onClearGroq = { viewModel.clearGroqKey() },
            onSaveGemini = { key -> viewModel.saveGeminiKey(key) },
            onClearGemini = { viewModel.clearGeminiKey() },
            onSaveAnthropic = { key -> viewModel.saveApiKey(key) },
            onClearAnthropic = { viewModel.clearApiKey() },
            onDismiss = { showSettings = false }
        )
    }
}

private fun captionFor(status: ListeningStatus, isRunning: Boolean): String = when (status) {
    ListeningStatus.LISTENING_FOR_WAKE -> "Say \"Hey Javis\" any time"
    ListeningStatus.LISTENING_FOR_COMMAND -> "Listening..."
    ListeningStatus.THINKING -> "Thinking..."
    ListeningStatus.SPEAKING -> "Speaking..."
    ListeningStatus.IDLE -> if (isRunning) "Starting..." else "Tap the mic to start listening"
}

@Composable
private fun AiCoreVisual(status: ListeningStatus) {
    val ringColor by animateColorAsState(
        targetValue = when (status) {
            ListeningStatus.LISTENING_FOR_COMMAND -> JavisGreen
            ListeningStatus.THINKING -> JavisCyan
            ListeningStatus.SPEAKING -> JavisGreen
            else -> JavisCyan.copy(alpha = 0.55f)
        },
        animationSpec = tween(400),
        label = "ringColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "aiCore")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(7000, easing = LinearEasing)),
        label = "rotation"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val active = status != ListeningStatus.IDLE
    val amplitude = if (active) 1f else 0.15f
    val innerScale = 1f + (pulse * 0.07f * amplitude)
    val glowAlpha = 0.14f + (pulse * 0.2f * amplitude)

    Box(
        modifier = Modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(190.dp)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val segments = 28
            val gapDegrees = 4.5f
            val segmentDegrees = (360f / segments) - gapDegrees
            for (i in 0 until segments) {
                val startAngle = i * (360f / segments)
                val alpha = 0.2f + (i % 4) * 0.13f
                drawArc(
                    color = ringColor.copy(alpha = alpha),
                    startAngle = startAngle,
                    sweepAngle = segmentDegrees,
                    useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = glowAlpha))
        )

        Box(
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer { scaleX = innerScale; scaleY = innerScale }
                .clip(CircleShape)
                .background(SpacePanel)
                .border(2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("JAVIS", color = ringColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("AI CORE", color = JavisTextDim, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun MicButton(isRunning: Boolean, status: ListeningStatus, onTap: () -> Unit) {
    val micColor = when {
        status == ListeningStatus.LISTENING_FOR_COMMAND -> JavisGreen
        isRunning -> JavisCyan
        else -> JavisTextDim
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (isRunning) micColor.copy(alpha = 0.15f) else SpacePanel)
            .border(2.dp, micColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onTap) {
            Text("🎤", fontSize = 28.sp)
        }
    }
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
                Text("Groq (free, no card needed)", color = JavisGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Get a free key at console.groq.com", color = JavisTextDim, fontSize = 11.sp)
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
                    TextButton(onClick = onClearGroq) { Text("Clear", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (groqInput.isNotBlank()) onSaveGroq(groqInput) },
                        enabled = groqInput.isNotBlank()
                    ) { Text("Save", color = JavisGreen, fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))
                Text("Gemini (free, may be region-locked)", color = JavisCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Get a free key at aistudio.google.com/apikey", color = JavisTextDim, fontSize = 11.sp)
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
                    TextButton(onClick = onClearGemini) { Text("Clear", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (geminiInput.isNotBlank()) onSaveGemini(geminiInput) },
                        enabled = geminiInput.isNotBlank()
                    ) { Text("Save", color = JavisCyan, fontSize = 12.sp) }
                }

                Spacer(Modifier.height(10.dp))
                Text("Anthropic (paid, pay-per-use)", color = JavisTextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Get a key at console.anthropic.com", color = JavisTextDim, fontSize = 11.sp)
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
                    TextButton(onClick = onClearAnthropic) { Text("Clear", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
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
        containerColor = SpacePanel,
        titleContentColor = Color(0xFFE8F6FF),
        textContentColor = Color(0xFFE8F6FF),
    )
}
