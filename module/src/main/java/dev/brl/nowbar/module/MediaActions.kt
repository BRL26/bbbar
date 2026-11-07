package dev.brl.nowbar.module

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Transport control for a media item.
 *
 * The pill gets its media session token from the notification it came from, and a
 * [MediaController] on that token exposes the same controls Android's own player does —
 * including seek/shuffle state — without any media permission of our own.
 */
object MediaActions {

    private fun controller(context: Context, token: MediaSession.Token?): MediaController? {
        return try {
            token?.let { MediaController(context, it) }
        } catch (t: Throwable) {
            Log.w("media controller unavailable", t)
            null
        }
    }

    fun playPause(context: Context, token: MediaSession.Token?) {
        try {
            val c = controller(context, token) ?: return
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) c.transportControls.pause() else c.transportControls.play()
            Log.i("media: ${if (playing) "pause" else "play"}")
        } catch (t: Throwable) {
            Log.w("media playPause failed", t)
        }
    }

    fun next(context: Context, token: MediaSession.Token?) {
        try {
            controller(context, token)?.transportControls?.skipToNext()
            Log.i("media: next")
        } catch (t: Throwable) {
            Log.w("media next failed", t)
        }
    }

    fun previous(context: Context, token: MediaSession.Token?) {
        try {
            controller(context, token)?.transportControls?.skipToPrevious()
            Log.i("media: previous")
        } catch (t: Throwable) {
            Log.w("media previous failed", t)
        }
    }

    /**
     * Jumps the player to [positionMs]. Used by the expanded card's seek slider: dragging it only
     * previews the position, the seek lands when the finger lifts.
     */
    fun seek(context: Context, token: MediaSession.Token?, positionMs: Long) {
        try {
            val c = controller(context, token) ?: return
            c.transportControls.seekTo(positionMs.coerceAtLeast(0L))
            Log.i("media: seek to ${positionMs}ms")
        } catch (t: Throwable) {
            Log.w("media seek failed", t)
        }
    }
}
