package app.mixtape.ui.components

import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.mixtape.player.CastSupport
import com.google.android.gms.cast.framework.CastButtonFactory

/** The standard Cast icon. Hidden on phones without Google Play services. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val available = remember { CastSupport.context(context) != null }
    if (!available) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // The Cast device picker is an AppCompat dialog, so give the button an AppCompat theme.
            val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            MediaRouteButton(themed).also { runCatching { CastButtonFactory.setUpMediaRouteButton(themed.applicationContext, it) } }
        },
    )
}

/** True while the phone is connected to a Chromecast. */
@Composable
fun rememberCastConnected(): Boolean {
    val context = LocalContext.current
    val castContext = remember { CastSupport.context(context) }
    var connected by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(castContext?.castState == com.google.android.gms.cast.framework.CastState.CONNECTED)
    }
    androidx.compose.runtime.DisposableEffect(castContext) {
        val l = com.google.android.gms.cast.framework.CastStateListener { state ->
            connected = state == com.google.android.gms.cast.framework.CastState.CONNECTED
        }
        castContext?.addCastStateListener(l)
        onDispose { castContext?.removeCastStateListener(l) }
    }
    return connected
}
