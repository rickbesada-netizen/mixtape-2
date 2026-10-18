package app.mixtape.core.net

import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.util.concurrent.atomic.AtomicLong

/**
 * YouTube sometimes answers "Sign in to confirm you're not a bot" for a while when one IP makes
 * a lot of requests (often after downloading many songs at once, or on a VPN). Nothing in the app
 * can bypass it; the right move is to back off and explain it clearly.
 */
object YouTubeLimits {
    private const val PAUSE_MS = 15L * 60 * 1000
    private val blockedUntil = AtomicLong(0)

    const val FRIENDLY_MESSAGE =
        "YouTube is asking this network to prove it isn't a bot, so it's blocking playback for now. " +
            "Switch between Wi-Fi and mobile data (or turn a VPN off/on) and try again, or wait 15-60 minutes. " +
            "Downloaded songs still play."

    fun isBotBlock(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 8) {
            if (e is SignInConfirmNotBotException || e is ReCaptchaException) return true
            val m = e.message ?: ""
            if (m.contains("not a bot") || m.contains("LOGIN_REQUIRED") || m.contains("confirm you")) return true
            e = e.cause
            depth++
        }
        return false
    }

    fun markBlocked() {
        blockedUntil.set(System.currentTimeMillis() + PAUSE_MS)
    }

    /** Clears the pause, e.g. after a request succeeds again. */
    fun clear() {
        blockedUntil.set(0)
    }

    fun isPaused(): Boolean = System.currentTimeMillis() < blockedUntil.get()
}
