package com.plutoforce.tapsave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Home screen: start/stop the floating button, pick download quality, and jump
 * to the WhatsApp Status Saver, download history, updates and help. The server
 * address is built in, so there's nothing to configure.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: TextView
    private lateinit var chipHigh: TextView
    private lateinit var chip720: TextView
    private lateinit var chip480: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkedThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        chipHigh = findViewById(R.id.chipHigh)
        chip720 = findViewById(R.id.chip720)
        chip480 = findViewById(R.id.chip480)

        toggleButton.setOnClickListener { toggleBubble() }

        chipHigh.setOnClickListener { setQuality("high") }
        chip720.setOnClickListener { setQuality("medium") }
        chip480.setOnClickListener { setQuality("low") }
        renderQuality(Prefs.quality(this))

        val audioSwitch = findViewById<Switch>(R.id.audioSwitch)
        audioSwitch.isChecked = Prefs.audioOnly(this)
        audioSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setAudioOnly(this, checked) }

        findViewById<View>(R.id.tileDownload).setOnClickListener { showHowTo() }
        findViewById<View>(R.id.tileStatus).setOnClickListener { openStatus() }

        findViewById<View>(R.id.rowTranscript).setOnClickListener {
            startActivity(Intent(this, TranscriptActivity::class.java))
        }
        findViewById<View>(R.id.rowHistory).setOnClickListener { openDownloads() }
        findViewById<View>(R.id.rowUpdate).setOnClickListener { checkForUpdate(true) }
        findViewById<View>(R.id.rowHowto).setOnClickListener { showHowTo() }

        findViewById<View>(R.id.navHome).setOnClickListener { /* already home */ }
        findViewById<View>(R.id.navStatus).setOnClickListener { openStatus() }
        findViewById<View>(R.id.navDownloads).setOnClickListener { openDownloads() }
        findViewById<View>(R.id.navUpdate).setOnClickListener { checkForUpdate(true) }

        findViewById<TextView>(R.id.updateSub).text =
            getString(R.string.row_update_sub) + "  •  v" + currentVersionName()

        maybeRequestNotifications()
        handleSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        renderRunningState()
        if (!checkedThisLaunch) {
            checkedThisLaunch = true
            checkForUpdate(userInitiated = false)
        }
    }

    // --- Floating button ---

    private fun toggleBubble() {
        if (OverlayService.isRunning) {
            stopService(Intent(this, OverlayService::class.java))
            mainHandler.postDelayed({ renderRunningState() }, 300L)
            toast("Floating button stopped")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("One quick permission")
                .setMessage("TapSave needs to show a floating button over other apps. Turn on \"display over other apps\" on the next screen, then tap Start again.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .show()
            return
        }
        startForegroundService(
            Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_SHOW }
        )
        mainHandler.postDelayed({ renderRunningState() }, 300L)
        toast("Floating button started")
    }

    private fun renderRunningState() {
        val running = OverlayService.isRunning
        toggleButton.text =
            getString(if (running) R.string.stop_button_active else R.string.start_button)
        statusText.text = when {
            running -> getString(R.string.status_running)
            Settings.canDrawOverlays(this) -> getString(R.string.status_ready)
            else -> getString(R.string.status_need_overlay)
        }
        statusText.setTextColor(getColor(if (running) R.color.success else R.color.muted))
    }

    // --- Quality chips ---

    private fun setQuality(value: String) {
        Prefs.setQuality(this, value)
        renderQuality(value)
    }

    private fun renderQuality(value: String) {
        style(chipHigh, value == "high")
        style(chip720, value == "medium")
        style(chip480, value == "low")
    }

    private fun style(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.chip_bg_selected else R.drawable.chip_bg)
        chip.setTextColor(getColor(if (selected) android.R.color.white else R.color.ink))
    }

    // --- Navigation ---

    private fun openStatus() = startActivity(Intent(this, StatusActivity::class.java))
    private fun openDownloads() = startActivity(Intent(this, DownloadHistoryActivity::class.java))

    private fun showHowTo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.row_howto_title))
            .setMessage(getString(R.string.how_to))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --- Update flow ---

    private fun checkForUpdate(userInitiated: Boolean) {
        if (userInitiated) toast("Checking for updates…")
        Thread {
            val latest = UpdateChecker.fetchLatest()
            val current = UpdateChecker.currentVersionCode(this)
            mainHandler.post {
                when {
                    latest == null ->
                        if (userInitiated) toast("Couldn't check right now. Try again later.")
                    latest.versionCode > current -> promptUpdate(latest)
                    else ->
                        if (userInitiated) toast("You're on the latest version.")
                }
            }
        }.start()
    }

    private fun promptUpdate(info: UpdateChecker.Info) {
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("A newer TapSave (${info.versionName}) is available. Update now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> startUpdate(info) }
            .show()
    }

    private fun startUpdate(info: UpdateChecker.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow updates")
                .setMessage("To install updates, allow TapSave to install apps on the next screen, then tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        toast("Downloading update…")
        Thread {
            val file = UpdateChecker.downloadApk(this, info.apkUrl)
            mainHandler.post {
                if (file == null) toast("Update download failed. Try again later.")
                else UpdateChecker.installApk(this, file)
            }
        }.start()
    }

    /** Kicks off a download when a link is shared into the app. */
    private fun handleSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = Prefs.firstUrl(shared)
        if (url == null) {
            toast("No link found in the shared text")
            return
        }
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_DOWNLOAD
            putExtra(OverlayService.EXTRA_URL, url)
        }
        startForegroundService(serviceIntent)
        toast("Downloading shared link…")
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun currentVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "1.0"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
