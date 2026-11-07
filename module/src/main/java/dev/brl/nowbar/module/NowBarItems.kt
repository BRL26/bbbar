package dev.brl.nowbar.module

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** What a pill is currently showing. */
enum class ItemKind { MEDIA, TIMER, ALARM, LIVE }

/**
 * One thing the pill can display. Everything the renderer needs is here: no view lookups, no
 * notification objects kept alive longer than the update that produced them.
 *
 * `progress` is 0..1 or negative for "no bar"; `playing` drives the play/pause glyph; `token` is
 * the media session, which is what gives the expanded card real transport controls.
 */
data class NowBarItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val subtitle: String,
    val progress: Float = -1f,
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val token: MediaSession.Token? = null,
    val packageName: String? = null,
    val control: Control? = null,
    /** Buttons the expanded card offers: the source app's own notification actions. */
    val actions: List<Action> = emptyList(),
    /**
     * Playback position and length in ms, so an expanded media card can draw a real seek slider
     * with the elapsed time beside it. Both are 0 when the player does not report a duration.
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    data class Control(val pendingIntent: PendingIntent, val label: String)

    /**
     * One control from the notification that produced the card - the Clock app's Pause / Add 1
     * min, a live update's own button. Sent as the source app's PendingIntent, so the app does the
     * work and the pill needs no permission of its own.
     */
    data class Action(val label: String, val pendingIntent: PendingIntent)
}

/**
 * The live items held in one place so the three surfaces always agree.
 *
 * Sources push in from [NowBarNotifications] and the service pulls out: it registers [onChange]
 * and re-renders the visible pill. Items come back in the owner's ranking order, with switched-off
 * sources skipped.
 */
object NowBarState {

    private val items = ConcurrentHashMap<ItemKind, NowBarItem>()

    /**
     * Cards faked from the settings app, so an activity can be judged on the real pill instead of
     * only in a preview row. A fake wins over whatever the notification listener finds for that
     * kind, and survives the listener's rebuilds.
     */
    private val simulated = ConcurrentHashMap<ItemKind, NowBarItem>()

    /** Called on every change; the service re-renders whichever pill is on screen. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun put(kind: ItemKind, item: NowBarItem?) {
        try {
            if (item == null) items.remove(kind) else items[kind] = item
            onChange?.invoke()
        } catch (t: Throwable) {
            Log.w("NowBarState.put failed", t)
        }
    }

    fun remove(kind: ItemKind) = put(kind, null)

    /** Shows [item] on the real pill until the same kind is switched back off. */
    fun putSimulated(item: NowBarItem) {
        try {
            simulated[item.kind] = item
            onChange?.invoke()
        } catch (t: Throwable) {
            Log.w("NowBarState.putSimulated failed", t)
        }
    }

    fun removeSimulated(kind: ItemKind) {
        try {
            if (simulated.remove(kind) != null) onChange?.invoke()
        } catch (t: Throwable) {
            Log.w("NowBarState.removeSimulated failed", t)
        }
    }

    /**
     * The same fakes the settings screen switches on, reachable from adb - handy when the pill is on
     * a screen the UI cannot be reached from:
     *
     *   adb shell am broadcast -a dev.brl.nowbar.module.SET \
     *     -n dev.brl.nowbar.module/.TuningReceiver --es fake media
     *   adb shell am broadcast ... --es fake none
     */
    fun applyFake(which: String) {
        try {
            when (which.lowercase()) {
                "media" -> putSimulated(
                    NowBarItem(
                        id = "fake|media",
                        kind = ItemKind.MEDIA,
                        title = "Test track",
                        subtitle = "Test artist",
                        progress = 0.35f,
                        playing = true,
                        positionMs = 70_000L,
                        durationMs = 200_000L
                    )
                )
                "timer" -> putSimulated(
                    NowBarItem(
                        id = "fake|timer",
                        kind = ItemKind.TIMER,
                        title = "Test timer",
                        subtitle = "5:00",
                        progress = 0.4f
                    )
                )
                "live" -> putSimulated(
                    NowBarItem(
                        id = "fake|live",
                        kind = ItemKind.LIVE,
                        title = "Ride arriving",
                        subtitle = "12 min",
                        progress = 0.2f
                    )
                )
                else -> {
                    removeSimulated(ItemKind.MEDIA)
                    removeSimulated(ItemKind.TIMER)
                    removeSimulated(ItemKind.LIVE)
                }
            }
            Log.i("fake card: $which")
            onChange?.invoke()
        } catch (t: Throwable) {
            Log.w("applyFake failed", t)
        }
    }

    fun clearSimulated() {
        try {
            if (simulated.isEmpty()) return
            simulated.clear()
            onChange?.invoke()
        } catch (t: Throwable) {
            Log.w("NowBarState.clearSimulated failed", t)
        }
    }

    fun isSimulated(kind: ItemKind): Boolean = simulated.containsKey(kind)

    /**
     * A transport command the owner just sent, held over the session's own state until the player
     * reports it back (or the hold expires). Without it the next tick re-read the player's old
     * snapshot and the scrubber snapped back to where it was before the seek, which reads exactly
     * like the command did nothing.
     */
    private class MediaHold(
        val id: String,
        val positionMs: Long?,
        val playing: Boolean?,
        /** When the command was sent: the held position keeps running from here. */
        val startedAt: Long,
        val until: Long
    )

    private var mediaHold: MediaHold? = null

    fun holdMedia(id: String, positionMs: Long?, playing: Boolean?, holdMs: Long = 1_500L) {
        try {
            val now = SystemClock.uptimeMillis()
            mediaHold = MediaHold(id, positionMs, playing, now, now + holdMs)
        } catch (t: Throwable) {
            Log.w("holdMedia failed", t)
        }
    }

    /** Applies a pending command over the item the player just reported. */
    private fun withHold(item: NowBarItem): NowBarItem {
        val hold = mediaHold ?: return item
        if (SystemClock.uptimeMillis() > hold.until) {
            mediaHold = null
            return item
        }
        if (hold.id != item.id) return item
        val playing = hold.playing ?: item.playing
        val duration = item.durationMs
        // A held command is a *prediction*, not a freeze: the position it set keeps running while
        // the player catches up. Pinning it made the elapsed time stop dead for the length of the
        // hold right after a seek, which looked like the bar had seized up.
        val elapsed = (SystemClock.uptimeMillis() - hold.startedAt).coerceAtLeast(0L)
        val base = hold.positionMs ?: item.positionMs
        var position = if (playing) base + elapsed else base
        if (duration > 0L) position = position.coerceAtMost(duration)
        return item.copy(
            positionMs = position,
            playing = playing,
            progress = if (duration > 0L) (position.toFloat() / duration) else item.progress
        )
    }

    /** Highest ranked item whose feature is switched on, or null. */
    fun current(context: Context): NowBarItem? = items(context).firstOrNull()

    /** How many cards the stack holds, used to size the window before it is drawn. */
    fun stackSize(context: Context): Int = try {
        items(context).size
    } catch (t: Throwable) {
        1
    }

    /** Every enabled item, highest ranked first. */
    fun items(context: Context): List<NowBarItem> {
        return try {
            val prefs = TuningStore.prefs(context)
            val out = ArrayList<NowBarItem>(3)
            for (key in TuningStore.rankOrder(context)) {
                val source = sourceFor(key) ?: continue
                val fake = simulated[source.kind]
                // A fake bypasses its feature switch: it exists to be looked at, and the settings
                // app already shows whether that source is enabled.
                if (fake == null && !prefs.getBoolean(source.feature, true)) continue
                // The next alarm is read straight from AlarmManager: no notification involved, so
                // it counts down even when nothing posts anything.
                var item = fake
                    ?: if (source.kind == ItemKind.ALARM) alarmItem(context) else items[source.kind]
                item ?: continue
                if (fake != null) {
                    out.add(item)
                    continue
                }
                // Countdowns and playback positions are derived at read time, so the pill stays
                // live even when the source app posted its notification once.
                item = when (source.kind) {
                    ItemKind.TIMER -> tickTimer(item)
                    ItemKind.MEDIA -> withHold(tickMedia(context, item))
                    ItemKind.ALARM -> item
                    ItemKind.LIVE -> item
                }
                // A live update at 100% is over. The system keeps its chip up until the app takes
                // the notification down; a full bar on a pill says nothing, so the card leaves the
                // stack here and whatever else is active takes its place.
                if (item.kind == ItemKind.LIVE && item.progress >= 0.999f) {
                    Log.i("live update ${item.packageName} is at 100%: dropped")
                    continue
                }
                out.add(item)
            }
            out
        } catch (t: Throwable) {
            Log.w("NowBarState.items failed", t)
            emptyList()
        }
    }

    /** Maps a ranking key to its item kind and its on/off preference. */
    private data class Source(val kind: ItemKind, val feature: String)

    private fun sourceFor(key: String): Source? = when (key) {
        "media" -> Source(ItemKind.MEDIA, TuningStore.FEATURE_MEDIA)
        "timers" -> Source(ItemKind.TIMER, TuningStore.FEATURE_TIMERS)
        "alarm" -> Source(ItemKind.ALARM, TuningStore.FEATURE_ALARM)
        "live" -> Source(ItemKind.LIVE, TuningStore.FEATURE_LIVE)
        else -> null
    }

    /** Minutes until the next alarm clock, at minute granularity. */
    private fun alarmItem(context: Context): NowBarItem? {
        return try {
            val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
                ?: return null
            val next = alarmManager.nextAlarmClock ?: return null
            val minutes = ((next.triggerTime - System.currentTimeMillis()) / 60_000L)
                .coerceAtLeast(0L)
            // Only an alarm that is within the hour is worth a card; anything further out is
            // just clock-app noise sitting on the pill all day.
            if (minutes > 60L) return null
            NowBarItem(
                id = "alarm|${next.triggerTime}",
                kind = ItemKind.ALARM,
                title = "Next alarm",
                subtitle = "in ${formatMinutes(minutes)}"
            )
        } catch (t: Throwable) {
            Log.w("next alarm unreadable", t)
            null
        }
    }

    /**
     * Recomputes a countdown's text from the base time baked into the id, so the pill keeps
     * counting down even though the clock app posts its notification only once. The third id
     * field says which clock it is: `1` countdown (base = end wall clock), `0` stopwatch
     * (fourth field = elapsedRealtime at zero).
     */
    private fun tickTimer(item: NowBarItem): NowBarItem {
        return try {
            val parts = item.id.split('|')
            val base = parts.getOrNull(1)?.toLongOrNull()
            if (base == null) return item
            when (parts.getOrNull(2)) {
                // Third field, when set, is the remaining time of a paused countdown: it must not
                // keep ticking down while the Clock app says it is paused.
                "1" -> {
                    val paused = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                    item.copy(
                        subtitle = formatClock(
                            if (paused > 0L) paused
                            else (base - System.currentTimeMillis()).coerceAtLeast(0L)
                        )
                    )
                }
                "0" -> {
                    val zero = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                    item.copy(
                        subtitle = formatClock((SystemClock.elapsedRealtime() - zero).coerceAtLeast(0L))
                    )
                }
                else -> item
            }
        } catch (t: Throwable) {
            item
        }
    }

    /**
     * The position the player is at *now*.
     *
     * `PlaybackState.position` is a snapshot taken at `lastPositionUpdateTime`; a player only posts a
     * new state when something happens (play, pause, seek, track change), so the raw value sits
     * still while the track runs. This is why the scrubber used to freeze: it was showing an old
     * snapshot once a second. Interpolate from the snapshot using the elapsed real time and the
     * playback speed, the same way the framework's own seek bar does.
     */
    internal fun livePosition(state: PlaybackState?): Long {
        if (state == null) return 0L
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING) return base
        val speed = state.playbackSpeed
        if (speed <= 0f) return base
        val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        if (since <= 0L || since > 6 * 60 * 60 * 1000L) return base
        return base + (since * speed).toLong()
    }

    /** Reads position, duration and playing state from the session, once a second. */
    private fun tickMedia(context: Context, item: NowBarItem): NowBarItem {
        val token = item.token ?: return item
        return try {
            val controller = MediaController(context, token)
            val state = controller.playbackState
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = livePosition(state).let { if (duration > 0L) it.coerceAtMost(duration) else it }
            val playing = state?.state == PlaybackState.STATE_PLAYING
            item.copy(
                progress = if (duration > 0L) (position.toFloat() / duration) else -1f,
                playing = playing,
                positionMs = position,
                durationMs = duration
            )
        } catch (t: Throwable) {
            item
        }
    }
}

/**
 * Reads ongoing notifications and turns the interesting ones into pill items:
 *
 * - **media** — a notification carrying a media session: title, artist, album art and the session
 *   token, so the expanded card can offer previous / play-pause / next;
 * - **timer/stopwatch** — a notification with `EXTRA_SHOW_CHRONOMETER`;
 * - **live** — any notification with a progress bar.
 *
 * Only what the pill displays is read, and nothing is stored beyond the item itself.
 */
class NowBarNotifications : NotificationListenerService() {

    /**
     * Promoted "live update" notifications (the Clock app's timer, for one) are delivered to
     * [onNotificationPosted] but are **absent from `activeNotifications`**. Rebuilding from that
     * list alone therefore saw no timer and immediately wiped the item it had just been given,
     * which is why a running timer never stayed on the pill. They are held here by key instead.
     */
    private val promoted = java.util.concurrent.ConcurrentHashMap<String, NowBarItem>()

    override fun onListenerConnected() {
        try {
            Log.i("notification listener connected")
            rebuild()
        } catch (t: Throwable) {
            Log.w("listener connect failed", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val item = parse(sbn)
            Log.i(
                "notification ${sbn.packageName}: ${item?.let { "item=${it.kind} '${it.title}'" } ?: "ignored"}"
            )
            if (item == null) {
                promoted.remove(sbn.key)
                rebuild()
            } else {
                if (isPromoted(sbn.notification)) promoted[sbn.key] = item
                else promoted.remove(sbn.key)
                NowBarState.put(item.kind, item)
            }
        } catch (t: Throwable) {
            Log.w("onNotificationPosted failed", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            if (sbn != null) promoted.remove(sbn.key)
            rebuild()
        } catch (t: Throwable) {
            Log.w("onNotificationRemoved failed", t)
        }
    }

    /** Re-reads every active notification, so a removal can never leave a stale item behind. */
    private fun rebuild() {
        try {
            val active = activeNotifications ?: return
            var media: NowBarItem? = null
            var timer: NowBarItem? = null
            var live: NowBarItem? = null
            for (sbn in active) {
                val item = parse(sbn)
                // Verbose on purpose: this runs for every notification on every change, and at info
                // level it was loud enough to push the placement logs out of the buffer.
                Log.v("rebuild sees ${sbn.packageName}: ${item?.kind ?: "ignored"}")
                item ?: continue
                if (item.finished()) {
                    Log.v("rebuild: ${sbn.packageName} live update is at 100%, dropping it")
                    continue
                }
                when (item.kind) {
                    ItemKind.MEDIA -> if (media == null) media = item
                    ItemKind.TIMER -> if (timer == null) timer = item
                    ItemKind.LIVE -> if (live == null) live = item
                    ItemKind.ALARM -> Unit
                }
            }
            // Fold in the promoted items the active list cannot show. A notification that was
            // removed has already left this map, so cancellation still clears the card.
            promoted.values.forEach { item ->
                if (item.finished()) return@forEach
                when (item.kind) {
                    ItemKind.MEDIA -> if (media == null) media = item
                    ItemKind.TIMER -> if (timer == null) timer = item
                    ItemKind.LIVE -> if (live == null) live = item
                    ItemKind.ALARM -> Unit
                }
            }
            Log.i(
                "rebuild: ${active.size} active, media=${media?.title ?: "-"}, " +
                    "timer=${timer?.title ?: "-"}, live=${live?.title ?: "-"}"
            )
            NowBarState.put(ItemKind.MEDIA, media)
            NowBarState.put(ItemKind.TIMER, timer)
            NowBarState.put(ItemKind.LIVE, live)
        } catch (t: Throwable) {
            Log.w("notification rebuild failed", t)
        }
    }

    /**
     * A live update whose progress has reached 100% is over. The system keeps its chip up until
     * the app takes the notification down, but a finished activity on a pill is just noise, so it
     * stops being an item as soon as the bar fills.
     */
    private fun NowBarItem.finished(): Boolean =
        kind == ItemKind.LIVE && progress >= 0.999f

    private fun parse(sbn: StatusBarNotification): NowBarItem? {
        return try {
            val n = sbn.notification ?: return null
            val extras = n.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

            // Order matters: a media notification can also carry a chronometer or progress bar.
            val metric = metricValue(n)
            when {
                extras.containsKey(Notification.EXTRA_MEDIA_SESSION) -> media(sbn, n, title, text)
                // Android 16+ timers ship as promoted-ongoing notifications and set no
                // EXTRA_SHOW_CHRONOMETER at all; the countdown lives in the metrics bundle.
                extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || metric != null ->
                    timer(
                        sbn,
                        n,
                        title.ifBlank { metric?.label.orEmpty() },
                        countDown = metric?.countDown ?: chronometerCountsDown(n)
                    )
                extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 -> live(sbn, n, title, text, extras)
                // Anything the platform itself promotes as a live update, plus the ongoing
                // activities that never carry a progress bar: navigation (Maps ETA, a ride, a
                // delivery) and calls. That is what makes Maps and ride apps show up without a
                // per-app allow list.
                isPromoted(n) || n.category in LIVE_CATEGORIES -> live(sbn, n, title, text, extras)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w("notification parse failed", t)
            null
        }
    }

    private fun media(
        sbn: StatusBarNotification,
        n: Notification,
        fallbackTitle: String,
        fallbackText: String
    ): NowBarItem? {
        val token = try {
            @Suppress("DEPRECATION")
            n.extras?.get(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        } catch (t: Throwable) {
            null
        }
        val controller = try {
            token?.let { MediaController(this, it) }
        } catch (t: Throwable) {
            null
        }
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: fallbackTitle
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: fallbackText
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: largeIcon(n)
        val state = controller?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = NowBarState.livePosition(state)
            .let { if (duration > 0L) it.coerceAtMost(duration) else it }
        return NowBarItem(
            id = "media|${sbn.key}",
            kind = ItemKind.MEDIA,
            title = title?.takeIf { it.isNotBlank() } ?: "Playing",
            subtitle = artist.orEmpty(),
            progress = if (duration > 0L) (position.toFloat() / duration) else -1f,
            art = art,
            playing = playing,
            token = token,
            packageName = sbn.packageName,
            positionMs = position,
            durationMs = duration
        )
    }

    private fun timer(
        sbn: StatusBarNotification,
        n: Notification,
        title: String,
        countDown: Boolean
    ): NowBarItem? {
        val start = n.`when`
        if (start <= 0L) return null
        val state = metricState(n)
        // A finished timer keeps its notification until it is dismissed in the shade, so EXPIRED
        // (and the reset/stopped equivalents) must not become a card: that is the 0:00 ghost that
        // used to sit on the pill with nothing running.
        if (state.state in TERMINAL_STATES) return null
        // The metric-style timer sets `when` to the moment the timer started (the pill briefly
        // showed 0:00 because the old code assumed it was the end), so the end comes from the
        // remaining time the Clock app itself reports, and from the duration in the label
        // ("3m Timer", "1h 5m Timer") only when no metric is present.
        val duration = durationFromLabel(title)
        val now = System.currentTimeMillis()
        val pausedMs = if (countDown && state.state == "PAUSED") state.remainingMs ?: 0L else 0L
        val base = when {
            countDown && state.remainingMs != null -> now + state.remainingMs
            countDown && duration != null && start <= now + 5_000L -> start + duration
            else -> start
        }
        val remaining = if (countDown) {
            pausedMs.takeIf { it > 0L } ?: (base - now).coerceAtLeast(0L)
        } else {
            0L
        }
        Log.i(
            "timer notification: '${title}' state=${state.state ?: "-"} " +
                "remaining=${formatClock(remaining)} countdown=$countDown"
        )
        return NowBarItem(
            id = "timer|$base|${if (countDown) 1 else 0}|$pausedMs",
            kind = ItemKind.TIMER,
            title = title.takeIf { it.isNotBlank() } ?: if (countDown) "Timer" else "Stopwatch",
            subtitle = if (countDown) formatClock(remaining) else "Stopwatch",
            packageName = sbn.packageName,
            actions = actions(n)
        )
    }

    /** `3m Timer` -> 180 000 ms, `1h 5m Timer` -> 3 900 000 ms, `10s Timer` -> 10 000 ms. */
    private fun durationFromLabel(label: String): Long? {
        val lower = label.lowercase()
        if (!lower.contains("timer")) return null
        val head = lower.substringBefore("timer")
        var total = 0L
        var found = false
        Regex("(\\d+)\\s*([hms])").findAll(head).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            total += when (match.groupValues[2]) {
                "h" -> value * 3_600_000L
                "m" -> value * 60_000L
                else -> value * 1_000L
            }
            found = true
        }
        return if (found) total else null
    }

    private fun isPromoted(n: Notification): Boolean = try {
        n.extras?.getBoolean("android.requestPromotedOngoing", false) ?: false
    } catch (t: Throwable) {
        false
    }

    /** Notification categories that are an activity in progress, not a message. */
    private val LIVE_CATEGORIES = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_TRANSPORT
    )

    /** States the metric template reports in its sort key. */
    private val TERMINAL_STATES = setOf("EXPIRED", "RESET", "STOPPED", "CANCELLED")

    private val KNOWN_STATES =
        TERMINAL_STATES + setOf("RUNNING", "PAUSED", "STARTED", "NOT_STARTED")

    /**
     * The metric template writes its own state into the notification sort key, for example
     * `13|↺91|RUNNING|▶18:01:55.617|Σ0:01:30|Δ0:01:30|⏳0:00:00` - state, start, total, remaining,
     * elapsed. `Δ` is the remaining time the Clock app itself counts down, which is exact and
     * survives a pause; the title wording is only a fallback.
     */
    private data class MetricState(val state: String?, val remainingMs: Long?)

    private fun metricState(n: Notification): MetricState = try {
        val parts = n.sortKey?.split('|').orEmpty()
        val state = parts.firstOrNull { it.trim().uppercase() in KNOWN_STATES }
        val remaining = parts.firstOrNull { it.startsWith("Δ") }
            ?.drop(1)
            ?.let(::clockToMillis)
        MetricState(state?.trim()?.uppercase(), remaining)
    } catch (t: Throwable) {
        Log.w("metric sort key unreadable", t)
        MetricState(null, null)
    }

    /** `0:01:30` or `1:05` from the sort key into milliseconds. */
    private fun clockToMillis(text: String): Long? = try {
        val parts = text.trim().split(':')
        when (parts.size) {
            2 -> parts[0].toLong() * 60_000L + parts[1].toLong() * 1_000L
            3 -> parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L +
                parts[2].toLong() * 1_000L
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun chronometerCountsDown(n: Notification): Boolean = try {
        n.extras?.getBoolean("android.showChronometerCountDown") ?: false
    } catch (t: Throwable) {
        false
    }

    /** One clock-style metric from a promoted-ongoing notification, if it carries one. */
    private data class Metric(val label: String, val countDown: Boolean)

    /**
     * The Clock app on Android 16+ posts its timer through the metrics template: the extras hold
     * `android.metrics = [{label: "10s Timer", value: {zeroElapsedRealtime, format, countDown}}]`
     * and nothing else that says "this is a countdown". `zeroElapsedRealtime` is what separates a
     * clock from a plain progress bar, so it is the marker used here.
     */
    private fun metricValue(n: Notification): Metric? {
        return try {
            val raw = n.extras?.get("android.metrics")
            val list = raw as? ArrayList<*> ?: return null
            for (entry in list) {
                val bundle = entry as? android.os.Bundle ?: continue
                val value = bundle.getBundle("value") ?: continue
                if (!value.containsKey("zeroElapsedRealtime")) continue
                return Metric(
                    label = bundle.getString("label").orEmpty(),
                    countDown = value.getBoolean("countDown", false)
                )
            }
            null
        } catch (t: Throwable) {
            Log.w("notification metrics unreadable", t)
            null
        }
    }

    private fun live(
        sbn: StatusBarNotification,
        n: Notification,
        title: String,
        text: String,
        extras: android.os.Bundle
    ): NowBarItem {
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val value = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        // A live update's own one-liner ("6 min", "Arriving") is what the system chip shows, and
        // it is the most useful thing to put on a small pill.
        val critical = extras.getCharSequence("android.shortCriticalText")
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        val info = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        val line = critical ?: text.takeIf { it.isNotBlank() } ?: info.orEmpty()
        return NowBarItem(
            id = "live|${sbn.key}",
            kind = ItemKind.LIVE,
            title = title.takeIf { it.isNotBlank() } ?: sbn.packageName,
            subtitle = line,
            progress = if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else -1f,
            art = largeIcon(n),
            packageName = sbn.packageName,
            actions = actions(n)
        )
    }

    /**
     * The card's controls come from the notification itself, so whatever the source app offers
     * works without a permission of our own. Labels stay short: they are drawn as glyphs.
     */
    private fun actions(n: Notification): List<NowBarItem.Action> = try {
        (n.actions ?: emptyArray()).mapNotNull { a ->
            val intent = a?.actionIntent ?: return@mapNotNull null
            val label = a.title?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            NowBarItem.Action(label, intent)
        }
    } catch (t: Throwable) {
        Log.w("notification actions unreadable", t)
        emptyList()
    }

    private fun largeIcon(n: Notification): Bitmap? {
        return try {
            @Suppress("DEPRECATION")
            when (val icon = n.extras?.get(Notification.EXTRA_LARGE_ICON)) {
                is Bitmap -> icon
                is BitmapDrawable -> icon.bitmap
                is Icon -> (icon.loadDrawable(this) as? BitmapDrawable)?.bitmap
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/** `24m` under an hour, `1h 05m` above it, used by the alarm item. */
internal fun formatMinutes(minutes: Long): String =
    if (minutes < 60L) {
        "${minutes}m"
    } else {
        "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
    }

/** `m:ss` up to an hour, `h:mm:ss` above it, used by the timer item. */
internal fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
