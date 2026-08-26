package com.deskpet.walker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sin

class PetView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams
) : View(context) {

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val spriteBitmap = buildSprite()

    private var direction = 1
    private var hopPhase = 0.0
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private var baseY = params.y

    private val handler = Handler(Looper.getMainLooper())
    private var walking = false

    private var wasWalkingBeforeDrag = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    private val stepRunnable = object : Runnable {
        override fun run() {
            if (!walking) return
            step()
            handler.postDelayed(this, STEP_INTERVAL_MS)
        }
    }

    fun startWalking() {
        if (walking) return
        walking = true
        handler.post(stepRunnable)
    }

    fun stopWalking() {
        walking = false
        handler.removeCallbacks(stepRunnable)
    }

    private fun step() {
        params.x += direction * STEP_PX

        if (params.x <= 0) {
            params.x = 0
            direction = 1
        } else if (params.x + width >= screenWidth) {
            params.x = screenWidth - width
            direction = -1
        }

        hopPhase += HOP_SPEED
        val bounce = -abs(sin(hopPhase)) * HOP_HEIGHT_PX
        params.y = baseY + bounce.toInt()

        windowManager.updateViewLayout(this, params)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wasWalkingBeforeDrag = walking
                walking = false
                handler.removeCallbacks(stepRunnable)
                params.y = baseY
                windowManager.updateViewLayout(this, params)

                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartParamX = params.x
                dragStartParamY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                params.x = (dragStartParamX + dx).coerceIn(0, screenWidth - width)
                params.y = (dragStartParamY + dy).coerceIn(0, screenHeight - height)
                windowManager.updateViewLayout(this, params)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                baseY = params.y
                if (wasWalkingBeforeDrag) startWalking()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val airTime = abs(sin(hopPhase)).toFloat()
        val scaleY = 1f + airTime * 0.12f
        // A arte do SPRITE olha para a esquerda, entao precisa inverter ao andar para a direita
        val scaleX = (1f - airTime * 0.06f) * if (direction < 0) 1f else -1f

        canvas.save()
        canvas.scale(scaleX, scaleY, w / 2f, h)
        canvas.drawBitmap(spriteBitmap, null, RectF(0f, 0f, w, h), bitmapPaint)
        canvas.restore()
    }

    private fun buildSprite(): Bitmap {
        val cols = SPRITE[0].length
        val bmp = Bitmap.createBitmap(cols, SPRITE.size, Bitmap.Config.ARGB_8888)
        for (y in SPRITE.indices) {
            val row = SPRITE[y]
            for (x in row.indices) {
                val color = when (row[x]) {
                    'K' -> Color.parseColor("#000000")
                    'F' -> Color.parseColor("#C9A67C")
                    'E' -> Color.parseColor("#A6A6A6")
                    'R' -> Color.parseColor("#fcbee0")
                    else -> Color.TRANSPARENT
                }
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    companion object {
        private const val STEP_PX = 6
        private const val STEP_INTERVAL_MS = 60L
        private const val HOP_SPEED = 0.35
        private const val HOP_HEIGHT_PX = 26f

        // Pixel art do coelho: '.' transparente, 'K' preto, 'F' pelo, 'E' orelha interna, 'R' rosa
        private val SPRITE = arrayOf(
            "..................",
            "..................",
            "...KK..KK.........",
            "..KEEKKEEK........",
            "..KREKKREK........",
            "..KREKKREK........",
            ".KFFFFFFFFK.......",
            ".KFFFFFFFFK.......",
            "KFKFFFKFFFFK......",
            "KFKFFFKFFFFK.KK...",
            "KFFEKEFFFFFKKEEK..",
            ".KFEEEFFFFKFFKEK..",
            "..KKKKKKKKFFFKK...",
            "...KFFFFFFFFFK....",
            "...KFFKFFFKFFK....",
            "....KKKKKKKKK.....",
        )
    }
}
