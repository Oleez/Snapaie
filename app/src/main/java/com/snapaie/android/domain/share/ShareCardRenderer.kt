package com.snapaie.android.domain.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * One Canvas->Bitmap share-card system (extension's Forge PNG card + the PDF's
 * weekly Reader Report card). Cards carry a small watermark and deliberately no
 * download CTA — the user is sharing their achievement, not our ad.
 */
class ShareCardRenderer(private val context: Context) {

    /** Forge Recall session card: indigo->pink gradient, "FORGE RECALL 🔥", score + XP. */
    fun renderForgeCard(headline: String, subtitle: String): Bitmap {
        val width = 1080
        val height = 420
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#4f46e5"), Color.parseColor("#db2777"), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 48f, 48f, bg)

        val glow = Paint().apply {
            shader = RadialGradient(
                width * 0.85f, height * 0.15f, width * 0.5f,
                Color.argb(56, 251, 191, 36), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

        val title = textPaint(66f, Typeface.BOLD)
        canvas.drawText("FORGE RECALL 🔥", 64f, 130f, title)
        val head = textPaint(88f, Typeface.BOLD)
        canvas.drawText(headline, 64f, 250f, head)
        val sub = textPaint(48f, Typeface.NORMAL, alpha = 235)
        canvas.drawText(subtitle, 64f, 330f, sub)
        drawWatermark(canvas, width, height)
        return bitmap
    }

    /** Weekly Reader Report card: SnapAE dark/mint identity, big minutes-saved number. */
    fun renderReaderReportCard(
        minutesSaved: Int,
        pages: Int,
        avgCompression: Int,
        streakDays: Int,
    ): Bitmap {
        val width = 1080
        val height = 540
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#0E1415"), Color.parseColor("#16221E"), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 48f, 48f, bg)

        val mint = Color.parseColor("#88F0D0")
        val label = textPaint(44f, Typeface.NORMAL, alpha = 200)
        canvas.drawText("THIS WEEK I SAVED", 64f, 110f, label)

        val big = textPaint(180f, Typeface.BOLD).apply { color = mint }
        val bigText = formatMinutes(minutesSaved)
        canvas.drawText(bigText, 64f, 290f, big)
        val ofReading = textPaint(44f, Typeface.NORMAL, alpha = 200)
        canvas.drawText("of reading time", 64f, 360f, ofReading)

        val stats = textPaint(46f, Typeface.BOLD)
        canvas.drawText("$pages pages · $avgCompression% compressed · $streakDays-day streak 🔥", 64f, 450f, stats)
        drawWatermark(canvas, width, height)
        return bitmap
    }

    /** Writes [bitmap] to cache and returns a chooser intent sharing it as PNG. */
    fun shareIntent(bitmap: Bitmap, title: String): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "snapaie-card-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    private fun textPaint(size: Float, style: Int, alpha: Int = 255): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.alpha = alpha
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
    }

    private fun drawWatermark(canvas: Canvas, width: Int, height: Int) {
        val mark = textPaint(34f, Typeface.NORMAL, alpha = 150)
        val text = "snapaie · offline AI reading"
        val w = mark.measureText(text)
        canvas.drawText(text, width - w - 48f, height - 40f, mark)
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
