package app.mixtape.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.mixtape.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

/**
 * Updates Mixtape from inside the app: checks the GitHub release for a newer build, downloads it
 * and hands it to Android's installer. Because every build is signed with the same key, the new
 * version installs over the old one and everything (downloads, playlists, settings) stays.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks at most every few hours unless [force]. Returns the update if there is one. */
    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val last = prefs.getLong("last_check", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            return@withContext (state.value as? UpdateState.Available)?.info
        }
        val req = Request.Builder().url("$BASE/version.json?t=${System.currentTimeMillis()}").build()
        val info = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't check for updates (${r.code})")
            val o = JSONObject(r.body?.string().orEmpty())
            UpdateInfo(o.optInt("versionCode"), o.optString("versionName"), o.optString("notes").trim())
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        if (info.versionCode > BuildConfig.VERSION_CODE) {
            if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Available(info)
            info
        } else {
            if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
            null
        }
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    /** Downloads the new APK, then opens the installer. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "Mixtape.apk")
        try {
            withContext(Dispatchers.IO) {
                _state.value = UpdateState.Downloading(info, 0f)
                val req = Request.Builder().url("$BASE/$APK_NAME?v=${info.versionCode}").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
                    val body = r.body ?: throw IOException("Download failed")
                    val total = body.contentLength()
                    file.outputStream().use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = done.toFloat() / total
                                if (p - lastShown > 0.01f) { lastShown = p; _state.value = UpdateState.Downloading(info, p) }
                            }
                        }
                    }
                }
            }
            // Remember the notes so the app can show "What's new" after it restarts.
            prefs.edit().putString("pending_notes", info.notes).putInt("pending_version", info.versionCode).apply()
            _state.value = UpdateState.ReadyToInstall(info, file)
            install(file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(info, e.message ?: "Download failed")
        }
    }

    /** Whether Android will let Mixtape open the installer (a one-time permission on Android 8+). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(file: File) {
        if (!canInstall()) {
            openInstallPermission()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** After an update: what's new in the version now running (shown once). */
    fun takeWhatsNew(): String? {
        val v = prefs.getInt("pending_version", 0)
        val notes = prefs.getString("pending_notes", null)
        if (v != 0 && v <= BuildConfig.VERSION_CODE && !notes.isNullOrBlank()) {
            prefs.edit().remove("pending_version").remove("pending_notes").apply()
            return notes
        }
        return null
    }

    private companion object {
        const val BASE = "https://github.com/rickbesada-netizen/mixtape-2/releases/download/latest"
        /** The build you install from the download link (debug-signed with the permanent key). */
        const val APK_NAME = "Mixtape.apk"
        const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    }
}
