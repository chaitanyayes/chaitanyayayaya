package com.chaitanya.hingeopener

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.chaitanya.hingeopener.overlay.Bubble
import com.chaitanya.hingeopener.overlay.OverlayLifecycleOwner
import com.chaitanya.hingeopener.overlay.OverlayState
import com.chaitanya.hingeopener.overlay.ResultCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Foreground service that owns the MediaProjection session, watches which app is in front
 * (UsageStatsManager), and shows the bubble + result card overlays only while Hinge is in front.
 */
class BubbleService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var store: CaptureStore
    private lateinit var client: ClaudeClient
    private val state = OverlayState()
    private val owner = OverlayLifecycleOwner()

    private var capturer: ScreenCapturer? = null
    private var projectionGeneration = 0
    private var capturing = false

    private var lastForegroundPkg: String? = null
    private var overlaysShown = false
    private var cardWanted = false

    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var cardView: ComposeView? = null
    private lateinit var cardParams: WindowManager.LayoutParams
    private var cardX = 0f
    private var cardY = 0f

    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        store = CaptureStore(this)
        client = ClaudeClient(prefs)
        owner.start()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Must be foreground with the mediaProjection type BEFORE getMediaProjection (Android 14+).
                startForeground(
                    NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                startCaptureSession(code, data)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCaptureSession(code: Int, data: Intent) {
        capturer?.release()
        capturer = null
        val gen = ++projectionGeneration
        val projection = runCatching {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        }.getOrNull()
        if (projection == null) {
            toast("Couldn't start screen capture. Open Hinge Opener and tap Start again.")
            stopSelf()
            return
        }
        capturer = runCatching {
            ScreenCapturer(this, projection) { if (gen == projectionGeneration) onProjectionStopped() }
        }.getOrElse {
            toast("Couldn't start screen capture: ${it.message}")
            stopSelf()
            return
        }
        _running.value = true
        main.removeCallbacks(poll)
        main.post(poll)
    }

    /** Projection ended by the system (screen lock on some versions, "Stop sharing", etc.). */
    private fun onProjectionStopped() {
        projectionGeneration++
        capturer?.release()
        capturer = null
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hinge Opener stopped")
            .setContentText("Screen capture session ended. Tap to start again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ENDED_NOTIF_ID, n)
        stopSelf()
    }

    // ---------------------------------------------------------------- foreground detection

    private val poll = object : Runnable {
        override fun run() {
            checkForeground()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun checkForeground() {
        currentForegroundPackage()?.let { lastForegroundPkg = it }
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        setOverlaysVisible(interactive && lastForegroundPkg == prefs.hingePackage)
    }

    /** Most recent app to come to the foreground, or null if nothing changed in the window. */
    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val begin = end - if (lastForegroundPkg == null) 5 * 60_000L else 15_000L
        val events = usm.queryEvents(begin, end) ?: return null
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
        }
        return pkg
    }

    // ---------------------------------------------------------------- overlays

    private fun setOverlaysVisible(visible: Boolean) {
        if (visible == overlaysShown) return
        overlaysShown = visible
        if (visible) {
            attach(ensureBubble(), bubbleParams)
            if (cardWanted) attach(ensureCard(), cardParams)
        } else {
            detach(bubbleView)
            detach(cardView)
        }
    }

    private fun attach(view: View, params: WindowManager.LayoutParams) {
        if (!view.isAttachedToWindow && view.parent == null) {
            runCatching { wm.addView(view, params) }
                .onFailure { toast("Can't draw over apps. Check the overlay permission.") }
        }
    }

    private fun detach(view: View?) {
        if (view != null && (view.isAttachedToWindow || view.parent != null)) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        owner.attachTo(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { content() } }
    }

    private fun overlayParams(width: Int, height: Int, extraFlags: Int = 0) =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or extraFlags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun ensureBubble(): View {
        bubbleView?.let { return it }
        bubbleParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            x = screenW - dp(78)
            y = (screenH * 0.38f).roundToInt()
        }
        // The container intercepts every touch so the raw listener below sees the whole gesture.
        val view = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
        }
        view.addView(
            composeView { Bubble(state) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        view.setOnTouchListener(BubbleTouchListener())
        bubbleView = view
        return view
    }

    private fun ensureCard(): ComposeView {
        cardView?.let { return it }
        val width = (screenW * 0.92f).roundToInt()
        cardX = (screenW - width) / 2f
        cardY = screenH * 0.10f
        cardParams = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = cardX.roundToInt()
            y = cardY.roundToInt()
        }
        val maxHeightDp = (screenH * 0.72f / resources.displayMetrics.density).roundToInt()
        val view = composeView {
            ResultCard(
                state = state,
                maxHeightDp = maxHeightDp,
                onCopy = ::copy,
                onClear = ::clearAll,
                onClose = ::closeCard,
                onRetry = ::analyze,
                onDrag = ::dragCard,
            )
        }
        cardView = view
        return view
    }

    private fun showCard() {
        cardWanted = true
        if (overlaysShown) attach(ensureCard(), cardParams)
    }

    private fun closeCard() {
        cardWanted = false
        detach(cardView)
    }

    private fun dragCard(dx: Float, dy: Float) {
        val view = cardView ?: return
        cardX = (cardX + dx).coerceIn(-cardParams.width * 0.5f, screenW - cardParams.width * 0.5f)
        cardY = (cardY + dy).coerceIn(0f, screenH - dp(120).toFloat())
        cardParams.x = cardX.roundToInt()
        cardParams.y = cardY.roundToInt()
        if (view.isAttachedToWindow) wm.updateViewLayout(view, cardParams)
    }

    /** Raw-coordinate touch handling so the bubble tracks the finger exactly while its window moves. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var view: View? = null
        private val longPress = Runnable {
            longPressed = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onBubbleLongPress()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            view = v
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = (startX + dx).roundToInt().coerceIn(0, screenW - v.width)
                        bubbleParams.y = (startY + dy).roundToInt().coerceIn(0, screenH - v.height)
                        if (v.isAttachedToWindow) wm.updateViewLayout(v, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!dragging && !longPressed) onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- capture + analyze

    private fun onBubbleTap() {
        if (state.loading || capturing) return
        val cap = capturer ?: run {
            toast("Capture session ended. Open Hinge Opener and tap Start.")
            return
        }
        if (store.count >= CaptureStore.MAX_CAPTURES) {
            toast("Max ${CaptureStore.MAX_CAPTURES} captures. Long-press the bubble to analyze.")
            return
        }
        capturing = true
        // Hide our own windows so they aren't in the screenshot, then wait for a fresh frame.
        bubbleView?.visibility = View.INVISIBLE
        cardView?.visibility = View.INVISIBLE
        main.postDelayed({
            cap.capture(store.nextFile(), CaptureStore.MAX_WIDTH) { result ->
                bubbleView?.visibility = View.VISIBLE
                cardView?.visibility = View.VISIBLE
                capturing = false
                result.onSuccess {
                    store.add(it)
                    state.captureCount = store.count
                    state.captureTick++
                    bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    flash()
                }.onFailure { toast(it.message ?: "Capture failed.") }
            }
        }, HIDE_BEFORE_CAPTURE_MS)
    }

    private fun onBubbleLongPress() {
        if (state.loading || capturing) return
        if (store.count == 0) {
            if (state.result != null || state.error != null) showCard()
            else toast("Tap the bubble to capture her profile first.")
            return
        }
        analyze()
    }

    private fun analyze() {
        if (state.loading || store.count == 0) return
        state.loading = true
        state.error = null
        closeCard()
        scope.launch {
            try {
                val result = client.generate(store.all)
                state.result = result
                // Screenshots are deleted as soon as they've been analyzed.
                store.clear()
                state.captureCount = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Captures are kept on failure so Retry works; Clear deletes them.
                state.result = null
                state.error = e.message ?: "Something went wrong."
            } finally {
                state.loading = false
            }
            showCard()
        }
    }

    private fun clearAll() {
        store.clear()
        state.captureCount = 0
        state.result = null
        state.error = null
        closeCard()
        toast("Captures cleared")
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Hinge opener", text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied")
    }

    /** Subtle white flash so it's obvious a capture happened. */
    private fun flash() {
        val v = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        val p = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            // ≤ 0.8 window alpha keeps touches passing through to Hinge on Android 12+.
            alpha = 0.8f
        }
        runCatching { wm.addView(v, p) }.onFailure { return }
        v.animate().alpha(0.4f).setDuration(70).withEndAction {
            v.animate().alpha(0f).setDuration(220).withEndAction {
                runCatching { wm.removeView(v) }
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hinge Opener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the Hinge bubble is active"
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hinge Opener is on")
            .setContentText("The bubble shows up when Hinge is open.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        detach(bubbleView)
        detach(cardView)
        projectionGeneration++ // ignore the onStop callback our own release() triggers
        capturer?.release()
        capturer = null
        store.clear()
        owner.destroy()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.chaitanya.hingeopener.START"
        const val ACTION_STOP = "com.chaitanya.hingeopener.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1
        private const val ENDED_NOTIF_ID = 2
        private const val POLL_MS = 700L
        private const val LONG_PRESS_MS = 500L
        private const val HIDE_BEFORE_CAPTURE_MS = 250L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, BubbleService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_STOP))
        }
    }
}
