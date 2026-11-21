package dev.welles.notes.feature.stickies.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.ui.NoteColors

/** Rectangle whose lower right corner is cut off diagonally – the outline of a post-it with a folded corner. */
class FoldedCornerShape(private val fold: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val f = with(density) { fold.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2)
        return Outline.Generic(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height - f)
                lineTo(size.width - f, size.height)
                lineTo(0f, size.height)
                close()
            },
        )
    }
}

/**
 * A post-it: paper in the note colour with a hint of adhesive strip at the top, a soft light fall,
 * a folded lower right corner and a lifted shadow that follows the outline. Same look as on the web.
 * [fold] 0 draws no corner (e.g. for desktop windows, which are rectangular).
 */
@Composable
fun StickyPaper(
    color: String,
    modifier: Modifier = Modifier,
    fold: Dp = 18.dp,
    elevation: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val paper = NoteColors.background(color)
    val shape = remember(fold) { if (fold > 0.dp) FoldedCornerShape(fold) else RectangleShape }
    Box(
        modifier
            .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .6f))
            .clip(shape)
            .background(paper)
            .drawBehind { paperShading() }
            .drawWithContent {
                drawContent()
                if (fold > 0.dp) flap(paper, fold.toPx())
            },
        content = content,
    )
}

private fun DrawScope.paperShading() {
    // Adhesive strip: the top edge is a touch darker.
    val strip = 18.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .045f), Color.Transparent), endY = strip), size = Size(size.width, strip))
    // Light from the upper left, paper slightly darker towards the bottom.
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = .35f), .45f to Color.Transparent, 1f to Color.Black.copy(alpha = .035f),
            start = Offset.Zero, end = Offset(size.width * .18f, size.height),
        ),
    )
}

/** The corner folded over: a triangle in paper colour, darker towards the crease. */
private fun DrawScope.flap(paper: Color, f: Float) {
    val w = size.width
    val h = size.height
    val triangle = Path().apply {
        moveTo(w - f, h - f)
        lineTo(w, h - f)
        lineTo(w - f, h)
        close()
    }
    drawPath(triangle, paper)
    drawPath(
        triangle,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = .25f), .8f to Color.Black.copy(alpha = .10f), 1f to Color.Black.copy(alpha = .20f),
            start = Offset(w - f, h - f), end = Offset(w - f / 2, h - f / 2),
        ),
    )
}
