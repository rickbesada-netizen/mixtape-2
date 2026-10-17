package app.mixtape.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.crypto.SecretKey

/** Decrypts a downloaded video and saves it as a normal .mp4/.webm the phone's gallery can see. */
object VideoExport {

    /** Returns a short description of where the file went. */
    suspend fun saveToPhone(context: Context, encrypted: File, key: SecretKey, title: String): String =
        withContext(Dispatchers.IO) {
            val webm = isWebm(encrypted, key)
            val ext = if (webm) "webm" else "mp4"
            val mime = if (webm) "video/webm" else "video/mp4"
            val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifBlank { "video" } + "." + ext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mixtape")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Couldn't create the video file")
                try {
                    resolver.openOutputStream(uri)?.use { decryptTo(encrypted, key, it) }
                        ?: throw IOException("Couldn't write the video file")
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                "Saved to Movies/Mixtape/$name"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Mixtape").apply { mkdirs() }
                val out = File(dir, name)
                out.outputStream().use { decryptTo(encrypted, key, it) }
                "Saved to ${out.absolutePath}"
            }
        }

    /** WebM/Matroska files start with the EBML magic bytes 1A 45 DF A3. */
    fun isWebm(encrypted: File, key: SecretKey): Boolean =
        EncryptedFileReader(encrypted, key).use { r ->
            r.seek(0)
            val head = ByteArray(4)
            r.read(head, 0, 4) == 4 &&
                head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        }

    private fun decryptTo(encrypted: File, key: SecretKey, out: OutputStream) {
        EncryptedFileReader(encrypted, key).use { reader ->
            reader.seek(0)
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
        }
    }
}
