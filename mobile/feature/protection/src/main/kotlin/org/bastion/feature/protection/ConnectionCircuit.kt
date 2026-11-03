package org.bastion.feature.protection

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.BorderTokens
import org.bastion.core.designsystem.tokens.RadiusTokens

/** One element of the connection circuit. */
enum class CircuitId(@StringRes val label: Int) {
    PHONE(R.string.circuit_phone),
    NETWORK(R.string.circuit_network),
    VPN(R.string.circuit_vpn),
    RELAY(R.string.circuit_relay),
    PC(R.string.circuit_pc),
    LOCATION(R.string.circuit_location),
    CONTROL(R.string.circuit_control),
}

private class Node(val id: CircuitId, val x: Float, val y: Float)

// Positions as fractions of the canvas. The trunk runs left to right through the middle; the two
// branches leave the phone vertically and turn right, like traces on a circuit board.
private val NODES = listOf(
    Node(CircuitId.PHONE, 0.10f, 0.50f),
    Node(CircuitId.NETWORK, 0.30f, 0.50f),
    Node(CircuitId.VPN, 0.50f, 0.50f),
    Node(CircuitId.RELAY, 0.70f, 0.50f),
    Node(CircuitId.PC, 0.90f, 0.50f),
    Node(CircuitId.LOCATION, 0.30f, 0.13f),
    Node(CircuitId.CONTROL, 0.30f, 0.87f),
)

private class Trace(val from: CircuitId, val to: CircuitId, val via: List<Pair<Float, Float>> = emptyList())

private val TRACES = listOf(
    Trace(CircuitId.PHONE, CircuitId.NETWORK),
    Trace(CircuitId.NETWORK, CircuitId.VPN),
    Trace(CircuitId.VPN, CircuitId.RELAY),
    Trace(CircuitId.RELAY, CircuitId.PC),
    Trace(CircuitId.PHONE, CircuitId.LOCATION, listOf(0.10f to 0.13f)),
    Trace(CircuitId.PHONE, CircuitId.CONTROL, listOf(0.10f to 0.87f)),
)

private const val NODE_WIDTH_DP = 54
private const val NODE_HEIGHT_DP = 26
private const val DASH_DP = 8
private const val FLOW_MS = 1400
private const val LABEL_ALPHA = 0.55f

/**
 * Circuit of the live connections. A trace is lit and flows only when both of its ends are really
 * up; otherwise it stays a dim, still line. [active] is read from real device state, not simulated.
 */
@Composable
fun ConnectionCircuit(active: Set<CircuitId>, modifier: Modifier = Modifier) {
    val colors = BastionTheme.colors
    val measurer = rememberTextMeasurer()
    val style = BastionTheme.typography.mono.copy(fontSize = 9.sp)
    val labels = CircuitId.entries.associateWith { stringResource(it.label) }
    val upCount = active.size
    val description = stringResource(R.string.circuit_description, upCount, CircuitId.entries.size)
    val flow = rememberInfiniteTransition(label = "circuit")
    val phase by flow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "flow",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(176.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val nodeW = NODE_WIDTH_DP.dp.toPx()
        val nodeH = NODE_HEIGHT_DP.dp.toPx()
        val dash = DASH_DP.dp.toPx()
        fun at(id: CircuitId): Offset = NODES.first { it.id == id }.let { Offset(it.x * w, it.y * h) }

        TRACES.forEach { trace ->
            val path = Path().apply {
                val start = at(trace.from)
                moveTo(start.x, start.y)
                trace.via.forEach { (vx, vy) -> lineTo(vx * w, vy * h) }
                val end = at(trace.to)
                lineTo(end.x, end.y)
            }
            val lit = trace.from in active && trace.to in active
            if (lit) {
                drawPath(
                    path,
                    colors.accent,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), -phase * dash * 2f),
                    ),
                )
            } else {
                drawPath(path, colors.inactive.copy(alpha = LABEL_ALPHA), style = Stroke(width = BorderTokens.Width.toPx()))
            }
        }

        NODES.forEach { node ->
            val up = node.id in active
            val center = Offset(node.x * w, node.y * h)
            val topLeft = Offset(center.x - nodeW / 2f, center.y - nodeH / 2f)
            val corner = CornerRadius(RadiusTokens.Md.toPx())
            drawRoundRect(colors.background, topLeft, Size(nodeW, nodeH), corner)
            drawRoundRect(
                if (up) colors.accent else colors.borderActive,
                topLeft,
                Size(nodeW, nodeH),
                corner,
                style = Stroke(BorderTokens.Width.toPx()),
            )
            drawCircle(if (up) colors.success else colors.inactive, 2.5.dp.toPx(), Offset(topLeft.x + 6.dp.toPx(), topLeft.y + 6.dp.toPx()))
            val text = labels.getValue(node.id)
            val layout = measurer.measure(
                text,
                style.copy(color = if (up) colors.textPrimary else colors.textSecondary, textAlign = TextAlign.Center),
            )
            drawText(
                layout,
                topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f + 2.dp.toPx()),
            )
        }
    }
}
