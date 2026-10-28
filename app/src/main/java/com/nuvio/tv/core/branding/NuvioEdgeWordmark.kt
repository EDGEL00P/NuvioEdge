package com.nuvio.tv.core.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.nuvio.tv.R
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

fun nuvioEdgeWordmarkPng(context: Context): ByteArray {
    val source = BitmapFactory.decodeResource(context.resources, R.drawable.app_logo_wordmark)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textSize = source.height * 0.42f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val suffix = "Edge"
    val gap = source.height * 0.08f
    val width = source.width + ceil(gap + paint.measureText(suffix)).toInt()
    val output = Bitmap.createBitmap(width, source.height, Bitmap.Config.ARGB_8888)

    return try {
        val canvas = Canvas(output)
        canvas.drawBitmap(source, 0f, 0f, null)
        val metrics = paint.fontMetrics
        val baseline = (source.height - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(suffix, source.width + gap, baseline, paint)

        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    } finally {
        source.recycle()
        output.recycle()
    }
}
