# bbbar — a Now Bar for Nothing OS

**v1.0.0** — [release notes](https://github.com/BRL26/bbbar/releases/tag/v1.0.0)

A Now Bar–style pill for Nothing Phone (3a) on Nothing OS 5 (Android 17): a rounded pill on the
lock screen, a small one where the status bar notification icons live, and an expanded pop-up that
grows out of it. Personal device project — the pill is our own window, drawn by an accessibility
overlay.

The repo/project name is **bbbar**; the app label is `bbbar`; the package is still
`dev.brl.nowbar.module` (renaming it would unbind the accessibility service and lose the saved
geometry).

## How it is built

| Path | Window | Over apps | Over lock screen | Needs |
| --- | --- | --- | --- | --- |
| `NowBarA11yService` | `TYPE_ACCESSIBILITY_OVERLAY` | yes | **yes** | Accessibility service enabled |
| `OverlayService` | `TYPE_APPLICATION_OVERLAY` | yes | no | "Display over other apps" |

The accessibility overlay is the real path: it wins on the lock screen without root (it is
`Window #2`, above `NotificationShade`). `TYPE_APPLICATION_OVERLAY` is layered *below* the
keyguard by platform rule, which is why the fallback overlay only ever shows over apps.

## Placement in landscape

Landscape has no room at the sides — the clock owns the left end of the strip and the battery/status
icons the right — so the status pill is **centred horizontally** in landscape, in the shade as well as
in the status bar. Any tuned left edge is ignored there on purpose; only the height is configurable.
The pill is also allowed to grow much wider than the portrait cap: half the screen with the shade
open, a third of it in landscape, so a long line gets a long pill instead of a squeezed one (it still
shrinks to its text when the line is short).

Two numbers make that possible on the device, both published by the hook, because an accessibility
service's own display metrics freeze at the size they had when it started:

| Settings.Global key | Meaning |
| --- | --- |
| `bbbar_screen_w`, `bbbar_screen_h` | the screen size **SystemUI** sees, so a rotation reaches the app |
| `bbbar_clock_right_px` | the status bar clock's right edge, `0` when the clock is not shown (the shade) |

The clock edge is what keeps the pill off the time: in portrait its left edge is pushed to the right
of the clock whatever the tuned value says, and in landscape the same number is why centring clears
it. With the shade open the clock reports `0`, so the pill sits at a plain **16 dp** margin there
instead of the width the clock needs.

Rotation is handled from the display listener (a service does not reliably get
`onConfigurationChanged`) plus the hook's screen size: any change re-places every window that is up,
so a pill cannot be left laid out for the orientation you just left.

## Following the status bar

The pill is drawn in the same strip as the notification icons, so it follows the bar's own
visibility: when a fullscreen or immersive app hides the status bar, the pill rises out with it and
comes back down when the bar returns.

This is a **push**, not a poll. The platform tells SystemUI whenever the status bar window changes
through `CommandQueue.Callbacks.setWindowState`, and `NowBarHook` hooks the method that receives it
(`com.android.systemui.statusbar.window.StatusBarWindowStateController#setWindowState`). That hook
writes `Settings.Global → bbbar_sb_hidden` the moment the state changes, and the service watches that
key with a `ContentObserver`. From the platform's call to the pill reacting is a few milliseconds;
nothing on this path runs on a timer.

Why it took a hook rather than a view check: on this build a hidden bar can be a *window* that the
window manager fades or scales out (the launcher's auto-hide does exactly that). `isShown`, the
view's alpha and its visible rect all stayed `true`/full, so the pill never noticed. Window insets
were tried as a middle ground and were worse in practice: a window that stops drawing stops
receiving inset updates, so the last answer stuck at "hidden" and the pill stayed gone.

A stale hook (SystemUI restarted without it) is ignored, so a leftover flag can never switch the
pill off for good. Without the hook the pill simply stays visible — and if the push cannot be hooked
at all, the hook falls back to sampling the bar view every 500 ms, which still covers immersive apps
(just not the fade-out kind).

The notification shade and Quick Settings are **not** treated as a hidden bar: the pill stays
visible there, on purpose. While the shade is open:

- the **top pill** is what shows, locked or unlocked. The keyguard's bottom pill would be sitting
  behind the notification list, and the shade has its own header for that strip
- the pill is **only visible** there, never interactive: the window gets `FLAG_NOT_TOUCHABLE` and the
  view ignores touches, so a tap falls through to the shade's own header instead of expanding
  something under it
- its height there is configurable: **Shade / Quick Settings top edge** in the status bar card
  (`sbShadeTopDp`, `-1` = stay where it is). There is no left control because that strip has no
  clock to dodge

Both switching and moving are pushed live from the same hook — the shade state comes from
`NotificationShadeWindowControllerImpl#apply`, reading its `shadeOrQsExpanded` field — so it happens
with the shade animation, not after it.

The hook only reloads with SystemUI, so a build that changed it needs exactly one restart after
installing. That is one broadcast, no repeated crashing:

```
adb shell am broadcast -a dev.brl.nowbar.module.RESTART_SUI \
  -n dev.brl.nowbar.module/.TuningReceiver
```

## Android's own status bar icons

The pill lives in the strip Android fills with notification icons, so bbbar can hide that strip's
contents from inside SystemUI. `NowBarHook` (the LSPosed entry, scope `com.android.systemui`) hooks
the `PhoneStatusBarView` constructor, walks to the view whose resource id is `notification_icon_area`
and sets `alpha` on the children the switches ask for. It never lays anything out and never draws,
so a changed class name leaves the stock status bar untouched instead of breaking it.

Two switches, in **Settings → Android status bar**:

| Switch | Settings.Global key | Hides |
| --- | --- | --- |
| Hide notification icons | `bbbar_hide_notif_icons` | the app icons next to the clock |
| Hide Android live updates | `bbbar_hide_live_chips` | the system's own Live Update chips |

Both are read by SystemUI from `Settings.Global` (writable only through root, which the app uses) and
watched with a `ContentObserver`, so flipping a switch takes effect without a restart. The hook
takes effect when SystemUI (re)loads, so the first time needs **Test tooling → Restart SystemUI**, and
the module must be enabled for `com.android.systemui` in LSPosed. The **Xposed hook** row asks the hook itself: it publishes
`Settings.Global → bbbar_hook_beat` (SystemUI holds `WRITE_SECURE_SETTINGS` here) every 30 s, and the
row shows `loaded` when that heartbeat is fresh, `not loaded (last beat Ns ago)` when SystemUI
restarted without the hook, and `off (kill switch)` when the file exists. A logcat attach line is
only the fallback for a SystemUI that has not been restarted since this build. Before that it read
the *last* `hook:` line, which is `hook: icon area child …` — so a loaded hook showed as not loaded,
and once the log rotated there was nothing to find at all. A file kill switch stays available:

```bash
touch /data/local/tmp/bbbar_hook_off   # SystemUI loads with no hook at all
```

Three surfaces, each with its own geometry:

| Surface | What it is | Where |
| --- | --- | --- |
| Lockscreen pill | 340 × 68 dp, radius 40, 150 dp above the bottom | bottom centre, clear of the fingerprint circle |
| Status bar pill | 106 × 27 dp, radius 19, 75 dp from the left | over the notification icons, past the clock, short of the cutout |
| Expanded popup | 393 × 120 dp, radius 32 | drops out of the status bar pill; auto-centred under the status bar |

Only one collapsed surface is on screen at a time. Unlocking swaps the lock pill for the status
pill; locking reverses it, staged as `bar pill off → 450 ms → lock pill on` so the lock pill never
lands inside the screen-off animation. Tapping a collapsed pill opens its expanded form; tapping
anywhere outside closes it (taps inside no longer do).

Geometry comes from live window insets (`statusBars` top, `displayCutout`) and the tuned dp values
below, never from hardcoded screen pixels. The status bar pill's width is fitted to its line
(maximum is the tuned width) and **morphs** to the new width over 230 ms instead of snapping, so a
new title, a timer appearing or the card going away reads as the same shape stretching. A change
that arrives mid-morph retargets from the width on screen; a settings change keeps the current
width for its first frame, so the pill never flashes wide.

## Unlock/lock detection

`KeyguardManager.addKeyguardLockedStateListener` throws on this build. Detection therefore stacks:

1. **Root log watcher** (`RootKeyguardWatcher`) — `su -c logcat …` on the SystemUI keyguard tags.
   Every line is only a *wakeup*; real state still comes from `KeyguardManager`, re-checked every
   8 ms for a short confirmation window. This is the fast path.
2. `ACTION_USER_PRESENT` through `UserPresentReceiver` → `NowBarBridge`.
3. An accessibility window-state event on the keyguard window.
4. A poll: 40 ms while locked and awake, 800 ms while dozing, 1 s otherwise.

One handover per 400 ms, and a raw `isKeyguardLocked=false` only counts after a streak of
consecutive reads — both exist because the flag oscillates mid-transition and acting on it made
the pill vanish and reappear. A *suppressed* handover now schedules a resync 320 ms later: back
when it just returned, the earlier handover had already taken its window down and nothing was left
to retry, which is one of the ways the pill disappeared for good.

## Staying alive

Three keepers, in order of how much they can fix:

1. **Window watchdog** (in the service, every 2.5 s). Compares the windows on screen with the ones
the lock state asks for and repairs a difference that persists over two checks: a detached window
is dropped and rebuilt, a surface that should not be up is removed, a detached popup is dropped so
the next tap opens it properly. It skips while a handover is in flight and while the keyguard pill
is waiting out `SCREEN_OFF_LOCK_DELAY_MS`.
2. **Root keeper** (`RootKeeper`) — a `sh` loop under root, `setsid`-detached so it outlives the app
   process, checking every 20 s that our own component is still in
   `enabled_accessibility_services` and putting it back with `settings put` when it is not. This is
   the one that matters after a crash: a killed app is also dropped from that list, and then
   Android never binds it again — the pill stays off until it is switched back on by hand. Script
   and log: `/data/local/tmp/bbbar-watchdog.sh`, `.pid`, `.err`.
3. **Alarm fallback** (`NowBarWatchdog` + `WatchdogReceiver`, every 60 s, re-armed on boot and after
   an update). Same check plus a forced rebind when the service is listed but not running. Inexact
   repeating alarms sit in a batching window (`origWhen` in the past, `maxWhenElapsed` in the
   future), so this one is a backstop, not the primary keeper.

Both the loop and the alarm only repair a drop they did not cause: the service clears
`wasConnected` on a *graceful* stop, so switching bbbar off in Accessibility stays off, and a crash
(which never reaches `onDestroy`) leaves the flag set and gets repaired. The root loop also stands
down while the **Pill enabled** master switch is off.

## What it shows

The pill renders the items that are active, in the owner's **ranking order** (media, timers, live
updates, reorderable in the settings screen). When several are active they stack, each card
peeking out under the one in front. Each source also has its own on/off switch.

| Item | Source | Needs |
| --- | --- | --- |
| Media | `EXTRA_MEDIA_SESSION` notification: title, artist, large icon as album art, play/pause action | Notification access |
| Timer / stopwatch | `EXTRA_SHOW_CHRONOMETER` notification, counted down locally from the notification's base time | Notification access |
| Live update | any notification with a progress bar (`EXTRA_PROGRESS_MAX` / `EXTRA_PROGRESS`) | Notification access |

Notification access is granted once at **Settings → Notifications → Device & app notifications →
bbbar**, and the settings screen links straight to it and shows whether it is granted. Only what
the pill draws is read — title, text, art, progress and one action — and nothing is stored.

Controls exist only in an expanded view: the lockscreen expanded pill and the status bar popup get
previous / play-pause / next for media (through the notification's own `MediaSession` token) and the
source notification's own action buttons for everything else, so no media permission is involved.
The status bar pill and the collapsed lockscreen pill are text and art only, with the player's app
icon next to the line so it is clear which app is running.

Swiping is part of the surface:

| Gesture | Where | Effect |
| --- | --- | --- |
| Tap | collapsed pill | expand |
| Tap outside | expanded pill / popup | collapse (animated) |
| Swipe right | expanded or lockscreen card, media in front | previous track |
| Swipe left | expanded or lockscreen card, media in front | next track |
| Swipe up / down | expanded or lockscreen card | cycle the stack; the new front card is also what the status bar pill shows |
| Drag the scrubber | expanded media card | preview a position, seek on release |

The card leans towards the finger first: until the direction is clear (12 dp, and one axis leading
by 1.25×) it only follows **22 %** of the movement, which reads as the card being stretched from one
edge rather than dragged. Once an axis wins it stays won — it cannot flip halfway through — and the
card catches up to the finger over about 130 ms, so it bounces across to the finger's position and
tracks it exactly from then on. Sideways drags stay at 75 % even then, because the swipe they commit
to is a skip rather than a card change; media drags go right for *previous*, matching the rest of the
system.

Released short of the distance a swipe needs (44 dp, or 30 % of the card height) the card slides
back to its place over about 0.4 s with a slight overshoot; a committed swipe eases home over about
0.3 s while the next card comes in.

**Dragging moves the window, not the drawing.** The earlier mechanic grew the window and drew the
card offset inside it, which cannot work: a window clips to its own bounds, so the card was sliced at
an invisible edge (a "box" around it), and every offset, growth and shrink had to be undone in the
right order or the card was left outside its window and vanished. Now the window stays exactly the
size of the card and the window manager moves it with the finger — nothing to clip, nothing to
restore. Deltas come from raw screen coordinates, so moving the window cannot feed back into the
gesture, and an `ACTION_OUTSIDE` that arrives mid-drag is ignored: with the window following the
finger, the tail of its own gesture can land outside it, and treating that as a tap elsewhere was
collapsing the card under the finger. Expansion animators are cancelled when a drag starts, so the
card cannot re-run its slide-in afterwards. The status bar pill ignores swipes, so it does not follow
the finger at all.

An expanded card settles back to the pill on its own after **10 s**. Any control, swipe or seek
re-arms that timer, so it never closes while it is being used; the collapsed pill keeps showing
the same activity.

Expanded media cards carry a real scrubber under the title, with the elapsed time beside it,
instead of the thin progress strip along the bottom edge (that strip stays for timers, charging and
live updates, which have no seekable position).

The scrubber moves while a track plays: `PlaybackState.position` is only a snapshot taken at
`lastPositionUpdateTime`, so the position is interpolated from it with the elapsed time and the
playback speed instead of being shown as-is (that snapshot was why the bar sat still). After a seek
or a play/pause the new value is held over the player's old state for up to two seconds, so the
thumb does not snap back before the player reports it.

Both expanded forms are **as tall as their content**: the expanded popup (`popupHeightDp` is a
ceiling) and the expanded lockscreen card (2.5× the tuned height is a ceiling, the collapsed height
is the floor). A one-line card gets a short island instead of a band of empty card under it, and the
popup's height animates when a different card needs more or less room.

Long lines scroll instead of being cut: a 1.4 s hold, a steady 34 dp/s slide, a 1.1 s hold, then
loop, with the cut ends faded into the card (`scrollText` turns it off). A track change restarts the
line only when the title, artist or cover pixels actually changed — the notification store rebuilds
every item about once a second, so the old identity check restarted the scroll mid-song.

## Settings app

Opening **bbbar** shows a Material 3 screen with Material You colours (from the wallpaper, light and
dark) and five tabs along the bottom — **Home**, **Look**, **Activities**, **Debug**, **About**.
Every control writes into the shared `tuning` prefs the service watches, so edits apply live; the
**Apply settings** button pushes a reload immediately when an edit needs one. **Back** walks the
screen instead of closing it: a sub-page returns to its tab, a side tab returns to Home, and only
then does back leave the app.

- **Home** — running/stopped with the reason, the master switch, the **preview** and the idle line.
  The preview is not a mock-up: it is the pill itself, fed by `NowBarState.items()`, so it shows the
  card that is on the bar at this moment, in the same rank order, at the same sizes, and follows it
  within a second (a new track, a timer tick, a card swiped away). Tap it to see the expanded card,
  or the idle line when nothing is active.
- **Look** — the three surface switches, then one sub-page per surface plus the look pages:
  **Lockscreen pill**, **Status bar pill**, **Expanded card**, **Media artwork**, **Text and motion**,
  **Always-on display** and **Idle line**. Sliders run `0…1000 dp` so nothing is capped; `-1` on a
  left/top value means *auto*. The status bar page carries **Shade / Quick Settings top edge** and
  **Shade / Quick Settings width** — `0` fits the line the way it always did, anything else is the
  width the pill takes while the shade is open (clamped to the screen).
- **Activities** — the item switches (media, timers, alarms, live updates), the **ranking** list with
  ▲/▼, notification access, the app exclusions, the status-bar hiding switches, and **simulate**: a
  fake card of each kind drawn on the real pill, for judging a look without waiting for an activity.
- **Debug** — the plumbing (accessibility overlay, root watcher and its switch, fallback overlay,
  notification access, hook state, screen and clock geometry, windows on screen) and the log level.
- **About** — version, package, repository link, and **Reset all settings**: confirm, then every
  stored value goes back to this build's defaults and the pill reloads.

Turning the master switch off, or a surface off, takes effect immediately: the service removes that
window at once instead of waiting for the next lock/unlock cycle, and switching it back on puts it up
again.

### Two rules the pill keeps

- **The placeholder belongs to the status bar.** The lock pill and the expanded card never draw it,
  and the status pill does not draw it on a boot lock screen either: the keyguard flag answers
  "unlocked" for a moment while the lock screen is still being built, which used to flash the idle
  line across it. The pill only shows its placeholder once an unlock has actually happened in this
  boot — remembered across service restarts, so a keeper restore mid-session does not lose it.
- **A live update at 100 % leaves the stack.** The system keeps its chip up until the app withdraws
  the notification, but a full bar on a pill says nothing, so the card drops out and whatever is
  below it takes the front.

## Build and install

```bash
cd Projects/nowbar-module
~/tools/gradle/current/bin/gradle :module:assembleDebug --offline
adb install -r module/build/outputs/apk/debug/module-debug.apk
```

AGP 9.5.0 with built-in Kotlin (do **not** add the Kotlin Gradle plugin), Gradle 9.8.2,
compileSdk/targetSdk 37, minSdk 31.

Enable the pill:

- **Settings → Accessibility → bbbar → Use bbbar**, or
- ```bash
  adb shell settings put secure enabled_accessibility_services \
      dev.brl.nowbar.module/dev.brl.nowbar.module.NowBarA11yService
  adb shell settings put secure accessibility_enabled 1
  ```

Nothing else is required: the root watcher starts with the service, and Magisk already holds the
grant.

## Tuning over adb

The same keys the sliders write, for when the pill is covering something:

```bash
adb shell am broadcast -a dev.brl.nowbar.module.SET -n dev.brl.nowbar.module/.TuningReceiver \
  --ei widthDp 340 --ei heightDp 68 --ei bottomDp 150

adb shell am broadcast -a dev.brl.nowbar.module.SET -n dev.brl.nowbar.module/.TuningReceiver \
  --ei sbShadeWidthDp 480    # fixed width while Quick Settings is open (0 = fit the line)

adb shell am broadcast -a dev.brl.nowbar.module.SET -n dev.brl.nowbar.module/.TuningReceiver \
  --ez dump true      # log every stored value
```

## The always-on display look

With **Line + dots on the AOD** on, the AOD card is drawn as a thin outline with the text inside it
and no filled card, and the played fraction is a lattice of small white dots instead of a bar. The
text is the inversion of the same lattice — solid glyphs with the dots punched through in black — so
the line reads as part of the pattern rather than sitting on top of it.

The black dots in the text are the *same* dots as the bar's progress, not a texture laid over the
letters: they only exist as far as the played fraction reaches, clipped to the glyphs and to the
filled width at once. Beyond the playhead the line is solid text. At 0 % there are no holes at all.

Spacing and dot size are separate settings — **AOD dot spacing** (3–24 dp) and **AOD dot size**
(1–16 dp) — so the dots can be made bigger without also spreading them out. The dot diameter is
clamped to the spacing, so the maximum is dots that just touch rather than a solid bar.

The lattice drifts: it picks a random direction and speed every 2.2–4.5 s, eases into it over about
a second, and never moves faster than 8 dp/s. Nothing jumps; a frame only ever moves the offset by
`velocity × dt`.

## Logs

```bash
adb logcat -s NowBar                 # everything, tag NowBar
adb logcat -s NowBar | grep handover # unlock/lock swaps and their timings
adb logcat -s NowBar | grep watchdog # surface repairs and keeper restores
adb logcat -s NowBar | grep morph    # status bar pill width changes
```

Useful lines: `lock handover: … removed in Nms`, `unlock handover: first frame Nms after the
change`, `screen off: status pill removed, lock pill in 450ms`, `root keyguard signal confirmed …`.

## Repo layout

```
module/src/main/java/dev/brl/nowbar/module/
  NowBarA11yService.kt   the pill: three windows, geometry, handover, animations
  Pill.kt                PillView (canvas pill) + PillHost
  Tuning.kt              typed reads of the tuning prefs
  TuningStore.kt         pref names, defaults, keys
  SettingsActivity.kt    Material 3 settings screen
  TunerService.kt        floating dev tuner (draggable panel)
  OverlayService.kt      fallback TYPE_APPLICATION_OVERLAY pill
  RootKeyguardWatcher.kt root logcat wakeup stream
  RootKeeper.kt          root loop that keeps the accessibility service enabled
  NowBarWatchdog.kt      alarm-based fallback keeper (+ WatchdogReceiver.kt, boot re-arm)
  UserPresentReceiver.kt ACTION_USER_PRESENT → service
  NowBarHook.kt          LSPosed prototype entry (inert, see below)
```

## Known limits

- **No AOD pill.** `TYPE_ACCESSIBILITY_OVERLAY` is not composited while the display dozes, so the
  pill is timed against the display (`SCREEN_OFF`, `isInteractive`), never the doze screen. A real
  AOD pill needs a window type that survives dozing; not attempted.
- **No per-app allow / deny list yet.** Any app that posts a media session, a chronometer or a
  progress notification becomes a card; individual apps cannot be excluded.
- **The idle pill is a status bar surface only.** Lockscreen and popup disappear when nothing is
  active. The status bar pill shows the placeholder label, and it is *only* the label that decides
  this: blank label → no idle pill, any text → pill. The old Text / Logo / Hide switch is gone,
  because a stored "Hide" looked exactly like a dead bar and nothing ever reset it.
- **The LSPosed hook only hides**, it never draws or lays out: it sets `alpha` on the status bar's
  notification-icon area children. Everything the pill shows comes from the accessibility overlay,
  which is why a changed Nothing OS build cannot break the pill itself.
- **No root daemon.** The `app_process` overlay approach was abandoned: `WindowManager` rejects its
  window with `Unknown pid … uid=0`. Root is used only for the logcat wakeup stream and the two
  `Settings.Global` switches.
- **The published APK is signed with the local debug key.** It is a personal sideload build, not a
  Play Store artefact; install it over the previous build with `adb install -r`.
