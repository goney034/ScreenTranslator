package app.screenbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a draggable bubble on screen. Tapping it grabs the screen, sends it to the chosen AI
 * and draws the translations over the original text. Tapping the screen (or the bubble) clears them.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "app.screenbubble.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val MAX_SIDE = 1600
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = LlmClient()
    private val lock = Any()

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null
    private var job: Job? = null
    private var screen = Point(0, 0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // already running
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        startCapture(mp)
        showBubble()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun realSize(): Point {
        val point = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            point.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
        }
        return point
    }

    private fun startCapture(mp: MediaProjection) {
        screen = realSize()
        val imageReader = ImageReader.newInstance(screen.x, screen.y, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = image
                }
            }
        }, handler)
        reader = imageReader
        display = mp.createVirtualDisplay(
            "screen-bubble", screen.x, screen.y, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showBubble() {
        val view = TextView(this).apply {
            text = getString(R.string.bubble_label)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61565C0"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
        }
        val params = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = screen.y / 3
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (moved || dx * dx + dy * dy > dp(10) * dp(10)) {
                            moved = true
                            params.x = (startX + dx).roundToInt()
                            params.y = (startY + dy).roundToInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
                }
                return true
            }
        })
        bubble = view
        bubbleParams = params
        wm.addView(view, params)
    }

    private fun setBubbleLabel(text: String) {
        bubble?.text = text
    }

    private fun onBubbleTap() {
        if (job?.isActive == true) return
        if (overlay != null) {
            hideOverlay()
            return
        }
        val prefs = Prefs(this)
        val cfg = prefs.config()
        if (cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
            toast(getString(R.string.error_no_key))
            return
        }
        setBubbleLabel("…")
        bubble?.visibility = View.INVISIBLE // keep the bubble out of the screenshot
        job = scope.launch {
            try {
                kotlinx.coroutines.delay(250) // let the screen settle so the next frame excludes the bubble
                val bitmap = grabFrame()
                bubble?.visibility = View.VISIBLE
                if (bitmap == null) {
                    toast(getString(R.string.error_no_frame))
                    return@launch
                }
                val jpeg = withContext(Dispatchers.Default) { encode(bitmap) }
                val blocks = client.translate(cfg, jpeg, prefs.direction)
                if (blocks.isEmpty()) toast(getString(R.string.nothing_to_translate)) else showOverlay(blocks)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LlmException) {
                toast(if (e.code > 0) "HTTP ${e.code}: ${e.message}" else e.message.orEmpty())
            } catch (e: Exception) {
                toast(getString(R.string.error_generic, e.javaClass.simpleName))
            } finally {
                bubble?.visibility = View.VISIBLE
                setBubbleLabel(getString(if (overlay != null) R.string.bubble_clear else R.string.bubble_label))
            }
        }
    }

    private fun grabFrame(): Bitmap? {
        val image = synchronized(lock) { latest } ?: return null
        return try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val wide = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            if (rowPixels == image.width) wide else Bitmap.createBitmap(wide, 0, 0, image.width, image.height).also { wide.recycle() }
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val scale = min(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun showOverlay(blocks: List<Block>) {
        hideOverlay()
        val root = FrameLayout(this)
        val minHeight = dp(26)
        val loose = ArrayList<Block>()
        for (block in blocks) {
            if (!block.hasBox) {
                loose.add(block)
                continue
            }
            val left = (block.left!! * screen.x).roundToInt()
            val top = (block.top!! * screen.y).roundToInt()
            val width = max(dp(40), ((block.right!! - block.left) * screen.x).roundToInt())
            val height = max(minHeight, ((block.bottom!! - block.top) * screen.y).roundToInt())
            val view = translationView(block.translation, small = true)
            val lp = FrameLayout.LayoutParams(width, height)
            lp.leftMargin = min(left, max(0, screen.x - width))
            lp.topMargin = min(top, max(0, screen.y - height))
            root.addView(view, lp)
        }
        if (loose.isNotEmpty()) {
            val panel = translationView(loose.joinToString("\n\n") { it.translation }, small = false)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM
            lp.setMargins(dp(12), 0, dp(12), dp(96))
            root.addView(panel, lp)
        }
        root.setOnClickListener { hideOverlay() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, params)
        overlay = root
        bringBubbleToFront()
    }

    private fun translationView(text: String, small: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setPadding(dp(4), dp(2), dp(4), dp(2))
        gravity = if (small) Gravity.CENTER_VERTICAL else Gravity.START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EB101820"))
            cornerRadius = dp(4).toFloat()
        }
        if (small) {
            setAutoSizeTextTypeUniformWithConfiguration(8, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        } else {
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun bringBubbleToFront() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        runCatching {
            wm.removeView(view)
            wm.addView(view, params)
        }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        setBubbleLabel(getString(R.string.bubble_label))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        hideOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        reader?.close()
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
