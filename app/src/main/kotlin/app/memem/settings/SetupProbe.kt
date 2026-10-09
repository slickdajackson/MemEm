package app.memem.settings

import android.content.Context
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
}
