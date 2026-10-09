package app.memem.models

import android.content.Context
import java.io.File

data class ModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

object ModelCatalog {
    val embed = ModelSpec(
        id = "embed",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        url = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        bytes = 164_626_432L,
    )
    val gemmaCpu = ModelSpec(
        id = "gemma-cpu",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        bytes = 2_588_147_712L,
    )

    /** Optional. Funnier than E2B and about twice as slow. Off unless the user turns it on. */
    val gemmaE4b = ModelSpec(
        id = "gemma-e4b",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        bytes = 3_659_530_240L,
    )

    fun file(context: Context, spec: ModelSpec): File = File(context.filesDir, "models/${spec.fileName}")

    fun ready(context: Context, spec: ModelSpec): Boolean {
        val file = file(context, spec)
        return file.isFile && file.length() == spec.bytes
    }
}
