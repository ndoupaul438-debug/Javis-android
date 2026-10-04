package com.javis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.javis.ui.JavisThemeId
import com.javis.ui.JavisThemePalette
import com.javis.ui.JavisThemeStore
import com.javis.ui.JavisViewModel
import com.javis.ui.paletteFor
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JavisTheme(
    palette: JavisThemePalette,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            background = palette.background,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private val viewModel: JavisViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
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
            var selectedTheme by remember {
                mutableStateOf(
                    JavisThemeStore.load(this@MainActivity)
                )
            }

            val palette = paletteFor(selectedTheme)

            JavisTheme(palette) {
                JavisApp(
                    viewModel = viewModel,
                    palette = palette,
                    selectedTheme = selectedTheme,
                    onThemeChange = {
                        selectedTheme = it
                        JavisThemeStore.save(
                            this@MainActivity,
                            it
                        )
                    },
                    onToggleListening = {
                        toggleListening()
                    }
                )
            }
        }
    }

    private fun toggleListening() {
        if (WakeWordServiceState.isRunning.value) {
            WakeWordService.stop(this)
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
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
            micPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
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
    palette: JavisThemePalette,
    selectedTheme: JavisThemeId,
    onThemeChange: (JavisThemeId) -> Unit,
    onToggleListening: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val running by WakeWordServiceState.isRunning.collectAsStateWithLifecycle()
    val status by WakeWordServiceState.status.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        PremiumBackground(palette)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "JAVIS",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp,
                        color = palette.text
                    )

                    Text(
                        text = "INTELLIGENCE CORE",
                        fontSize = 8.sp,
                        letterSpacing = 2.2.sp,
                        color = palette.primary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionPill(
                        connected = state.hasApiKey,
                        palette = palette
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = { showSettings = true }
                    ) {
                        Text(
                            text = "⚙",
                            fontSize = 21.sp,
                            color = palette.text
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            PremiumStatusCard(
                status = status,
                running = running,
                palette = palette
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                JavisCore(
                    status = status,
                    running = running,
                    palette = palette
                )
            }

            Text(
                text = statusText(status, running),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.text
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = secondaryStatus(status, running),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = palette.muted
            )

            Spacer(modifier = Modifier.height(12.dp))

            PremiumMicButton(
                enabled = running,
                palette = palette,
                onClick = onToggleListening
            )

            Spacer(modifier = Modifier.height(12.dp))

            QuickActions(palette)

            state.lastError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            palette = palette,
            selectedTheme = selectedTheme,
            onThemeChange = onThemeChange,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun PremiumBackground(
    palette: JavisThemePalette
) {
    val infinite = rememberInfiniteTransition(
        label = "background"
    )

    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 9000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backgroundShift"
    )

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val w = size.width
        val h = size.height

        val x1 = w * (0.15f + shift * 0.25f)
        val y1 = h * (0.12f + shift * 0.12f)

        val x2 = w * (0.90f - shift * 0.25f)
        val y2 = h * (0.75f - shift * 0.10f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primary.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(x1, y1),
                radius = w * 0.65f
            ),
            radius = w * 0.65f,
            center = Offset(x1, y1)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.secondary.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(x2, y2),
                radius = w * 0.60f
            ),
            radius = w * 0.60f,
            center = Offset(x2, y2)
        )
    }
}

@Composable
private fun ConnectionPill(
    connected: Boolean,
    palette: JavisThemePalette
) {
    val color = if (connected) {
        Color(0xFF4DE3A2)
    } else {
        palette.muted
    }

    Surface(
        shape = RoundedCornerShape(50.dp),
        color = palette.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            palette.border.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 6.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )

            Spacer(
                modifier = Modifier.width(6.dp)
            )

            Text(
                text = if (connected) "ONLINE" else "LOCAL",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = palette.text
            )
        }
    }
}

@Composable
private fun PremiumStatusCard(
    status: ListeningStatus,
    running: Boolean,
    palette: JavisThemePalette
) {
    val title = when {
        !running -> "JAVIS STANDBY"
        status == ListeningStatus.LISTENING_FOR_WAKE ->
            "LISTENING FOR JAVIS"
        status == ListeningStatus.LISTENING_FOR_COMMAND ->
            "LISTENING"
        status == ListeningStatus.THINKING ->
            "THINKING"
        status == ListeningStatus.SPEAKING ->
            "SPEAKING"
        else -> "READY"
    }

    val detail = when {
        !running -> "Voice assistant is offline"
        status == ListeningStatus.LISTENING_FOR_WAKE ->
            "Say Hey Javis to begin"
        status == ListeningStatus.LISTENING_FOR_COMMAND ->
            "Tell me what you need"
        status == ListeningStatus.THINKING ->
            "Processing your request"
        status == ListeningStatus.SPEAKING ->
            "Javis is responding"
        else -> "System ready"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            palette.border.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                palette.primary.copy(alpha = 0.35f),
                                palette.surface2
                            )
                        )
                    )
                    .border(
                        1.dp,
                        palette.primary.copy(alpha = 0.45f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "J",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primary
                )
            }

            Spacer(
                modifier = Modifier.width(13.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = palette.text
                )

                Spacer(
                    modifier = Modifier.height(3.dp)
                )

                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = palette.muted
                )
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (running) palette.primary
                        else palette.muted
                    )
            )
        }
    }
}

@Composable
private fun JavisCore(
    status: ListeningStatus,
    running: Boolean,
    palette: JavisThemePalette
) {
    val infinite = rememberInfiniteTransition(label = "javisCore")

    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulse by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val energy by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "energy"
    )

    val active = running && status != ListeningStatus.IDLE
    val thinking = status == ListeningStatus.THINKING
    val speaking = status == ListeningStatus.SPEAKING
    val listening =
        status == ListeningStatus.LISTENING_FOR_WAKE ||
        status == ListeningStatus.LISTENING_FOR_COMMAND

    val stateColor = when {
        thinking -> palette.secondary
        speaking -> palette.accent
        listening -> palette.primary
        running -> palette.primary
        else -> palette.muted
    }

    val stateScale = when {
        active -> pulse
        else -> 1f
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .graphicsLayer {
                scaleX = stateScale
                scaleY = stateScale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension * 0.19f
            val outer = size.minDimension * 0.45f

            // Deep ambient energy field.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateColor.copy(alpha = 0.22f + energy * 0.10f),
                        stateColor.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outer * 1.45f
                ),
                radius = outer * 1.45f,
                center = center
            )

            // Rotating outer intelligence ring.
            
                drawCircle(
                    color = palette.border.copy(
                        alpha = if (running) 0.75f else 0.35f
                    ),
                    radius = outer,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5f
                    )
                )

                for (i in 0 until 48) {
                    val angle = Math.toRadians(i * 7.5)
                    val longTick = i % 4 == 0

                    val r1 = outer - if (longTick) 13f else 7f
                    val r2 = outer

                    val p1 = Offset(
                        center.x + cos(angle).toFloat() * r1,
                        center.y + sin(angle).toFloat() * r1
                    )

                    val p2 = Offset(
                        center.x + cos(angle).toFloat() * r2,
                        center.y + sin(angle).toFloat() * r2
                    )

                    drawLine(
                        color = if (longTick) {
                            stateColor.copy(
                                alpha = if (active) 0.85f else 0.42f
                            )
                        } else {
                            palette.border.copy(alpha = 0.42f)
                        },
                        start = p1,
                        end = p2,
                        strokeWidth = if (longTick) 2.5f else 1.2f,
                        cap = StrokeCap.Round
                    )
                }

            // State-reactive inner orbit.
            
                drawArc(
                    color = stateColor.copy(alpha = 0.9f),
                    startAngle = -35f,
                    sweepAngle = if (thinking) 285f else 220f,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - outer * 0.72f,
                        center.y - outer * 0.72f
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        outer * 1.44f,
                        outer * 1.44f
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.5f,
                        cap = StrokeCap.Round
                    )
                )

            // Core glow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f),
                        stateColor.copy(alpha = 0.95f),
                        stateColor.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = base * 2.8f
                ),
                radius = base * 2.8f,
                center = center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.surface2,
                        palette.surface
                    )
                ),
                radius = base,
                center = center
            )

            drawCircle(
                color = stateColor.copy(alpha = 0.85f),
                radius = base,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.5f
                )
            )
        }

        Text(
            text = "J",
            fontSize = 42.sp,
            fontWeight = FontWeight.ExtraBold,
            color = palette.text
        )

        Text(
            text = when {
                thinking -> "THINK"
                speaking -> "VOICE"
                listening -> "LISTEN"
                running -> "READY"
                else -> "OFF"
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = stateColor
        )
    }
}

@Composable
private fun PremiumMicButton(
    enabled: Boolean,
    palette: JavisThemePalette,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(
        label = "mic"
    )

    val pulse by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micPulse"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .graphicsLayer {
                    scaleX = if (enabled) pulse else 1f
                    scaleY = if (enabled) pulse else 1f
                }
                .shadow(
                    elevation = if (enabled) 18.dp else 5.dp,
                    shape = CircleShape,
                    ambientColor = palette.primary,
                    spotColor = palette.primary
                )
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        Brush.linearGradient(
                            colors = listOf(
                                palette.primary,
                                palette.secondary
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                palette.surface2,
                                palette.surface
                            )
                        )
                    }
                )
                .border(
                    1.dp,
                    if (enabled) {
                        palette.primary.copy(alpha = 0.75f)
                    } else {
                        palette.border
                    },
                    CircleShape
                )
                .clickable(
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(34.dp)
            ) {
                val centerX = size.width / 2f

                drawRoundRect(
                    color = if (enabled) {
                        Color.White
                    } else {
                        palette.muted
                    },
                    topLeft = Offset(
                        centerX - 7f,
                        3f
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        14f,
                        22f
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        7f,
                        7f
                    )
                )

                drawArc(
                    color = if (enabled) {
                        Color.White
                    } else {
                        palette.muted
                    },
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(
                        centerX - 12f,
                        10f
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        24f,
                        24f
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3f
                    )
                )

                drawLine(
                    color = if (enabled) {
                        Color.White
                    } else {
                        palette.muted
                    },
                    start = Offset(
                        centerX,
                        34f
                    ),
                    end = Offset(
                        centerX,
                        29f
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )

                drawLine(
                    color = if (enabled) {
                        Color.White
                    } else {
                        palette.muted
                    },
                    start = Offset(
                        centerX - 7f,
                        36f
                    ),
                    end = Offset(
                        centerX + 7f,
                        36f
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun QuickActions(
    palette: JavisThemePalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionCard(
            modifier = Modifier.weight(1f),
            title = "VOICE",
            subtitle = "Talk",
            symbol = "◉",
            palette = palette
        )

        QuickActionCard(
            modifier = Modifier.weight(1f),
            title = "ASSIST",
            subtitle = "Ask",
            symbol = "✦",
            palette = palette
        )

        QuickActionCard(
            modifier = Modifier.weight(1f),
            title = "ACTIONS",
            subtitle = "Control",
            symbol = "⌁",
            palette = palette
        )
    }
}

@Composable
private fun QuickActionCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    symbol: String,
    palette: JavisThemePalette
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = palette.surface.copy(alpha = 0.82f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            palette.border.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = 11.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = symbol,
                fontSize = 17.sp,
                color = palette.primary
            )

            Spacer(
                modifier = Modifier.height(5.dp)
            )

            Text(
                text = title,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = palette.text
            )

            Text(
                text = subtitle,
                fontSize = 8.sp,
                color = palette.muted
            )
        }
    }
}

@Composable
private fun ThemeSelector(
    selectedTheme: JavisThemeId,
    palette: JavisThemePalette,
    onThemeChange: (JavisThemeId) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "VISUAL IDENTITY",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = palette.primary
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        Text(
            text = "Choose the Javis interface personality",
            fontSize = 11.sp,
            color = palette.muted
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        JavisThemeId.values().forEach { theme ->
            ThemeOption(
                theme = theme,
                selected = theme == selectedTheme,
                palette = palette,
                onClick = {
                    onThemeChange(theme)
                }
            )

            Spacer(
                modifier = Modifier.height(7.dp)
            )
        }
    }
}

@Composable
private fun ThemeOption(
    theme: JavisThemeId,
    selected: Boolean,
    palette: JavisThemePalette,
    onClick: () -> Unit
) {
    val themePalette = paletteFor(theme)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick
            ),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) {
            themePalette.surface2.copy(alpha = 0.98f)
        } else {
            palette.surface.copy(alpha = 0.72f)
        },
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) {
                themePalette.primary
            } else {
                palette.border.copy(alpha = 0.8f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(35.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                themePalette.primary,
                                themePalette.secondary
                            )
                        )
                    )
                    .border(
                        1.dp,
                        themePalette.border,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "J",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            Spacer(
                modifier = Modifier.width(11.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = theme.title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = palette.text
                )

                Text(
                    text = theme.description,
                    fontSize = 9.sp,
                    color = palette.muted
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            themePalette.primary
                        )
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    viewModel: JavisViewModel,
    palette: JavisThemePalette,
    selectedTheme: JavisThemeId,
    onThemeChange: (JavisThemeId) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var groqKey by remember {
        mutableStateOf("")
    }

    var geminiKey by remember {
        mutableStateOf("")
    }

    var anthropicKey by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = "JAVIS SETTINGS",
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = palette.text
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        max = 520.dp
                    )
            ) {
                Column(
                    modifier = Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    ThemeSelector(
                        selectedTheme = selectedTheme,
                        palette = palette,
                        onThemeChange = onThemeChange
                    )

                    Spacer(
                        modifier = Modifier.height(20.dp)
                    )

                    Text(
                        text = "AI PROVIDERS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = palette.primary
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value = groqKey,
                        onValueChange = {
                            groqKey = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text("Groq API Key")
                        },
                        singleLine = true
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = {
                            geminiKey = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text("Gemini API Key")
                        },
                        singleLine = true
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value = anthropicKey,
                        onValueChange = {
                            anthropicKey = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text("Anthropic API Key")
                        },
                        singleLine = true
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "Provider priority: Groq → Gemini → Anthropic → LLMPI → Local",
                        fontSize = 10.sp,
                        color = palette.muted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.saveGroqKey(groqKey)
                    viewModel.saveGeminiKey(geminiKey)
                    viewModel.saveApiKey(anthropicKey)
                    onDismiss()
                }
            ) {
                Text(
                    text = "SAVE",
                    color = palette.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    text = "CANCEL",
                    color = palette.muted
                )
            }
        }
    )
}

private fun statusText(
    status: ListeningStatus,
    running: Boolean
): String {
    if (!running) return "Javis is offline"

    return when (status) {
        ListeningStatus.IDLE ->
            "Ready when you are"

        ListeningStatus.LISTENING_FOR_WAKE ->
            "Listening for your wake word"

        ListeningStatus.LISTENING_FOR_COMMAND ->
            "I'm listening"

        ListeningStatus.THINKING ->
            "Thinking..."

        ListeningStatus.SPEAKING ->
            "Speaking..."
    }
}

private fun secondaryStatus(
    status: ListeningStatus,
    running: Boolean
): String {
    if (!running) {
        return "Tap the button to activate voice mode"
    }

    return when (status) {
        ListeningStatus.IDLE ->
            "Voice session ready"

        ListeningStatus.LISTENING_FOR_WAKE ->
            "Say Hey Javis"

        ListeningStatus.LISTENING_FOR_COMMAND ->
            "Speak naturally — no need to repeat the wake word"

        ListeningStatus.THINKING ->
            "Connecting intelligence to your request"

        ListeningStatus.SPEAKING ->
            "You can interrupt when you need to"
    }
}
