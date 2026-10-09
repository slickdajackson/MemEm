package app.memem.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.memem.models.ModelCatalog

object SetupProbe {
    fun modelsReady(context: Context): Boolean {
        return ModelCatalog.ready(context, ModelCatalog.embed) &&
            ModelCatalog.ready(context, ModelCatalog.gemmaCpu)
    }

    fun keyboardEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    fun keyboardSelected(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        return current.substringBefore('/') == context.packageName
    }

    fun batteryFree(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}

object SystemLinks {
    fun appDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        launch(context, intent)
    }

    fun autostart(context: Context) {
        val primary = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        if (!launch(context, primary)) appDetails(context)
    }

    fun battery(context: Context) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (launch(context, request)) return
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (launch(context, list)) return
        appDetails(context)
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
