package app.memem.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.memem.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = DownloadNotes.CHANNEL
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val note = DownloadNotes.running(this, "Download startet", 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(DownloadNotes.ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotes.ID, note)
        }
        DownloadProgress.publish(DownloadSnapshot(active = true, text = "Download startet", percent = 0))
        if (job?.isActive == true) return START_REDELIVER_INTENT
        job = scope.launch {
            var failed: String? = null
            try {
                val specs = listOf(ModelCatalog.embed, ModelCatalog.gemmaCpu)
                val downloader = Downloader()
                specs.forEachIndexed { index, spec ->
                    downloader.download(spec, ModelCatalog.file(this@ModelDownloadService, spec)) { done, total ->
                        val pct = if (total == 0L) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
                        val text = "${spec.id} ${index + 1}/${specs.size}: $pct%"
                        DownloadProgress.publish(DownloadSnapshot(active = pct < 100, text = text, percent = pct))
                        if (pct < 100) notify(DownloadNotes.running(this@ModelDownloadService, text, pct))
                    }
                }
                DownloadProgress.publish(DownloadSnapshot(active = false, text = "Modelle liegen bereit", percent = 100))
            } catch (t: Throwable) {
                failed = (t.message ?: "Download fehlgeschlagen") + " Teildatei bleibt, Fortsetzen lädt weiter."
                DownloadProgress.publish(DownloadSnapshot(active = false, text = failed, percent = 0, failed = true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val manager = getSystemService(NotificationManager::class.java)
                    val error = failed
                    if (DownloadProgress.wizardVisible) {
                        manager.cancel(DownloadNotes.ID)
                    } else if (error == null) {
                        manager.notify(DownloadNotes.ID, DownloadNotes.done(this@ModelDownloadService))
                    } else {
                        manager.notify(DownloadNotes.ID, DownloadNotes.failed(this@ModelDownloadService, error))
                    }
                    stopSelf(startId)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Modell-Download", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun notify(note: Notification) {
        getSystemService(NotificationManager::class.java).notify(DownloadNotes.ID, note)
    }
}

internal object DownloadNotes {
    const val CHANNEL = "memem-download"
    const val ID = 41

    fun running(context: Context, text: String, percent: Int): Notification {
        return base(context, text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 99), percent <= 0)
            .build()
    }

    fun done(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            9,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return base(context, "Modelle liegen bereit")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .setContentIntent(open)
            .build()
    }

    fun failed(context: Context, text: String): Notification {
        val again = Intent(context, ModelDownloadService::class.java)
        val pending = PendingIntent.getService(
            context,
            8,
            again,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return base(context, text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(0, "Fortsetzen", pending)
            .build()
    }

    private fun base(context: Context, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("MemEm")
            .setContentText(text)
            .setOnlyAlertOnce(true)
    }
}
