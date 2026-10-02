package one.yago.sorchat.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.yago.sorchat.app.ui.theme.LocalGradients
import kotlin.math.absoluteValue

/** Warm gradient pairs; each contact gets one, picked by their id. */
private val AvatarGradients = listOf(
    Color(0xFFFF9A56) to Color(0xFFFF5F6D),
    Color(0xFFFF5F6D) to Color(0xFFE8458B),
    Color(0xFFE8458B) to Color(0xFF9B4DCA),
    Color(0xFFFFB347) to Color(0xFFFF7A59),
    Color(0xFFF06292) to Color(0xFFBA68C8),
    Color(0xFFFF8A65) to Color(0xFFD84E7A),
    Color(0xFF5CC8A8) to Color(0xFF2E9E8F),
)

@Composable
fun Avatar(name: String, id: String, size: Dp = 48.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val (from, to) = AvatarGradients[id.hashCode().absoluteValue % AvatarGradients.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(from, to)))
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Scales down a little while pressed, springing back: makes buttons feel squishy. */
@Composable
fun Modifier.squishy(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "squish")
    return scale(scale)
}

/** The primary call to action: a sunset-gradient pill. */
@Composable
fun SunsetButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .squishy(interaction)
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(LocalGradients.current.sunset)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, contentDescription = null, tint = Color.White) }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A round button with an icon, e.g. call controls. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    background: Brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.2f))),
    contentColor: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .squishy(interaction)
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.45f))
        }
    }
}

fun solid(color: Color) = Brush.linearGradient(listOf(color, color))
