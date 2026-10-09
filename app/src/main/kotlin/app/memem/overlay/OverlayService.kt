package app.memem.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.memem.R
import app.memem.a11y.MememAccessibilityService
import app.memem.debug.DebugLog
import app.memem.insert.Inserter
import app.memem.pipeline.MemeOption
import app.memem.pipeline.MemePipeline
import app.memem.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional floating dot. Window type and flags follow ChatLens OverlayService:
 * TYPE_APPLICATION_OVERLAY, not focusable, so WhatsApp stays the active window.
 * A tap starts meme suggestions for the open chat. Nothing is sent.
 */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var pipeline: MemePipeline
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inserter by lazy { Inserter(this) }
    private val log by lazy { DebugLog(this) }
    private var dot: TextView? = null
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private val previews = ArrayList<ImageView>(3)
    private var options: List<MemeOption> = emptyList()
    private var job: Job? = null
    private var dotX = 0
    private var dotY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pipeline = MemePipeline(this)
        pipeline.preload()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNote()
        if (dot == null) addDot()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        if (::pipeline.isInitialized) pipeline.remote.unbind()
        hidePanel()
        dot?.let { runCatching { windowManager.removeView(it) } }
        dot = null
        options.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        super.onDestroy()
    }

    private fun startForegroundNote() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Schwebender Punkt", NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this,
            5,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            6,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("MemEm: schwebender Punkt")
            .setContentText("Tippen schlägt Memes vor. Gesendet wird nichts.")
            .setContentIntent(open)
            .addAction(0, "Punkt entfernen", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, type)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    private fun addDot() {
        val size = dp(56)
        val view = TextView(this).apply {
            text = "M"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xFF111113.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF5C518.toInt())
            }
        }
        val params = baseParams(size, size)
        val area = screenArea()
        dotX = area.third - size - dp(8)
        dotY = area.second / 3
        params.x = dotX
        params.y = dotY
        view.setOnTouchListener(dragListener(params, size))
        windowManager.addView(view, params)
        dot = view
    }

    private fun dragListener(params: WindowManager.LayoutParams, size: Int): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = dp(6)
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    val area = screenArea()
                    dotX = (startX + dx).coerceIn(0, (area.third - size).coerceAtLeast(0))
                    dotY = (startY + dy).coerceIn(0, (area.second - size).coerceAtLeast(0))
                    params.x = dotX
                    params.y = dotY
                    dot?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel() else snap(params, size)
                    true
                }
                else -> false
            }
        }
    }

    private fun snap(params: WindowManager.LayoutParams, size: Int) {
        val area = screenArea()
        val margin = dp(8)
        dotX = if (dotX + size / 2 < area.third / 2) margin else area.third - size - margin
        params.x = dotX
        dot?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val label = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = getString(R.string.overlay_ready)
        }
        status = label
        val meme = Button(this).apply {
            text = getString(R.string.meme)
            setOnClickListener { runMeme() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previews.clear()
        repeat(3) { index ->
            val image = ImageView(this).apply {
                setBackgroundColor(0xFF2A2A2C.toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { pick(index) }
            }
            previews += image
            val lp = LinearLayout.LayoutParams(dp(84), dp(84))
            lp.marginEnd = dp(6)
            row.addView(image, lp)
        }
        val close = Button(this).apply {
            text = getString(R.string.overlay_close)
            setOnClickListener { hidePanel() }
        }
        box.addView(label)
        box.addView(meme)
        box.addView(row)
        box.addView(close)
        val params = baseParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        windowManager.addView(box, params)
        panel = box
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        status = null
        previews.clear()
    }

    private fun runMeme() {
        val service = MememAccessibilityService.instance
        if (service == null) {
            status?.text = getString(R.string.a11y_needed)
            return
        }
        val draft = service.draftText().trim()
        val context = service.recentTexts()
        val message = if (draft.isNotEmpty()) draft else context.takeLast(3).joinToString(" ")
        if (message.isBlank()) {
            status?.text = getString(R.string.overlay_empty)
            return
        }
        status?.text = getString(R.string.searching)
        job?.cancel()
        val usedContext = if (draft.isNotEmpty()) context else emptyList()
        job = scope.launch {
            val result = withContext(Dispatchers.Default) {
                pipeline.suggest(message, usedContext) { preview ->
                    scope.launch { show(preview) }
                }
            }
            show(result)
            status?.text = getString(R.string.overlay_pick)
        }
    }

    private fun show(next: List<MemeOption>) {
        if (panel == null) return
        val previous = options
        options = next
        previews.forEachIndexed { index, image ->
            image.setImageBitmap(next.getOrNull(index)?.bitmap)
        }
        previous.filter { old -> next.none { it.bitmap === old.bitmap } }.forEach { option ->
            if (!option.bitmap.isRecycled) option.bitmap.recycle()
        }
    }

    private fun pick(index: Int) {
        val option = options.getOrNull(index) ?: return
        val service = MememAccessibilityService.instance
        if (service == null) {
            status?.text = getString(R.string.a11y_needed)
            return
        }
        val outcome = inserter.insertViaAccessibility(option.file) { service.pasteImage(restoreOnFailure = true) }
        log.event(
            mapOf(
                "kind" to "insert",
                "via" to "overlay",
                "step" to outcome.step?.name,
                "ok" to outcome.reportedSuccess,
                "hint" to outcome.clipboardHint,
                "channel" to outcome.pasteChannel,
                "template" to option.template.id,
            ),
        )
        status?.text = when {
            outcome.pasteChannel == "a11y" && outcome.reportedSuccess -> getString(R.string.a11y_pasted)
            outcome.clipboardHint -> getString(R.string.clipboard_hint)
            outcome.reportedSuccess -> getString(R.string.shared)
            else -> getString(R.string.insert_failed)
        }
    }

    private fun baseParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screenArea(): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        return Triple(0, metrics.heightPixels, metrics.widthPixels)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "app.memem.OVERLAY_STOP"
        private const val CHANNEL = "overlay"
        private const val NOTE_ID = 47

        fun canDraw(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canDraw(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
