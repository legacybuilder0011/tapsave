package com.plutoforce.tapsave

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var backendField: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backendField = findViewById(R.id.backendField)
        statusText = findViewById(R.id.statusText)
        backendField.setText(Prefs.backend(this))

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            Prefs.setBackend(this, backendField.text.toString())
            toast("Server address saved")
            updateStatus()
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startBubble() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            toast("Floating button stopped")
        }

        maybeRequestNotifications()
        handleSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /** Kicks off a download when a link is shared into the app. */
    private fun handleSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = Prefs.firstUrl(shared)
        when {
            url == null -> toast("No link found in the shared text")
            Prefs.backend(this).isBlank() -> toast("Set the server address first")
            else -> {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_DOWNLOAD
                    putExtra(OverlayService.EXTRA_URL, url)
                }
                startForegroundService(serviceIntent)
                toast("Downloading shared link…")
            }
        }
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant the 'display over other apps' permission first")
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
        startForegroundService(intent)
        toast("Floating button started")
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("Overlay permission already granted")
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updateStatus() {
        val hasBackend = Prefs.backend(this).isNotBlank()
        val hasOverlay = Settings.canDrawOverlays(this)
        statusText.text = buildString {
            append(if (hasBackend) "✓ Server address set\n" else "✗ Server address not set\n")
            append(if (hasOverlay) "✓ Overlay permission granted" else "✗ Overlay permission needed")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
