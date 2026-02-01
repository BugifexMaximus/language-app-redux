package com.mystuff.simpletutor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermissionRequestCode = 1001
    private val recordAudioPermissionRequestCode = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button?>(R.id.debug_open_panel)?.setOnClickListener {
            val intent = Intent().setClassName(
                this,
                "com.mystuff.simpletutor.DebugPanelActivity"
            )
            startActivity(intent)
        }

        findViewById<Button?>(R.id.debug_open_vad)?.setOnClickListener {
            val intent = Intent().setClassName(
                this,
                "com.mystuff.simpletutor.DebugVadSettingsActivity"
            )
            startActivity(intent)
        }

        ensureNotificationPermissionAndStartService()
    }

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    ensureRecordAudioPermissionAndStartService()
                }
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Notification permission")
                        .setMessage("Allow notifications so the microphone service can show its foreground notification.")
                        .setPositiveButton("Allow") { _, _ ->
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(permission),
                                notificationPermissionRequestCode
                            )
                        }
                        .setNegativeButton("Not now", null)
                        .show()
                }
                else -> {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(permission),
                        notificationPermissionRequestCode
                    )
                }
            }
        } else {
            ensureRecordAudioPermissionAndStartService()
        }
    }

    private fun ensureRecordAudioPermissionAndStartService() {
        val permission = Manifest.permission.RECORD_AUDIO
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                startMicrophoneService()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                AlertDialog.Builder(this)
                    .setTitle("Microphone permission")
                    .setMessage("Allow microphone access so the foreground service can start.")
                    .setPositiveButton("Allow") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(permission),
                            recordAudioPermissionRequestCode
                        )
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    recordAudioPermissionRequestCode
                )
            }
        }
    }

    private fun startMicrophoneService() {
        val intent = Intent(this, MicrophoneForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ensureRecordAudioPermissionAndStartService()
        }

        if (requestCode == recordAudioPermissionRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startMicrophoneService()
        }
    }
}
