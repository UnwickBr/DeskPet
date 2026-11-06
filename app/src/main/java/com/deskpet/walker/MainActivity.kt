package com.deskpet.walker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sleepSecondsInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        sleepSecondsInput = findViewById(R.id.sleepSecondsInput)

        val prefs = getSharedPreferences(PetOverlayService.PREFS_NAME, MODE_PRIVATE)
        val currentSeconds = prefs.getLong(
            PetOverlayService.KEY_SLEEP_TIMEOUT_MS,
            PetView.DEFAULT_SLEEP_TIMEOUT_MS
        ) / 1000
        sleepSecondsInput.setText(currentSeconds.toString())

        findViewById<Button>(R.id.grantPermissionButton).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, PetOverlayService::class.java))
                statusText.text = getString(R.string.status_running)
            } else {
                statusText.text = getString(R.string.status_need_permission)
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, PetOverlayService::class.java))
            statusText.text = getString(R.string.status_stopped)
        }

        findViewById<Button>(R.id.randomMoveButton).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                val intent = Intent(this, PetOverlayService::class.java).apply {
                    action = PetOverlayService.ACTION_TOGGLE_RANDOM
                }
                startForegroundService(intent)
            } else {
                statusText.text = getString(R.string.status_need_permission)
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.saveSleepTimeoutButton).setOnClickListener {
            val seconds = sleepSecondsInput.text.toString().toIntOrNull()
            if (seconds == null || seconds <= 0) {
                Toast.makeText(this, "Digite um número de segundos maior que zero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timeoutMs = seconds * 1000L
            getSharedPreferences(PetOverlayService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PetOverlayService.KEY_SLEEP_TIMEOUT_MS, timeoutMs)
                .apply()

            if (PetOverlayService.isRunning) {
                val intent = Intent(this, PetOverlayService::class.java).apply {
                    action = PetOverlayService.ACTION_SET_SLEEP_TIMEOUT
                    putExtra(PetOverlayService.EXTRA_SLEEP_TIMEOUT_MS, timeoutMs)
                }
                startService(intent)
            }

            Toast.makeText(this, "Tempo de sono ajustado para ${seconds}s", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (Settings.canDrawOverlays(this)) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_need_permission)
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}
