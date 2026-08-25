package com.snapaie.android.ui.book

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapaie.android.domain.output.BookLayout
import com.snapaie.android.domain.output.LaidOutPage
import com.snapaie.android.domain.output.PageSpec
import com.snapaie.android.domain.output.TextWidth

/**
 * Measures text the way the exported PDF will.
 *
 * The layout engine is shared with the writer, so whatever measures for the screen decides
 * where the page breaks fall. Using the platform's own measurement here means the pages
 * someone reads in the app are the pages that come out of the file.
 */
@Composable
fun rememberComposeTextWidth(measurer: TextMeasurer): TextWidth = remember(measurer) {
    TextWidth { text, sizePt, bold ->
        if (text.isEmpty()) {
            0f
        } else {
            measurer.measure(
                text = text,
                style = TextStyle(
                    fontSize = sizePt.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ).size.width.toFloat()
        }
    }
}

/**
 * One laid-out page, drawn at the size it will be printed.
 *
 * Everything is positioned in PDF points and scaled to the available width, so the page on
 * screen is the page in the file — same breaks, same margins, same figures in the same
 * places. Reading it should not require exporting anything first.
 */
@Composable
fun BookPage(
    page: LaidOutPage,
    spec: PageSpec,
    measurer: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val paper = if (dark) Color(0xFF1B1E1F) else Color(0xFFFCFBF7)
    val ink = if (dark) Color(0xFFE8EDEA) else Color(0xFF15191B)

    BoxWithConstraints(modifier = modifier) {
        val scale = constraints.maxWidth / spec.widthPt
        val pageHeight = with(LocalDensity.current) { (spec.heightPt * scale).toDp() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(pageHeight)
                .shadow(14.dp, RoundedCornerShape(6.dp), ambientColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(6.dp))
                .background(paper),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawPage(page, scale, ink, measurer) }
        }
    }
}

private fun DrawScope.drawPage(
    page: LaidOutPage,
    scale: Float,
    ink: Color,
    measurer: TextMeasurer,
) {
    page.images.forEach { image ->
        val bitmap = runCatching { BitmapFactory.decodeFile(image.path) }.getOrNull() ?: return@forEach
        drawImage(
            image = bitmap.asImageBitmap(),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                (image.xPt * scale).toInt(),
                (image.yPt * scale).toInt(),
            ),
            dstSize = androidx.compose.ui.unit.IntSize(
                (image.widthPt * scale).toInt().coerceAtLeast(1),
                (image.heightPt * scale).toInt().coerceAtLeast(1),
            ),
        )
    }

    page.lines.forEach { line ->
        if (line.text.isBlank()) return@forEach
        val laid = measurer.measure(
            text = line.text,
            style = TextStyle(
                color = ink,
                fontSize = (line.sizePt * scale).sp,
                fontFamily = FontFamily.Serif,
                fontWeight = if (line.bold) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        drawText(
            textLayoutResult = laid,
            // Layout gives a baseline from the top; drawText wants the top of the line box.
            topLeft = Offset(line.xPt * scale, line.yPt * scale - laid.firstBaseline),
        )
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)
