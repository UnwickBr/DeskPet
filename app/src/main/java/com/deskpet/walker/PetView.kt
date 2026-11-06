package com.deskpet.walker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
    private val params: WindowManager.LayoutParams,
    initialSleepTimeoutMs: Long
) : View(context) {

    private enum class Mood { NONE, SNIFF, STAND }

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val spriteIdle = buildSprite(SPRITE_IDLE)
    private val spriteWalkA = buildSprite(SPRITE_WALK_A)
    private val spriteWalkB = buildSprite(SPRITE_WALK_B)
    private val spriteSleep = buildSprite(SPRITE_SLEEP)
    private val spriteReact = buildSprite(SPRITE_REACT)
    private val spriteSniffA = buildSprite(SPRITE_SNIFF_A)
    private val spriteSniffB = buildSprite(SPRITE_SNIFF_B)
    private val spriteHeld = buildSprite(SPRITE_HELD)
    private var currentSprite = spriteIdle
    private var dragging = false

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
    private var sleepTimeoutMs = initialSleepTimeoutMs

    private var reacting = false
    private val reactEndRunnable = Runnable {
        reacting = false
        updateSprite()
        invalidate()
    }

    private var zPhase = 0f
    private val zPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val zOutlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = false
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val sleepAnimRunnable = object : Runnable {
        override fun run() {
            if (!asleep) return
            zPhase += 0.06f
            invalidate()
            handler.postDelayed(this, SLEEP_ANIM_INTERVAL_MS)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var walking = false

    private var wasWalkingBeforeDrag = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    private var falling = false
    private var fallVelocity = 0f
    private var fallVelocityX = 0f
    private var landSquash = 0f

    private var swayAngle = 0f
    private var swayVelocity = 0f
    private var lastDragX = 0
    private var lastDragY = 0
    private var lastMoveTime = 0L
    private var dragVelocityX = 0f
    private var dragVelocityY = 0f

    private var mood = Mood.NONE
    private var moodEndAt = 0L
    private var nextMoodAt = System.currentTimeMillis() + randomMoodDelayMs()
    private var sniffFrameToggle = false
    private var sniffStepCounter = 0

    private val stepRunnable = object : Runnable {
        override fun run() {
            if (!walking) return
            step()
            handler.postDelayed(this, STEP_INTERVAL_MS)
        }
    }

    private val fallRunnable = object : Runnable {
        override fun run() {
            if (!falling) return
            fallStep()
            if (falling) handler.postDelayed(this, FALL_INTERVAL_MS)
        }
    }

    private val landSquashRunnable = object : Runnable {
        override fun run() {
            landSquash -= 0.15f
            if (landSquash < 0f) landSquash = 0f
            invalidate()
            if (landSquash > 0f) handler.postDelayed(this, 30L)
        }
    }

    private val swayRunnable = object : Runnable {
        override fun run() {
            swayVelocity += -SWAY_SPRING * swayAngle
            swayVelocity *= SWAY_DAMPING
            swayAngle = (swayAngle + swayVelocity).coerceIn(-SWAY_MAX_ANGLE, SWAY_MAX_ANGLE)
            invalidate()
            if (dragging || abs(swayAngle) > 0.05f || abs(swayVelocity) > 0.05f) {
                handler.postDelayed(this, SWAY_INTERVAL_MS)
            } else {
                swayAngle = 0f
                swayVelocity = 0f
            }
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
        markInteraction()
        if (asleep) {
            wakeUp()
            startWalking()
        }
        reacting = true
        handler.removeCallbacks(reactEndRunnable)
        handler.postDelayed(reactEndRunnable, REACT_DURATION_MS)
        updateSprite()
        invalidate()
    }

    fun setSleepTimeoutMs(ms: Long) {
        sleepTimeoutMs = ms
    }

    private fun markInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun enterSleep() {
        asleep = true
        walking = false
        handler.removeCallbacks(stepRunnable)
        hopPhase = 0.0
        zPhase = 0f
        params.y = baseY
        updateSprite()
        windowManager.updateViewLayout(this, params)
        invalidate()
        handler.post(sleepAnimRunnable)
    }

    private fun wakeUp() {
        if (!asleep) return
        asleep = false
        handler.removeCallbacks(sleepAnimRunnable)
        updateSprite()
    }

    private fun fallStep() {
        fallVelocity += GRAVITY_PX
        val groundY = screenHeight - height
        var newY = params.y + fallVelocity.toInt()
        if (newY <= 0) {
            newY = 0
            fallVelocity = 0f
        }
        if (newY >= groundY) {
            newY = groundY
            landOnGround()
        }
        params.y = newY
        baseY = newY

        var newX = params.x + fallVelocityX.toInt()
        if (newX <= 0) {
            newX = 0
            fallVelocityX = 0f
        } else if (newX >= screenWidth - width) {
            newX = screenWidth - width
            fallVelocityX = 0f
        }
        params.x = newX
        fallVelocityX *= FALL_X_FRICTION
        if (fallVelocityX != 0f) direction = if (fallVelocityX > 0) 1 else -1

        windowManager.updateViewLayout(this, params)
        invalidate()
    }

    private fun landOnGround() {
        falling = false
        fallVelocity = 0f
        fallVelocityX = 0f
        landSquash = 1f
        handler.removeCallbacks(landSquashRunnable)
        handler.post(landSquashRunnable)
        if (wasWalkingBeforeDrag) {
            startWalking()
        } else {
            updateSprite()
            invalidate()
        }
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

    private fun randomMoodDelayMs() = (8_000..20_000).random().toLong()

    private fun randomMoodDurationMs() = (1_500..2_800).random().toLong()

    private fun step() {
        if (System.currentTimeMillis() - lastInteractionAt >= sleepTimeoutMs) {
            enterSleep()
            return
        }

        val now = System.currentTimeMillis()
        if (mood != Mood.NONE) {
            if (now >= moodEndAt) {
                mood = Mood.NONE
                nextMoodAt = now + randomMoodDelayMs()
            }
        } else if (now >= nextMoodAt) {
            mood = Mood.SNIFF
            moodEndAt = now + randomMoodDurationMs()
            sniffStepCounter = 0
            sniffFrameToggle = false
        }

        if (mood != Mood.NONE) {
            if (mood == Mood.SNIFF) {
                sniffStepCounter++
                if (sniffStepCounter >= SNIFF_FRAME_STEPS) {
                    sniffStepCounter = 0
                    sniffFrameToggle = !sniffFrameToggle
                }
            }
            updateSprite()
            windowManager.updateViewLayout(this, params)
            invalidate()
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
            dragging -> spriteHeld
            mood == Mood.SNIFF -> if (sniffFrameToggle) spriteSniffB else spriteSniffA
            mood == Mood.STAND -> spriteHeld
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
                falling = false
                handler.removeCallbacks(fallRunnable)
                if (mood != Mood.NONE) {
                    mood = Mood.NONE
                    nextMoodAt = System.currentTimeMillis() + randomMoodDelayMs()
                }
                val wasAsleep = asleep
                dragging = true
                triggerReaction()

                wasWalkingBeforeDrag = if (wasAsleep) true else walking
                walking = false
                handler.removeCallbacks(stepRunnable)
                params.y = baseY
                windowManager.updateViewLayout(this, params)

                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartParamX = params.x
                dragStartParamY = params.y
                lastDragX = params.x
                lastDragY = params.y
                lastMoveTime = System.currentTimeMillis()
                dragVelocityX = 0f
                dragVelocityY = 0f
                handler.removeCallbacks(swayRunnable)
                handler.post(swayRunnable)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                params.x = (dragStartParamX + dx).coerceIn(0, screenWidth - width)
                params.y = (dragStartParamY + dy).coerceIn(0, screenHeight - height)

                val now = System.currentTimeMillis()
                val dt = (now - lastMoveTime).coerceAtLeast(1L).toFloat()
                val instVelX = (params.x - lastDragX) / dt
                val instVelY = (params.y - lastDragY) / dt
                dragVelocityX = dragVelocityX * VELOCITY_SMOOTHING + instVelX * (1f - VELOCITY_SMOOTHING)
                dragVelocityY = dragVelocityY * VELOCITY_SMOOTHING + instVelY * (1f - VELOCITY_SMOOTHING)
                lastMoveTime = now
                lastDragX = params.x
                lastDragY = params.y

                swayVelocity += dragVelocityX * SWAY_VELOCITY_IMPULSE
                windowManager.updateViewLayout(this, params)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                markInteraction()
                dragging = false
                val groundY = screenHeight - height
                if (params.y < groundY) {
                    fallVelocity = (dragVelocityY * THROW_VELOCITY_SCALE).coerceIn(-MAX_THROW_SPEED, MAX_THROW_SPEED)
                    fallVelocityX = (dragVelocityX * THROW_VELOCITY_SCALE).coerceIn(-MAX_THROW_SPEED, MAX_THROW_SPEED)
                    falling = true
                    handler.post(fallRunnable)
                } else {
                    baseY = params.y
                    if (wasWalkingBeforeDrag) startWalking()
                }
                updateSprite()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val airTime = if (mood != Mood.NONE) 0f else abs(sin(hopPhase)).toFloat()
        val squashScaleY = 1f - landSquash * 0.35f
        val squashScaleX = 1f + landSquash * 0.25f
        val scaleY = (1f + airTime * 0.12f) * squashScaleY
        // A arte do SPRITE olha para a esquerda, entao precisa inverter ao andar para a direita
        val scaleX = (1f - airTime * 0.06f) * squashScaleX * if (direction < 0) 1f else -1f

        // A view e mais larga que o desenho para sobrar margem lateral pro
        // balanco (sway); o sprite fica centralizado ocupando 2/3 da largura.
        val marginX = w / 6f
        val spriteRect = RectF(marginX, 0f, w - marginX, h)

        // Balanco gira em torno de um ponto fixo na cabeca (perto do topo),
        // como se estivesse sendo segurado por ali.
        val headPivotY = h * 0.1f

        canvas.save()
        canvas.rotate(swayAngle, w / 2f, headPivotY)
        canvas.scale(scaleX, scaleY, w / 2f, h)
        canvas.drawBitmap(currentSprite, null, spriteRect, bitmapPaint)
        canvas.restore()

        if (asleep) {
            drawSleepZs(canvas, spriteRect.left, spriteRect.width(), h)
        }
    }

    private fun drawSleepZs(canvas: Canvas, spriteLeft: Float, spriteW: Float, h: Float) {
        val cycle = 3f
        val letters = charArrayOf('z', 'Z', 'z')
        for (i in letters.indices) {
            val t = ((zPhase + i * (cycle / letters.size)) % cycle) / cycle
            val alpha = (255 * (1f - t * 0.7f)).toInt().coerceIn(0, 255)
            val size = 26f + t * 24f
            val x = spriteLeft + spriteW * 0.6f + i * (spriteW * 0.14f)
            val y = h * 0.34f * (1f - t) + 6f
            zOutlinePaint.textSize = size
            zOutlinePaint.alpha = alpha
            canvas.drawText(letters[i].toString(), x, y, zOutlinePaint)
            zPaint.textSize = size
            zPaint.alpha = alpha
            canvas.drawText(letters[i].toString(), x, y, zPaint)
        }
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
        private const val SNIFF_FRAME_STEPS = 6
        private const val REACT_DURATION_MS = 600L
        private const val SLEEP_ANIM_INTERVAL_MS = 90L
        const val DEFAULT_SLEEP_TIMEOUT_MS = 30_000L
        private const val GRAVITY_PX = 3.2f
        private const val FALL_INTERVAL_MS = 16L
        private const val FALL_X_FRICTION = 0.985f
        private const val SWAY_SPRING = 0.05f
        private const val SWAY_DAMPING = 0.88f
        private const val SWAY_VELOCITY_IMPULSE = 0.8f
        private const val SWAY_MAX_ANGLE = 28f
        private const val SWAY_INTERVAL_MS = 16L
        private const val VELOCITY_SMOOTHING = 0.6f
        private const val THROW_VELOCITY_SCALE = 14f
        private const val MAX_THROW_SPEED = 40f

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
            "...KK..KK......KK.",
            "..KEEKKEEK.....KK.",
            "..KREKKREK.....KK.",
            "..KREKKREK.....KK.",
            ".KFFFFFFFFK.......",
            ".KFFFFFFFFK....KK.",
            "KFKKFFFKKFFK...KK.",
            "KFKKFFFKKFFK.KK...",
            "KFFFEKEFFFFKKEEK..",
            ".KFFEEEFFFKFFKEK..",
            "..KKKKKKKKFFFKK...",
            "...KFFFFFFFFFK....",
            "...KFFKFFKFFK.....",
            "....KKKKKKKK......",
        )

        // cheirando o chao: nariz subindo
        private val SPRITE_SNIFF_A = arrayOf(
            "..................",
            "..................",
            "...KK..KK.........",
            "..KEEKKEEK........",
            "..KREKKREK........",
            "..KREKKREK........",
            ".KFFFFFFFFK..KK...",
            ".KFFFFFFFFKKKEEK..",
            "KFFKFFFKFFFKFKEK..",
            "KFFKFFFKFFFKFKK...",
            "KFFFEKEFFFFKFK....",
            ".KFFEEEFFFKFK.....",
            "..KKKKKKKKKK......",

        )

        // cheirando o chao: nariz descendo
        private val SPRITE_SNIFF_B = arrayOf(
            "..................",
            "..................",
            "...KK..KK.........",
            "..KEEKKEEK........",
            "..KREKKREK........",
            "..KREKKREK........",
            ".KFFFFFFFFK..KK...",
            ".KFFFFFFFFKKKEEK..",
            "KFFKFFFKFFFKFKEK..",
            "KFFKFFFKFFFKFKK...",
            "KFFFEEEFFFFKFK....",
            ".KFFEKEFFFKFK.....",
            "..KKKKKKKKKK......",

        )

        // segurando o coelho
        private val SPRITE_HELD = arrayOf(
            "..................",
            "......KK..KK......",
            ".....KEEKKEEK.....",
            ".....KREKKREK.....",
            ".....KREKKREK.....",
            "....KFFFFFFFFK....",
            "....KFFFFFFFFK....",
            "...KFFKFFFKFFFK...",
            "...KFFKFFFKFFFK...",
            "...KFFFEKEFFFFK...",
            "....KFFEEEFFFK....",
            ".....KKKKKKKK.....",
            ".....KFFFFFFK.....",
            "....KFFFFFFFFK....",
            "....KKFKFKFKFK....",
            "....KFKFFFKFFKK...",
            "....KFFFFFFFFKEK..",
            "....KFFFFFFFFKEK..",
            ".....KFFKKFFKKK...",
            "......KK..KK......",
        )
    }
}
