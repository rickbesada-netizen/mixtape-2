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
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    fun success() {
        if (finished) return
        finished = true
        CookieManager.getInstance().flush()
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
                androidx.compose.material3.TextButton(onClick = { pasting = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Google says this app may not be secure? Sign in another way")
                }
                if (pasting) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pasting = false },
                        title = { Text("Sign in with your computer") },
                        text = {
                            Column {
                                Text(
                                    "1. On a computer, open youtube.com in Chrome and make sure you're signed in.\n" +
                                        "2. Press F12, open the Network tab, then refresh the page.\n" +
                                        "3. Click the first item (www.youtube.com), find \"cookie:\" under Request Headers, and copy its whole value.\n" +
                                        "4. Send it to your phone and paste it here.\n\n" +
                                        "This text is the same as being signed in, so don't share it with anyone.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = pasted, onValueChange = { pasted = it },
                                    label = { Text("Paste the cookie text") },
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(enabled = !checking && pasted.isNotBlank(), onClick = {
                                checking = true
                                scope.launch {
                                    val ok = container.youtubeAccount.signInWithCookies(pasted)
                                    checking = false
                                    if (ok) { pasting = false; success() }
                                    else Toast.makeText(context, "That didn't work. Copy the whole cookie value while signed in to youtube.com.", Toast.LENGTH_LONG).show()
                                }
                            }) { Text(if (checking) "Checking..." else "Sign in") }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { pasting = false }) { Text("Cancel") } },
                    )
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
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                // Google refuses sign-in from in-app browsers. It spots them by the
                                // "wv" in the browser name and by a header carrying the app's name,
                                // so present as plain Chrome and leave that header off.
                                settings.userAgentString = YouTubeAccount.chromeUserAgent(android.webkit.WebSettings.getDefaultUserAgent(ctx))
                                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                    androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                                }
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
