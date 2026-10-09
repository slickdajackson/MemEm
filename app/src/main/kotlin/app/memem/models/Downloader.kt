package app.memem.models

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class Downloader {
    fun download(spec: ModelSpec, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() == spec.bytes && sha256(dest).equals(spec.sha256, ignoreCase = true)) {
            onProgress(spec.bytes, spec.bytes)
            return
        }
        val part = File(dest.absolutePath + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > spec.bytes) {
            part.delete()
            offset = 0L
        }
        var guard = 0
        while (offset < spec.bytes && guard < 8) {
            guard += 1
            val conn = open(spec.url, offset)
            val code = conn.responseCode
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                part.delete()
                offset = 0L
                continue
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(180)
                conn.disconnect()
                error("Download ${spec.id} HTTP $code $err")
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, code == 206).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onProgress(offset, spec.bytes)
                    }
                }
            }
            conn.disconnect()
        }
        if (part.length() != spec.bytes) error("Download ${spec.id} unvollständig: ${part.length()}")
        val hash = sha256(part)
        if (!hash.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            error("SHA-256 von ${spec.id} stimmt nicht")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "MemEm/0.2.5")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        conn.connect()
        return conn
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
