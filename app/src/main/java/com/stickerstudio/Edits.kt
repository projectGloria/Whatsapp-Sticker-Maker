package com.stickerstudio

import android.graphics.*
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

data class BrushPoint(val x: Float, val y: Float)

data class Stroke(val points: List<BrushPoint>, val size: Float, val restore: Boolean)

data class Edit(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val turns: Int = 0,
    val strokes: List<Stroke> = emptyList(),
    val text: String = "",
    val textX: Float = .5f,
    val textY: Float = .8f,
    val textSize: Float = 48f,
    val outline: Float = 8f,
    val start: Int = 0,
    val end: Int = 10000,
) {
    fun json(): String =
        JSONObject()
            .apply {
                put("left", left)
                put("top", top)
                put("right", right)
                put("bottom", bottom)
                put("turns", turns)
                put("text", text)
                put("textX", textX)
                put("textY", textY)
                put("textSize", textSize)
                put("outline", outline)
                put("start", start)
                put("end", end)
                put(
                    "strokes",
                    JSONArray().apply {
                        strokes.forEach { s ->
                            put(
                                JSONObject().apply {
                                    put("size", s.size)
                                    put("restore", s.restore)
                                    put(
                                        "points",
                                        JSONArray().apply {
                                            s.points.forEach { p ->
                                                put(JSONArray(listOf(p.x, p.y)))
                                            }
                                        },
                                    )
                                }
                            )
                        }
                    },
                )
            }
            .toString()

    companion object {
        fun parse(raw: String): Edit =
            runCatching {
                    val o = JSONObject(raw)
                    Edit(
                        o.optDouble("left", 0.0).toFloat(),
                        o.optDouble("top", 0.0).toFloat(),
                        o.optDouble("right", 1.0).toFloat(),
                        o.optDouble("bottom", 1.0).toFloat(),
                        o.optInt("turns"),
                        buildList {
                            val a = o.optJSONArray("strokes") ?: JSONArray()
                            for (i in 0 until a.length()) {
                                val s = a.getJSONObject(i)
                                val ps = s.getJSONArray("points")
                                add(
                                    Stroke(
                                        (0 until ps.length()).map {
                                            BrushPoint(
                                                ps.getJSONArray(it).getDouble(0).toFloat(),
                                                ps.getJSONArray(it).getDouble(1).toFloat(),
                                            )
                                        },
                                        s.getDouble("size").toFloat(),
                                        s.getBoolean("restore"),
                                    )
                                )
                            }
                        },
                        o.optString("text"),
                        o.optDouble("textX", .5).toFloat(),
                        o.optDouble("textY", .8).toFloat(),
                        o.optDouble("textSize", 48.0).toFloat(),
                        o.optDouble("outline", 8.0).toFloat(),
                        o.optInt("start"),
                        o.optInt("end", 10000),
                    )
                }
                .getOrDefault(Edit())
    }
}

object Renderer {
    fun render(source: Bitmap, edit: Edit, still: Boolean = true): Bitmap {
        val rotated =
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate((edit.turns % 4) * 90f) },
                true,
            )
        val l = (edit.left.coerceIn(0f, .95f) * rotated.width).toInt()
        val t = (edit.top.coerceIn(0f, .95f) * rotated.height).toInt()
        val r =
            (edit.right.coerceIn(edit.left + .05f, 1f) * rotated.width)
                .toInt()
                .coerceAtMost(rotated.width)
        val b =
            (edit.bottom.coerceIn(edit.top + .05f, 1f) * rotated.height)
                .toInt()
                .coerceAtMost(rotated.height)
        val clean = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        // Keep brush coordinates fixed while changing the outline width.
        val margin = if (still) 28f else 0f
        val scale = min((512 - 2 * margin) / (r - l), (512 - 2 * margin) / (b - t))
        val w = (r - l) * scale
        val h = (b - t) * scale
        Canvas(clean)
            .drawBitmap(
                rotated,
                Rect(l, t, r, b),
                RectF((512 - w) / 2, (512 - h) / 2, (512 + w) / 2, (512 + h) / 2),
                Paint(3),
            )
        if (rotated !== source) rotated.recycle()
        if (!still) return clean
        val mask = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        mc.drawColor(Color.WHITE)
        edit.strokes.forEach { s ->
            val p =
                Paint(3).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = s.size
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    if (!s.restore) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            val path = Path()
            s.points.forEachIndexed { i, point ->
                if (i == 0) path.moveTo(point.x * 512, point.y * 512)
                else path.lineTo(point.x * 512, point.y * 512)
            }
            if (s.points.size == 1) {
                val point = s.points[0]
                mc.drawCircle(
                    point.x * 512,
                    point.y * 512,
                    s.size / 2,
                    p.apply { style = Paint.Style.FILL },
                )
            } else mc.drawPath(path, p)
        }
        Canvas(clean)
            .drawBitmap(
                mask,
                0f,
                0f,
                Paint(3).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
            )
        mask.recycle()
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (edit.outline > 0) {
            val alpha = clean.extractAlpha()
            val white = Paint(3).apply { color = Color.WHITE }
            for (radius in 1..edit.outline.toInt()) for (a in 0 until 24) {
                val angle = a * PI / 12
                canvas.drawBitmap(
                    alpha,
                    (cos(angle) * radius).toFloat(),
                    (sin(angle) * radius).toFloat(),
                    white,
                )
            }
            alpha.recycle()
        }
        canvas.drawBitmap(clean, 0f, 0f, null)
        clean.recycle()
        if (edit.text.isNotBlank()) {
            val p =
                Paint(3).apply {
                    textSize = edit.textSize
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
            val lines = edit.text.split('\n')
            lines.forEachIndexed { index, line ->
                val y = edit.textY * 512 + index * edit.textSize * 1.1f
                p.style = Paint.Style.STROKE
                p.color = Color.BLACK
                canvas.drawText(line, edit.textX * 512, y, p)
                p.style = Paint.Style.FILL
                p.color = Color.WHITE
                canvas.drawText(line, edit.textX * 512, y, p)
            }
        }
        return out
    }
}
