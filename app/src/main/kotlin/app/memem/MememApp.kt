package app.memem

import android.app.Application
import app.memem.settings.AppLanguage

class MememApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.ensure(this)
    }
}
