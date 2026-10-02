package one.yago.sorchat.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import one.yago.sorchat.app.R
import one.yago.sorchat.app.UiState
import one.yago.sorchat.app.ui.theme.LocalGradients
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun WelcomeScreen(state: UiState, onRegister: (String) -> Unit, onSignIn: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Box(Modifier.fillMaxSize().background(LocalGradients.current.dusk)) {
        FloatingBubbles()
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            BobbingLogo()
            Text("sorchat", style = MaterialTheme.typography.displayMedium, color = Color.White)
            Text(
                "Chats, voice notes and calls\nwith your people.",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(shown, enter = slideInVertically { it / 2 } + fadeIn()) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("What should we call you?", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(64) },
                            placeholder = { Text("Your name") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onRegister(name) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SunsetButton(
                            text = if (state.busy) "Setting up…" else "Get started",
                            onClick = { onRegister(name) },
                            enabled = name.isNotBlank() && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = onSignIn, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("I already have an account")
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

/** The logo, gently floating up and down. */
@Composable
fun BobbingLogo(size: androidx.compose.ui.unit.Dp = 160.dp) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "bob",
    )
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(size).offset { IntOffset(0, bob.dp.roundToPx()) },
    )
}

/** Soft translucent bubbles drifting upwards behind everything. */
@Composable
private fun FloatingBubbles() {
    val time by rememberInfiniteTransition(label = "bubbles").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing)),
        label = "time",
    )
    // x position, radius, speed, phase
    val bubbles = remember {
        listOf(
            floatArrayOf(0.12f, 70f, 1f, 0.0f), floatArrayOf(0.85f, 110f, 0.7f, 0.3f), floatArrayOf(0.5f, 50f, 1.4f, 0.6f),
            floatArrayOf(0.3f, 90f, 0.9f, 0.8f), floatArrayOf(0.7f, 40f, 1.2f, 0.15f), floatArrayOf(0.95f, 60f, 1.1f, 0.5f),
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        for ((x, r, speed, phase) in bubbles.map { it.toList() }) {
            val progress = (time * speed + phase) % 1f
            val y = size.height * (1.1f - progress * 1.3f)
            val sway = sin((progress * 2 * PI).toFloat() + phase * 10) * 24f
            drawCircle(Color.White.copy(alpha = 0.08f), radius = r * density / 2.5f, center = Offset(size.width * x + sway, y))
        }
    }
}
