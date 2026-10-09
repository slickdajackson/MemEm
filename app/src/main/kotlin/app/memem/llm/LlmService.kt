package app.memem.llm

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Messenger
import java.io.File

/** Separate process so a native Gemma abort does not take the keyboard down. */
class LlmService : Service() {
    private val thread = HandlerThread("memem-llm")
    private lateinit var host: EngineHost
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread.start()
        host = EngineHost(File(cacheDir, "litert"))
        val handler = object : Handler(thread.looper) {
            override fun handleMessage(msg: android.os.Message) {
                host.handle(msg)
            }
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        if (::host.isInitialized) host.close()
        thread.quitSafely()
        super.onDestroy()
    }
}
