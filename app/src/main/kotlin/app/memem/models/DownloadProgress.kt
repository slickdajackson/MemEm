package app.memem.models

import kotlinx.coroutines.flow.MutableStateFlow

data class DownloadSnapshot(
    val active: Boolean = false,
    val text: String = "",
    val percent: Int = 0,
    val failed: Boolean = false,
)

object DownloadProgress {
    val flow = MutableStateFlow(DownloadSnapshot())

    @Volatile
    var wizardVisible: Boolean = false

    fun publish(snapshot: DownloadSnapshot) {
        flow.value = snapshot
    }
}
