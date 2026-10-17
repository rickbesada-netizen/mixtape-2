# Mixtape

An Android music app that puts YouTube Music, YouTube and SoundCloud behind one search box, imports
your Spotify playlists (audio is matched from YouTube), and downloads songs for encrypted offline
playback inside the app.

## Get the APK

### Option A: GitHub Actions (no Android Studio needed)
1. Create a new GitHub repository and upload this folder's contents (keep the `.github` folder).
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push (or press
   **Run workflow**).
3. When it finishes (about 6–10 minutes), open the run and download **mixtape-debug-apk**
   (and **mixtape-release-apk**, a smaller minified build, if that step succeeded).
4. Copy the APK to your phone and install it (allow "Install unknown apps" for your file manager).

### Option B: Android Studio on Windows
1. File > Open > select this folder. Let Gradle sync (it downloads the Android SDK pieces it needs).
2. Plug in your phone with USB debugging on and press Run, or use
   Build > Build App Bundle(s) / APK(s) > Build APK(s).

Requirements: Android 8.0 (API 26) or newer.

## Spotify setup (optional)
Spotify's Web API only gives access to apps registered by each developer, so you use your own:
1. Go to https://developer.spotify.com/dashboard and create an app (tick **Web API**).
2. Add the redirect URI `mixtape-auth://callback`.
3. In the app: Library > Spotify, paste the app's **Client ID**, tap **Log in with Spotify**.

Spotify's current Development Mode rules: the app owner needs Premium, up to 5 users per app
(add them under User Management), and playlist tracks can only be read for playlists you own or
collaborate on. For anything else, export the playlist as CSV (e.g. with Exportify) and use
Library > Import CSV.

## How it fits together

```
ui/                Compose screens (search, library, playlists, Spotify, downloads, now playing)
player/            PlaybackService (Media3 MediaSessionService + ExoPlayer), PlayerConnection
                   (MediaController for the UI), MixtapeDataSource (routes every byte request)
source/            MusicSource interface; YouTubeSource and SoundCloudSource via NewPipeExtractor
spotify/           PKCE login + Web API client (metadata only)
matcher/           Spotify -> YouTube matching (title, artist, duration, version penalties)
download/          WorkManager downloads, AES-256-CTR encryption with an Android Keystore-wrapped key
data/              Room database (tracks, playlists, downloads, matches, history), settings
```

Playback flow: every queue item carries only a track key (`yt:<id>`, `sc:<url>`, `sp:<id>`). When
ExoPlayer opens `mixtape://track?k=<key>`, `MixtapeDataSource` asks `StreamResolver`, which returns
the encrypted offline file if one exists, otherwise a fresh stream URL (Spotify keys are matched
to YouTube first). Expired URLs (HTTP 403) are re-resolved automatically.

Offline files live in the app's private storage, encrypted. Copying them off the device gives you
unplayable data. This is obfuscation, not DRM: a rooted device can still extract the key.

## When YouTube playback breaks
YouTube changes its internals every few weeks. Fixes land in NewPipeExtractor quickly: bump the
version in `app/build.gradle.kts` (`com.github.teamnewpipe:NewPipeExtractor:vX.Y.Z`) to the
latest release at https://github.com/TeamNewPipe/NewPipeExtractor/releases and rebuild.

## Legal
Streaming or downloading from YouTube and SoundCloud outside their official apps is against their
terms of service, and Spotify's developer terms forbid using its API to facilitate downloading.
This project is for personal, educational use. NewPipeExtractor is GPLv3, so if you distribute
this app, its source must be GPLv3 too.
