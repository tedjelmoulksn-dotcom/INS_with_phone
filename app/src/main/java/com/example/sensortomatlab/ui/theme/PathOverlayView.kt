package com.example.sensortomatlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PathOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var start = PointF(0f, 0f)
    private var end = PointF(0f, 0f)

    private var progressRatio = 0f  // entre 0 et 1

    private val paint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 12f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintStart = Paint().apply { color = Color.GREEN; isAntiAlias = true }
    private val paintEnd = Paint().apply { color = Color.MAGENTA; isAntiAlias = true }

    /** Définir les points du trajet */
    fun setPath(startX: Float, startY: Float, endX: Float, endY: Float) {
        start = PointF(startX, startY)
        end = PointF(endX, endY)
        progressRatio = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val progressX = start.x + (end.x - start.x) * progressRatio
        val progressY = start.y + (end.y - start.y) * progressRatio

        // tracé progressif de la ligne bleue
        canvas.drawLine(start.x, start.y, progressX, progressY, paint)

        // dessin des 2 points
        canvas.drawCircle(start.x, start.y, 20f, paintStart)
        canvas.drawCircle(end.x, end.y, 20f, paintEnd)
    }

    /** Appelée quand MATLAB envoie la distance parcourue */
    fun updateProgress(ratio: Float) {
        progressRatio = ratio.coerceIn(0f, 1f)  // forcer entre 0 et 1
        invalidate()
    }
}
