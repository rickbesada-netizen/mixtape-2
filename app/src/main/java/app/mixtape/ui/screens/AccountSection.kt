package app.mixtape.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.SectionTitle
import kotlinx.coroutines.launch

/** Log in / sign up with Supabase, and sync controls. Shown at the top of Settings. */
@Composable
fun AccountSection() {
    val container = LocalContainer.current
    val cloud = container.cloud
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val status by cloud.status.collectAsStateWithLifecycle()
    val syncing by cloud.syncing.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(!cloud.isConfigured) }
    var serverUrl by remember { mutableStateOf(cloud.url) }
    var serverKey by remember { mutableStateOf(cloud.anonKey) }

    SectionTitle("Account")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val a = account
        if (a != null) {
            Text("Logged in as ${a.email}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Your liked songs, playlists and download list sync to your account. On a new phone, " +
                    "log in and use Downloads > Restore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !syncing, onClick = {
                    scope.launch { runCatching { cloud.sync() }.onFailure { message = it.message } }
                }) { Text("Sync now") }
                OutlinedButton(onClick = { cloud.signOut() }) { Text("Log out") }
                if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Text(
                "Log in to keep your playlists, liked songs and downloads list in the cloud and on every phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val ready = email.contains("@") && password.length >= 6 && !busy
                Button(enabled = ready, onClick = {
                    busy = true; message = null
                    scope.launch {
                        runCatching { cloud.signIn(email, password) }
                            .onSuccess { password = "" }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Log in") }
                FilledTonalButton(enabled = ready, onClick = {
                    busy = true; message = null
                    scope.launch {
                        runCatching { cloud.signUp(email, password) }
                            .onSuccess { msg -> message = msg; if (msg == null) password = "" }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Create account") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (password.isNotEmpty() && password.length < 6) {
                Text("Password needs at least 6 characters", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { showServer = !showServer }) {
            Text(if (showServer) "Hide server settings" else "Server settings")
        }
        if (showServer) {
            Text(
                if (cloud.builtInServer) "This build already includes a server. Only change this to use a different Supabase project."
                else "Paste your Supabase project URL and anon (public) key from Project Settings > API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it }, singleLine = true,
                label = { Text("Supabase URL (https://xxxx.supabase.co)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = serverKey, onValueChange = { serverKey = it }, singleLine = true,
                label = { Text("Anon public key") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                cloud.setServer(serverUrl, serverKey)
                message = if (cloud.isConfigured) null else "That URL should start with https://"
                if (cloud.isConfigured) showServer = false
            }) { Text("Save server") }
        }
    }
}
