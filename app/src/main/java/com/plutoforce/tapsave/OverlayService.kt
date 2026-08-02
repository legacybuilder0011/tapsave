package com.plutoforce.tapsave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Foreground service that shows the draggable floating button and runs the
 * download. Tapping the button reads the copied link (via a transparent helper
 * activity) and starts a download, showing a spinner and then a check mark.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.plutoforce.tapsave.DOWNLOAD"
        const val ACTION_SAVE_STATUS = "com.plutoforce.tapsave.SAVE_STATUS"
        const val ACTION_SAVE_STATUS_FILE = "com.plutoforce.tapsave.SAVE_STATUS_FILE"
        const val ACTION_SHOW = "com.plutoforce.tapsave.SHOW"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_HAD_LINK = "had_link"

        private const val CHANNEL_ID = "tapsave_overlay"
        private const val NOTIFICATION_ID = 42

        /** Whether the floating button is currently showing (for the UI toggle). */
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var progressView: ProgressBar? = null
    private var percentView: TextView? = null

    @Volatile
    private var isDownloading = false
    private var prepareStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
        isRunning = true
        // Wake the backend now so the first link download isn't stuck on a cold start.
        VideoDownloader.warmUp(Prefs.backend(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showBubble()
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) startDownload(url)
            }
            ACTION_SAVE_STATUS ->
                startStatusSave(intent.getBooleanExtra(EXTRA_HAD_LINK, false))
            ACTION_SAVE_STATUS_FILE -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (!path.isNullOrBlank()) {
                    val file = java.io.File(path)
                    if (file.isFile) {
                        saveStatus(StatusSaver.Status(file, file.name.lowercase().endsWith(".mp4")))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.bubble, null)
        iconView = view.findViewById(R.id.bubbleIcon)
        progressView = view.findViewById(R.id.bubbleProgress)
        percentView = view.findViewById(R.id.bubblePercent)

        val size = dp(44)
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        view.setOnTouchListener(DragOrTapListener(params))
        runCatching { windowManager.addView(view, params) }
        bubble = view
        bubbleParams = params
    }

    private fun startDownload(url: String) {
        if (isDownloading) {
            toast("Already downloading…")
            return
        }
        val backend = Prefs.backend(this)
        if (backend.isBlank()) {
            toast("Open TapSave and set the server address first")
            return
        }

        val audio = Prefs.audioOnly(this)
        val quality = Prefs.quality(this)

        isDownloading = true
        setPreparing()
        toast(if (audio) "Downloading audio…" else "Downloading…")

        Thread {
            val result = VideoDownloader.download(
                applicationContext, backend, url, audio, quality
            ) { pct -> handler.post { setPercent(pct) } }
            handler.post {
                isDownloading = false
                if (result.ok) {
                    // Only remember it once it actually worked. Marking it up
                    // front meant a failed attempt could never be retried — the
                    // next tap treated the link as already handled.
                    Prefs.setLastDownloadedUrl(this, url)
                    if (result.parts.isNotEmpty()) {
                        // A carousel: every slide belongs in the list.
                        result.parts.forEach { part ->
                            DownloadStore.add(this, part.name, url, part.uri, part.audio)
                        }
                    } else if (result.uri != null && result.name != null) {
                        DownloadStore.add(this, result.name, url, result.uri, result.audio)
                    }
                    showSuccessThenIdle()
                } else {
                    setIdle()
                }
                toast(result.message)
            }
        }.start()
    }

    /**
     * Saves the WhatsApp status being viewed. This is a local file copy, so it
     * finishes almost instantly — no server involved. If we can't be sure which
     * status is on screen (WhatsApp pre-downloads some in the background), a
     * quick picker of the newest few is shown instead of guessing wrong.
     */
    private fun startStatusSave(hadUnsupportedLink: Boolean = false) {
        if (isDownloading) {
            toast("Busy — try again in a second")
            return
        }
        if (hadUnsupportedLink && StatusSaver.newest() == null) {
            toast("Already saved. Copy a new link, or open a status in WhatsApp.")
            return
        }
        if (!StatusSaver.hasAccess(this)) {
            toast("Open TapSave → WhatsApp Status Saver and allow file access first")
            return
        }
        if (StatusSaver.newest() == null) {
            toast("No status found — open a status in WhatsApp, then tap")
            return
        }

        val certain = StatusSaver.confidentlyViewed()
        if (certain != null) {
            saveStatus(certain)
        } else {
            // Ambiguous — let the user tap the one they just watched.
            startActivity(
                Intent(this, StatusPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }
    }

    /** Copies one status into the gallery, showing progress then a check mark. */
    private fun saveStatus(status: StatusSaver.Status) {
        if (isDownloading) return
        isDownloading = true
        setPreparing()

        Thread {
            val ok = StatusSaver.save(applicationContext, status) { pct ->
                handler.post { setPercent(pct) }
            }
            handler.post {
                isDownloading = false
                if (ok) showSuccessThenIdle() else setIdle()
                toast(
                    when {
                        !ok -> "Couldn't save that status"
                        status.isVideo -> "Video saved to Gallery"
                        else -> "Photo saved to Gallery"
                    }
                )
            }
        }.start()
    }

    /**
     * Connecting / preparing. Shows a spinner plus the seconds elapsed, so a
     * slow extraction still looks alive instead of frozen.
     */
    private fun setPreparing() {
        iconView?.visibility = View.GONE
        progressView?.visibility = View.VISIBLE
        percentView?.visibility = View.GONE
        prepareStartedAt = System.currentTimeMillis()
        handler.removeCallbacks(elapsedTicker)
        handler.postDelayed(elapsedTicker, 3000L)
    }

    /** Counts up next to the spinner until real progress arrives. */
    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (!isDownloading) return
            val seconds = ((System.currentTimeMillis() - prepareStartedAt) / 1000L).toInt()
            progressView?.visibility = View.GONE
            iconView?.visibility = View.GONE
            percentView?.visibility = View.VISIBLE
            percentView?.text = "${seconds}s"
            handler.postDelayed(this, 1000L)
        }
    }

    private fun setPercent(pct: Int) {
        handler.removeCallbacks(elapsedTicker)
        progressView?.visibility = View.GONE
        iconView?.visibility = View.GONE
        percentView?.visibility = View.VISIBLE
        percentView?.text = "$pct%"
    }

    private fun setIdle() {
        handler.removeCallbacks(elapsedTicker)
        percentView?.visibility = View.GONE
        progressView?.visibility = View.GONE
        iconView?.setImageResource(R.drawable.ic_download)
        iconView?.visibility = View.VISIBLE
    }

    private fun showSuccessThenIdle() {
        handler.removeCallbacks(elapsedTicker)
        percentView?.visibility = View.GONE
        progressView?.visibility = View.GONE
        iconView?.setImageResource(R.drawable.ic_check)
        iconView?.visibility = View.VISIBLE
        handler.postDelayed({ setIdle() }, 1600L)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TapSave button",
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("TapSave is running")
            .setContentText("Tap the button to save the status you're viewing, or a copied video link")
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class DragOrTapListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downTime = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTime = System.currentTimeMillis()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                    val duration = System.currentTimeMillis() - downTime
                    if (moved < dp(12) && duration < 600L) onTap()
                    return true
                }
            }
            return false
        }
    }

    private fun onTap() {
        if (isDownloading) {
            toast("Already downloading…")
            return
        }
        // A transparent activity has window focus, so it can read the clipboard
        // (background services cannot on Android 10+). It lives in its own task
        // (empty taskAffinity) and is excluded from recents with no animation, so
        // after it reads the clipboard the user returns to the app they were in
        // instead of the TapSave screen.
        val intent = Intent(this, ClipboardTapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        startActivity(intent)
    }
}
