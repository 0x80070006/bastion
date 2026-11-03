package org.bastion.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import org.bastion.core.designsystem.theme.BastionTheme

// BASTION emblem traced from the brand logo (five chevron plates), on a 1000-unit-wide grid.
private val PLATES = listOf(
    floatArrayOf(455f, 0f, 457f, 143f, 166f, 319f, 86f, 296f, 16f, 275f, 13f, 268f),
    floatArrayOf(543f, 0f, 992f, 270f, 922f, 294f, 834f, 319f, 543f, 143f),
    floatArrayOf(0f, 348f, 161f, 405f, 278f, 673f, 234f, 740f, 197f, 790f),
    floatArrayOf(1000f, 348f, 803f, 792f, 769f, 743f, 725f, 670f, 839f, 405f),
    floatArrayOf(499f, 662f, 665f, 717f, 751f, 868f, 501f, 792f, 249f, 870f, 332f, 719f),
)
private const val GRID_WIDTH = 1000f
private const val GRID_HEIGHT = 870f

/** The BASTION emblem alone, without the wordmark. Drawn as vector paths in [color]. */
@Composable
fun Emblem(size: Dp, modifier: Modifier = Modifier, color: Color = BastionTheme.colors.accent) {
    Canvas(modifier.width(size).aspectRatio(GRID_WIDTH / GRID_HEIGHT)) {
        val scale = this.size.width / GRID_WIDTH
        val offsetY = (this.size.height - GRID_HEIGHT * scale) / 2f
        PLATES.forEach { plate ->
            val path = Path()
            for (i in 0 until plate.size / 2) {
                val point = Offset(plate[i * 2] * scale, plate[i * 2 + 1] * scale + offsetY)
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
            drawPath(path, color)
        }
    }
}
