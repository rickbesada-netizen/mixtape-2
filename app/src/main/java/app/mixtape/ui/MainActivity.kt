package app.mixtape.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mixtape.MixtapeApp
import app.mixtape.player.Pip
import app.mixtape.ui.theme.MixtapeTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MixtapeApp).container
        container.player.connect()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        addOnPictureInPictureModeChangedListener { info -> Pip.inPip.value = info.isInPictureInPictureMode }

        setContent {
            MixtapeTheme {
                MixtapeAppUi(container)
            }
        }
    }

    /** Android 8-11: going home while a video plays pops it into a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Pip.videoPlaying) Pip.enter(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!text.isNullOrBlank()) {
                (application as MixtapeApp).container.pendingLink.value = text
            }
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val data = intent.data ?: return
        if (data.scheme == "mixtape-auth") {
            val container = (application as MixtapeApp).container
            lifecycleScope.launch {
                runCatching { container.spotifyAuth.handleRedirect(data) }
                    .onSuccess { Toast.makeText(this@MainActivity, "Connected to Spotify", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
            }
            setIntent(Intent(intent).setData(null))
        }
    }
}
