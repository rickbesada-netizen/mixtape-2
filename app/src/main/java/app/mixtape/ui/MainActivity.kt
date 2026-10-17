package app.mixtape.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mixtape.MixtapeApp
import app.mixtape.ui.theme.MixtapeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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

        setContent {
            MixtapeTheme {
                MixtapeAppUi(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
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
