package app.memem.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "memem-download"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gpu = intent?.getBooleanExtra(EXTRA_GPU, false) == true
        ensureChannel()
        val note = notification("Download startet", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, note)
        }
        scope.launch {
            try {
                val specs = buildList {
                    add(ModelCatalog.embed)
                    add(ModelCatalog.gemmaCpu)
                    if (gpu) add(ModelCatalog.gemmaGpu)
                }
                val downloader = Downloader()
                specs.forEachIndexed { index, spec ->
                    downloader.download(spec, ModelCatalog.file(this@ModelDownloadService, spec)) { done, total ->
                        val pct = if (total == 0L) 0 else ((done * 100) / total).toInt()
                        val text = "${spec.id} ${index + 1}/${specs.size}: $pct%"
                        notify(text, pct, 100)
                    }
                }
                notify("Modelle liegen bereit", 100, 100)
            } catch (t: Throwable) {
                notify(t.message ?: "Download fehlgeschlagen", 0, 0)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
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

    private fun notify(text: String, progress: Int, max: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification(text, progress, max))
    }

    private fun notification(text: String, progress: Int, max: Int): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MemEm")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(max > 0 && progress < max)
        if (max > 0) builder.setProgress(max, progress, false)
        return builder.build()
    }

    companion object {
        const val EXTRA_GPU = "gpu"
        private const val NOTE_ID = 41
    }
}
