package app.mixtape.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mixtape.source.YouTubeAccount
import app.mixtape.ui.components.LocalContainer
import kotlinx.coroutines.launch

/**
 * Google's own sign-in page, inside Mixtape. Your password goes straight to Google; Mixtape only
 * keeps the YouTube sign-in cookie it gets back, on this phone.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(onDone: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    fun success() {
        if (finished) return
        finished = true
        Toast.makeText(context, "Signed in to YouTube. Bringing in your subscriptions...", Toast.LENGTH_LONG).show()
        scope.launch {
            container.youtubeAccount.refreshUser()
            runCatching { container.youtubeAccount.subscribedChannels() }
                .onSuccess { list ->
                    list.forEach { container.subscriptions.subscribe(it, mirror = false) }
                    Toast.makeText(context, "${list.size} subscriptions from YouTube added", Toast.LENGTH_LONG).show()
                }
        }
        onDone()
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to YouTube", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This is Google's own page. Mixtape never sees your password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(ctx).apply {
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Google refuses sign-in from in-app browsers, so look like Chrome.
                                settings.userAgentString = YouTubeAccount.USER_AGENT
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        loading = true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        loading = false
                                        CookieManager.getInstance().flush()
                                        if (url.startsWith(YouTubeAccount.ORIGIN) || url.startsWith("https://m.youtube.com")) {
                                            if (container.youtubeAccount.captureCookies()) success()
                                        }
                                    }
                                }
                                loadUrl(YouTubeAccount.SIGN_IN_URL)
                            }
                        },
                        onRelease = { it.stopLoading(); it.destroy() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
