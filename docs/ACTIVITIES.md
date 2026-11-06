# Activities: how other islands do it, and what bbbar reads

Notes from reading the open-source dynamic-island / Now Bar clones, and the platform rules they
lean on. Everything here was checked against this device (Nothing Phone 3a, Android 17) where it
says "seen".

## What the open-source projects do

| Project | Where its activities come from | Idea worth keeping |
| --- | --- | --- |
| `ajitpal/dynamic-island-android` | `NotificationListenerService` for content, `MediaSessionManager` for transport, chronometer extras for timers, a "metric" fallback when the extras are absent | One parser per activity type, all funnelled into a single event model; cutout-bound anchor computed at runtime |
| `ProNoob2450/Android-Island` | notifications, media sessions, charging/low-battery broadcasts, `TelephonyManager` call state, plus synthetic providers for timer/navigation | `core:events` = normalized events + **deterministic priority queue**; `core:ui` = reducer with states `idle / peek / expanded / pinned / dismissed / incoming-call / in-call`; secondary bubble for a second activity |
| `Islandify` | media + notifications, foreground service keeps the overlay alive | Dismissed vs. ended are different states; a card that was swiped away stays away until the activity changes |

Common shape in all three: **one event model, one priority queue, one renderer**. Sources never
draw; they push items. That is what bbbar does too (`NowBarState` + `NowBarItem`), which is why
adding a source is cheap.

## The platform rules that matter (Android 16+ Live Updates)

Live Updates are "promoted ongoing notifications". To qualify an app must declare
`POST_PROMOTED_NOTIFICATIONS`, call `EXTRA_REQUEST_PROMOTED_ONGOING`, be ongoing, have a title, use
no custom `RemoteViews`, and be one of Standard / BigText / Call / Progress / **Metric** style.
`Notification.FLAG_PROMOTED_ONGOING` marks one that got promoted, and
`android.shortCriticalText` is the one-liner the system chip shows.

Seen on this device, from `dumpsys notification --noredact`:

```
pkg=com.google.android.deskclock flags=ONGOING_EVENT|LOCAL_ONLY|PROMOTED_ONGOING
android.template = android.app.Notification$MetricStyle
android.metrics  = [{label=5m Timer,
                     value={zeroElapsedRealtime=6313998, format=2, countDown=true}}]
android.shortCriticalText = null
sortKey (on the firing copy) = 0 11|↺91|EXPIRED|▶18:07:53.607|Σ0:03:00|Δ0:03:00|⏳0:00:00
```

Two consequences that shaped the code:

- A **running** metric notification carries no `sortKey` and no `category`, so the remaining time
  comes from the label + `when`; the terminal state (`EXPIRED`/`RESET`) only shows up on the copy
  posted when the timer fires. That copy must be ignored or a `0:00` ghost card sits on the pill.
- A **paused** timer keeps the same metrics, so the paused remaining time has to be frozen locally
  rather than counted down.

## What bbbar reads today

| Activity | Source | Controls |
| --- | --- | --- |
| Media | `EXTRA_MEDIA_SESSION` → `MediaSession.Token`, title/artist/art from the controller, position from `PlaybackState` | previous / play-pause / next (session transport) |
| Timer, stopwatch | Metric template (`android.metrics`, `countDown`, `zeroElapsedRealtime`), label fallback (`3m Timer`), `sortKey` state for expired/paused | the Clock app's own notification actions (`Pause`, `Add 1 min`) |
| Next alarm | `AlarmManager.nextAlarmClock`, only within the hour | none |
| Live update | any progress notification, any promoted notification, `android.shortCriticalText`, categories `call` / `navigation` / `progress` / `transport` | the notification's own actions |
| Fakes | settings app switches (`NowBarState.putSimulated`) | — |

Controls are **the source app's `PendingIntent`s**. That is why a timer can be paused, a call
answered and a live update dismissed without the pill holding any permission of its own: the app
performs its own action.

## Backlog: activities worth adding

Ordered by value on this device. None of these need root, and all but the starred ones are a new
`ItemKind` or a few lines in the existing parser.

1. **Maps / navigation ETA** — promoted update, category `navigation`; show arrival time plus
   distance, controls straight from the notification. *Seen working in the parse path; needs a
   real trip to confirm the strings.*
2. **Ride / delivery** — same shape as navigation (Uber, DoorDash). Nothing new to build.
3. **Call in progress** — `CallStyle` notification; answer / decline / hang up are already its
   actions. Add a distinct rank above live updates so a call always wins.
4. **Downloads, installs, photo backup** — progress notifications. Needs **more than one live
   slot** (today the first live item wins), so: `Map<String, NowBarItem>` per source key.
5. **File transfer (KDE Connect)** — progress + cancel action; already installed on this phone.
6. **Screen recording / casting** — `MediaProjection` and `MediaRouter` are readable without a
   notification: a local item with elapsed time and a stop control.
7. **Hotspot** — `ConnectivityManager` tethering state plus client count.
8. **Bluetooth audio** — connected device name from `BluetoothProfile`; useful when buds connect.
9. **Focus / Do Not Disturb** — `NotificationManager.getCurrentInterruptionFilter`.
10. **Torch** — `CameraManager` torch callback; the card is a one-tap off.
11. **VPN** — `NetworkCapabilities.TRANSPORT_VPN`, or the VPN app's own ongoing notification (the
    VPN on this phone posts one, but with no category, so it needs the per-app allow list).
12. **Next calendar event** — `CalendarContract` (needs `READ_CALENDAR`) with a countdown.
13. **New screenshot** — `MediaStore` watch; tap opens it, long press shares.

Structural ideas from the other projects still missing here: a **peek** state (a new activity
briefly opens, then settles), **pinned** vs **dismissed**, a **secondary bubble** for a second
activity, and a per-app allow list feeding the existing live-update source.

## Not doing

- **Battery** — removed at the owner's request (charging card, glyph and toggle are gone).
- **Now Brief** — Samsung's card is tied to Samsung services and accounts.
