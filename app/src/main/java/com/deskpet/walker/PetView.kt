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
    private val spriteIdle = buildSprite(SPRITE_IDLE)
    private val spriteWalkA = buildSprite(SPRITE_WALK_A)
    private val spriteWalkB = buildSprite(SPRITE_WALK_B)
    private val spriteSleep = buildSprite(SPRITE_SLEEP)
    private val spriteReact = buildSprite(SPRITE_REACT)
    private var currentSprite = spriteIdle

    private var direction = 1
    private var hopPhase = 0.0
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private var baseY = params.y

    private var randomMode = false
    private var vx = STEP_PX
    private var vy = 0
    private var stepsUntilRedirect = 0

    private var walkFrameToggle = false
    private var walkStepCounter = 0

    private var asleep = false
    private var lastInteractionAt = System.currentTimeMillis()

    private var reacting = false
    private val reactEndRunnable = Runnable {
        reacting = false
        updateSprite()
        invalidate()
    }

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

    fun toggleRandomMovement() {
        markInteraction()
        randomMode = !randomMode
        if (randomMode) pickRandomDirection()
    }

    fun triggerReaction() {
        if (asleep) return
        reacting = true
        handler.removeCallbacks(reactEndRunnable)
        handler.postDelayed(reactEndRunnable, REACT_DURATION_MS)
        updateSprite()
        invalidate()
    }

    private fun markInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun enterSleep() {
        asleep = true
        walking = false
        handler.removeCallbacks(stepRunnable)
        hopPhase = 0.0
        params.y = baseY
        updateSprite()
        windowManager.updateViewLayout(this, params)
        invalidate()
    }

    private fun wakeUp() {
        if (!asleep) return
        asleep = false
        updateSprite()
    }

    private fun pickRandomDirection() {
        val options = intArrayOf(-STEP_PX, 0, STEP_PX)
        do {
            vx = options.random()
            vy = options.random()
        } while (vx == 0 && vy == 0)
        if (vx != 0) direction = if (vx > 0) 1 else -1
        stepsUntilRedirect = (20..60).random()
    }

    private fun step() {
        if (System.currentTimeMillis() - lastInteractionAt >= SLEEP_TIMEOUT_MS) {
            enterSleep()
            return
        }

        if (randomMode) {
            stepRandom()
        } else {
            stepPatrol()
        }

        walkStepCounter++
        if (walkStepCounter >= WALK_FRAME_STEPS) {
            walkStepCounter = 0
            walkFrameToggle = !walkFrameToggle
        }

        hopPhase += HOP_SPEED
        val bounce = -abs(sin(hopPhase)) * HOP_HEIGHT_PX
        params.y = baseY + bounce.toInt()

        updateSprite()
        windowManager.updateViewLayout(this, params)
        invalidate()
    }

    private fun updateSprite() {
        currentSprite = when {
            asleep -> spriteSleep
            reacting -> spriteReact
            walking -> if (walkFrameToggle) spriteWalkB else spriteWalkA
            else -> spriteIdle
        }
    }

    private fun stepPatrol() {
        params.x += direction * STEP_PX

        if (params.x <= 0) {
            params.x = 0
            direction = 1
        } else if (params.x + width >= screenWidth) {
            params.x = screenWidth - width
            direction = -1
        }
    }

    private fun stepRandom() {
        params.x += vx
        baseY += vy

        var bounced = false
        if (params.x <= 0) {
            params.x = 0
            vx = abs(vx)
            bounced = true
        } else if (params.x + width >= screenWidth) {
            params.x = screenWidth - width
            vx = -abs(vx)
            bounced = true
        }
        if (baseY <= 0) {
            baseY = 0
            vy = abs(vy)
            bounced = true
        } else if (baseY + height >= screenHeight) {
            baseY = screenHeight - height
            vy = -abs(vy)
            bounced = true
        }
        if (vx != 0) direction = if (vx > 0) 1 else -1

        stepsUntilRedirect--
        if (bounced || stepsUntilRedirect <= 0) pickRandomDirection()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                markInteraction()
                val wasAsleep = asleep
                if (wasAsleep) {
                    wakeUp()
                } else {
                    triggerReaction()
                }

                wasWalkingBeforeDrag = if (wasAsleep) true else walking
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
                markInteraction()
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
        canvas.drawBitmap(currentSprite, null, RectF(0f, 0f, w, h), bitmapPaint)
        canvas.restore()
    }

    private fun buildSprite(pattern: Array<String>): Bitmap {
        val cols = pattern[0].length
        val bmp = Bitmap.createBitmap(cols, pattern.size, Bitmap.Config.ARGB_8888)
        for (y in pattern.indices) {
            val row = pattern[y]
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
        private const val WALK_FRAME_STEPS = 5
        private const val REACT_DURATION_MS = 600L
        private const val SLEEP_TIMEOUT_MS = 30_000L

        // Pixel art do coelho: '.' transparente, 'K' preto, 'F' pelo, 'E' orelha interna, 'R' rosa
        private val SPRITE_IDLE = arrayOf(
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

        // ciclo de caminhada: pata direita levantada
        private val SPRITE_WALK_A = arrayOf(
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
            "...KFFKFFKKKK.....",
            "....KKKKKK........",
        )

        // ciclo de caminhada: pata esquerda levantada
        private val SPRITE_WALK_B = arrayOf(
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
            "...KFFKFFKFFFK....",
            "...KKKKKKKFFFK....",
            "..........KKK.....",
        )

        // dormindo: olhos fechados
        private val SPRITE_SLEEP = arrayOf(
            "..................",
            "..................",
            "...KK..KK.........",
            "..KEEKKEEK........",
            "..KREKKREK........",
            "..KREKKREK........",
            ".KFFFFFFFFK.......",
            ".KFFFFFFFFK.......",
            "KFFFFFFFFFFK......",
            "KKKFFFKKFFFK.KK...",
            "KFFEKEFFFFFKKEEK..",
            ".KFEEEFFFFKFFKEK..",
            "..KKKKKKKKFFFKK...",
            "...KFFFFFFFFFK....",
            "....KKKKKKKKK.....",
        )

        // reacao (toque / notificacao): olhos arregalados
        private val SPRITE_REACT = arrayOf(
            "..................",
            "..................",
            "...KK..KK.........",
            "..KEEKKEEK........",
            "..KREKKREK........",
            "..KREKKREK........",
            ".KFFFFFFFFK.......",
            ".KKFFFKFFFK.......",
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
