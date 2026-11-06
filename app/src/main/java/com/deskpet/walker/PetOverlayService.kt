package com.deskpet.walker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager

class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var petView: PetView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        addPetView()
        isRunning = true
        PetNotificationListener.petCallback = {
            mainHandler.post { petView?.triggerReaction() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_RANDOM -> petView?.toggleRandomMovement()
            ACTION_SET_SLEEP_TIMEOUT -> {
                val ms = intent.getLongExtra(EXTRA_SLEEP_TIMEOUT_MS, PetView.DEFAULT_SLEEP_TIMEOUT_MS)
                petView?.setSleepTimeoutMs(ms)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        PetNotificationListener.petCallback = null
        petView?.let {
            it.stopWalking()
            windowManager.removeView(it)
        }
        petView = null
        super.onDestroy()
    }

    private fun addPetView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            PET_WIDTH_PX,
            PET_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 400

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sleepTimeoutMs = prefs.getLong(KEY_SLEEP_TIMEOUT_MS, PetView.DEFAULT_SLEEP_TIMEOUT_MS)

        val view = PetView(this, windowManager, params, sleepTimeoutMs)
        petView = view
        windowManager.addView(view, params)
        view.startWalking()
    }

    private fun buildNotification(): Notification {
        val channelId = "deskpet_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DeskPet",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_running))
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_TOGGLE_RANDOM = "com.deskpet.walker.action.TOGGLE_RANDOM"
        const val ACTION_SET_SLEEP_TIMEOUT = "com.deskpet.walker.action.SET_SLEEP_TIMEOUT"
        const val EXTRA_SLEEP_TIMEOUT_MS = "sleep_timeout_ms"
        const val PREFS_NAME = "deskpet_prefs"
        const val KEY_SLEEP_TIMEOUT_MS = "sleep_timeout_ms"
        private const val NOTIFICATION_ID = 1001
        // Largura maior que o desenho em si (168px), sobrando margem nas
        // laterais pra animacao de balanco nao cortar. PetView desenha o
        // sprite centralizado ocupando 2/3 dessa largura (ver onDraw).
        private const val PET_WIDTH_PX = 252
        private const val PET_HEIGHT_PX = 192

        @Volatile
        var isRunning = false
            private set
    }
}
