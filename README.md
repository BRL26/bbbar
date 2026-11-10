# bbbar — a Now Bar for Nothing OS

**v2.2.0** — previous release notes: [v2.1.0](https://github.com/BRL26/bbbar/releases/tag/v2.1.0)

New in this build: **per-element hook switches** (the bar's clock, the battery / network cluster,
the privacy dot), a real **all-off** switch that also stops the root keeper, the keyguard log
stream and the watchdog, **grayscale AOD app icons** whose progress dots invert against a
black-and-white copy, and an unlock handover that cannot blink. Shipped defaults are still the ones
dialled in on the phone: left side with the bar's clock off, 160 × 27 dp, 20 dp from the left, and
the idle line reads `5:11 PM :)`.

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

Landscape anchors the status pill to the **right end** of the strip, the way the inverse portrait
layout does, in the shade as well as in the status bar: that is the edge the strip is read from, and
a centred pill in a wide strip read as floating. Two controls move and size it there, both in
Look → Status bar pill: **Landscape width** and **Landscape right margin** (`-1` on either means
"use the portrait value"). The width is what lets the pill take up a landscape strip properly -
setting it large gives a long line a long pill instead of squeezing it into a status-bar-sized box
(it still shrinks to its text when the line is short).

Two numbers make that possible on the device, both published by the hook, because an accessibility
service's own display metrics freeze at the size they had when it started:

| Settings.Global key | Meaning |
| --- | --- |
| `bbbar_screen_w`, `bbbar_screen_h` | the screen size **SystemUI** sees, so a rotation reaches the app |
| `bbbar_clock_right_px` | the status bar clock's right edge, `0` when the clock is not shown (the shade) |

The clock edge is what keeps the pill off the time in the normal portrait layout: its left edge is
pushed to the right of the clock whatever the tuned value says. With the shade open the clock
reports `0`, so the pill sits at a plain **16 dp** margin there instead of the width the clock needs.
Right-anchored layouts (inverse portrait, and landscape) do not need the clock edge at all - they
stop at their own right margin.

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
- it is the **same pill in the same place** as the status bar: the shade / Quick Settings header
  gets the normal pill instead of a shade-specific one, so there is nothing to adjust and no
  separate shade geometry to drift (the old `sbShadeLeftDp` / `sbShadeTopDp` / `sbShadeWidthDp`
  settings are gone)

Both switching and showing are pushed live from the same hook — the shade state comes from
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

Two switches in **Settings → Android status bar**, and three per-element ones on the status bar
page (issue #12):

| Switch | Settings.Global key | Hides |
| --- | --- | --- |
| Hide notification icons | `bbbar_hide_notif_icons` | the app icons next to the clock |
| Hide Android live updates | `bbbar_hide_live_chips` | the system's own Live Update chips |
| Hide Android's clock | `bbbar_hide_clock` | the strip's clock, for the clock-less left layout (portrait only) |
| Hide battery / network icons | `bbbar_hide_sys_icons` | Android's battery / network cluster, for the right-end layout |
| Hide the privacy indicator | `bbbar_hide_privacy` | the camera / microphone dot, for the right-end layout |
| (placement) | `bbbar_inverse` | tells the hook which end the pill owns, so the notification icons come back |

The hook's switches live under **Sys → Android status bar**, together with its own status row, and
are on by default. Each is only consulted where its layout uses it: the clock for the clock-less left
side, the cluster and the privacy dot for the right-hand one. Switching one off leaves Android's own
element in place - the pill then stops drawing its own battery / network glyphs, since it no longer
replaced them. A key that was never written follows the layout, so an older installation behaves
exactly as before until a switch is touched.

`bbbar_hide_clock` masks the same clock view the hook samples for `bbbar_clock_right_px`, so a
hidden clock reports a right edge of `0` and the left-edge layout simply has nothing to avoid.

All of them are read by SystemUI from `Settings.Global` (writable only through root, which the app
uses) and
watched with a `ContentObserver`, so flipping a switch takes effect without a restart. The hook
takes effect when SystemUI (re)loads, so the first time needs **Sys → SystemUI → Restart SystemUI**,
and the module must be enabled for `com.android.systemui` in LSPosed. A module that is installed but
not *enabled* (or not scoped to SystemUI) loads nothing at all: the row then keeps reading
`not loaded (last beat Ns ago)` however often SystemUI restarts, and LSPosed posts its own
"module is not active" notification. Toggling the module off and on in the manager fixes it. The **Xposed hook** row asks the hook itself: it publishes
`Settings.Global → bbbar_hook_beat` (SystemUI holds `WRITE_SECURE_SETTINGS` here) every 30 s, and the
row shows `loaded` when that heartbeat is fresh, `not loaded (last beat Ns ago)` when SystemUI
restarted without the hook, and `off (kill switch)` when the file exists. A logcat attach line is
only the fallback for a SystemUI that has not been restarted since this build. Before that it read
the *last* `hook:` line, which is `hook: icon area child …` — so a loaded hook showed as not loaded,
and once the log rotated there was nothing to find at all. A file kill switch stays available:

```bash
touch /data/local/tmp/bbbar_hook_off   # SystemUI loads with no hook at all
```

The same hook also *stops the draw*. Alpha and visibility are a race: SystemUI rewrites both while
the screen turns off, and the frame in between is exactly where the phone's own battery and Wi-Fi
flashed through under the pill. So the views that carry them (`StatusIconContainer`,
`StatusBarIconView`, the Nothing-specific wrappers, and every class actually found inside
`system_icon_area`) get `draw` / `dispatchDraw` hooked, and the call is skipped while the pill owns
that corner. A skipped draw cannot be undone by an alpha pass.

Only the **void** overloads may be skipped. `View` also has
`draw(Canvas, ViewGroup, long) -> boolean`, which `ViewGroup.drawChild` reads to know whether the
child painted itself: cancelling it leaves drawChild unboxing a `null` into a boolean, which is a
hard `NullPointerException` in the middle of a frame. That crash looped SystemUI on boot, and LSPosed
answers a boot crash loop with **safe mode** (every module off until it is cleared). The hook now
walks `declaredMethods` and hooks only methods whose return type is `void`.

The keyguard draws its own status bar, with its own icon views, from a separate tree — which is why
the phone's battery and Wi-Fi came back on the lock screen after the strip's copy was hidden. The
same hook now hooks the keyguard bar's constructor too and registers its icon area, so both copies
follow the same switch.

Three surfaces, each with its own geometry:

| Surface | What it is | Where |
| --- | --- | --- |
| Lockscreen pill | 340 × 68 dp, radius 40, 150 dp above the bottom | bottom centre, clear of the fingerprint circle |
| Status bar pill | 160 × 27 dp, radius 19, 20 dp from the left (clock off) | the left end of the strip, with Android's clock hidden — or the right end of the strip, in Android's battery / network corner |
| Expanded popup | 393 × 120 dp, radius 32 | drops out of the status bar pill; auto-centred under the status bar |

Only one collapsed surface is on screen at a time. Unlocking swaps the lock pill for the status
pill; locking reverses it, staged as `bar pill off → 450 ms → lock pill on` so the lock pill never
lands inside the screen-off animation. Tapping a collapsed pill opens its expanded form; tapping
anywhere outside closes it (taps inside no longer do).

The status pill's window is **8 dp taller than the card at the top and the bottom**, with the card
drawn inside it: a 27 dp bar is a small target for a thumb, and a tap that lands just above or
below the shape belongs to the pill, not to the status bar underneath. The card itself is drawn at
the tuned size and place — the padding is invisible and never moves the pill.

Geometry comes from live window insets (`statusBars` top, `displayCutout`) and the tuned dp values
below, never from hardcoded screen pixels. The status bar pill's width is fitted to its line
(maximum is the tuned width) and **morphs** to the new width over 230 ms instead of snapping, so a
new title, a timer appearing or the card going away reads as the same shape stretching. A change
that arrives mid-morph retargets from the width on screen; a settings change keeps the current
width for its first frame, so the pill never flashes wide.

Every number in the settings screen is both a slider and a text field. A slider over 0–1000 dp
cannot be landed on an exact value by hand, so the value beside each title can be typed: a number,
`-1` or `auto` (both mean "automatic"), committed with done or by tapping away, clamped to that
control's range, and the thumb follows it. `prefs dump` over adb shows what was stored.

## Strip placement, the pill clock, and measure mode

**Placement** (Look → Status bar pill) has three values, and each one decides both where the pill is
and what the hook does to Android's strip:

| Placement | Where | Hook |
| --- | --- | --- |
| **Right** | Android's battery / network corner, `sbRightDp` from the right | hides `system_icon_area` and the privacy dot (each unless its own switch is off), restores the notification icons |
| **Left** | over the notification icons, at `sbLeftDp` (or just past the clock) | leaves the right cluster and the clock alone, hides the notification icons |
| **Left, no clock** (default) | the left end, `sbRightDp` **read as a left margin** | hides the clock (portrait), so the pill has that whole side |

Right and left clock-off are the same layout mirrored: width, height, top and margin are the same
numbers, which is why switching between them does not re-tune anything. Landscape keeps the right
end in all three, so its clock stays. The clock-off layout also has no strip pill on the keyguard:
the keyguard draws its own bar, at its own clock, so a pill there would be a second status bar over
the first.

The clock is suppressed where alpha alone loses a frame: the hook keeps the clock's `INVISIBLE`
state and also skips the draw calls of that one instance (the class is `Clock`, a `TextView`, so the
skip lives on `onDraw` and is gated on the instance). SystemUI re-shows the clock around the shade
and screen-on, and that pass was the split second the time appeared before the next mask tick.

In the right-end layout the pill draws its own battery and signal glyphs from the signal level
SystemUI publishes, keeps its right edge fixed and opens its expanded card centred on the screen.
The glyphs are separated from the line by a short rounded hairline. The network glyph follows the
connection: Wi-Fi when the phone is on Wi-Fi (even with mobile data also up), the cellular bars when
only mobile data is up, and nothing at all when it is offline - the battery is always drawn. Which
glyph leads is a setting (`statusIconOrder`, signal first by default).

The camera / microphone privacy indicator lives in that corner, and its own window is above the
overlay, so it would sit on top of the pill. While the right-end layout is on, the hook hides it
with the rest of the cluster (`privacy_chip_container`, or any `*Privacy*` view on a build that
places it elsewhere), and restores it untouched otherwise.

**When idle** decides what the pill says with nothing active: **Text** (the placeholder), **Clock**
(the time, replacing the placeholder) or **Clock + text** (the time with the placeholder after it —
the default). The time uses the bar's own `DateFormat.getTimeFormat` and the pill's text paint,
repaints on the minute boundary, and the pill refits its width to it. A live card always wins: the
clock is only ever the idle line. Clock or clock + text is what makes **Left, no clock** coherent -
the pill is then the strip's clock instead of sitting beside one.

**Measure mode (outline only)** (Look → Surfaces) draws every surface as an empty outline: no
text, artwork, progress or glyphs, and the surfaces stay up with nothing active. It is for setting
sizes, edges and radii against the real screen instead of the preview. It can also be toggled over
adb with `--ez outlineMode true`.

The stored configuration is versioned (`configVersion`). A file written by an older build is
**rewritten from this build's complete defaults** on the next process start, so geometry dialled
in for the old layout cannot leave the new one half-configured. The status pill's numbers are also
clamped to what the strip can hold (40–1000 × 12–96 dp), so a stale slider extreme cannot make it
cover the screen.

## The lock screen's strip pill

The status pill stays up on the lock screen whenever the bar itself is visible, but there it carries
the phone's status icons and nothing else: battery, Wi-Fi or cellular (whichever is actually up), and
earbuds when a Bluetooth audio device is connected. The activity card at the bottom already says
what is playing, so the strip is the readout the system icons used to be.

Locking morphs the pill rather than swapping it: the line and its leading glyph fade out, the status
cluster slides from the right end to the middle of the shape, the progress strip and the divider go
with the line, and the window width animates down to the cluster. Unlocking reverses it. The strip
pill on the lock screen is a readout, not a control: the window is `FLAG_NOT_TOUCHABLE` and the view
ignores touches while the keyguard is up. Opening the shade while locked removes it altogether — the
shade has its own header, and there is nothing the pill could open.

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
down while the **Everything off** switch is on.

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

- **Home** — running/stopped with the reason, the **preview** and the idle line.
  The preview is not a mock-up: it is the pill itself, fed by `NowBarState.items()`, so it shows the
  card that is on the bar at this moment, in the same rank order, at the same sizes, and follows it
  within a second (a new track, a timer tick, a card swiped away). Tap it to see the expanded card,
  or the idle line when nothing is active.
- **Look** — the three surface switches, then one sub-page per surface plus the look pages:
  **Lockscreen pill**, **Status bar pill**, **Expanded card**, **Media artwork**, **Text and motion**,
  **Always-on display** and **Idle line**. Sliders run `0…1000 dp`; `-1` on a left/top value means
  *auto*. The status bar page shows the placement, the idle line, and the edge control that
  belongs to that placement (left edge, or the margin read from the anchored side), plus the
  battery / signal order. Its three placement buttons read **Right**, **Left** and **Left, no
  clock**; the caption under them spells out the difference. The pill in the shade / Quick
  Settings is the same one, at the same place, so there are no shade-specific controls.
- **Items** — the item switches (media, timers, alarms, live updates), the **ranking** list with
  ▲/▼, notification access, the app exclusions, and **simulate**: a fake card of each kind drawn on
  the real pill, for judging a look without waiting for an activity.
- **Sys** — the **Everything off** switch, the LSPosed hook (its state row and the three hiders for
  the strip) and **Restart SystemUI**.
- **Debug** — the plumbing (accessibility overlay, root watcher and its switch, fallback overlay,
  notification access, screen and clock geometry, windows on screen) and the log level.
- **About** — version, package, repository link, and **Reset all settings**: confirm, then every
  stored value goes back to this build's defaults and the pill reloads.

Turning **Sys → Everything off** on, or a surface off, takes effect immediately: the service removes that
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
  --es sbSide leftNoClock --es idleContent clockThenLabel

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

The leading glyph - an app icon, artwork, the KDE Connect play mark - is the same idea in one
place: it is drawn in grayscale on the AOD, and only the pixels under a progress dot are one-bit.
Each of those flips the opposite way to the colour the mark rounds to on a black-and-white copy of
itself, so a light pixel gets a dark dot and a dark pixel a light one (issue #11). A colourful app
icon therefore still looks like itself, with the played part reading as a dotted copy of it.

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
  NowBarHook.kt          LSPosed entry: hides Android's icons, publishes bar/clock/signal state
```

## Known limits

- **No strip pill on the AOD.** A dozing display reports as a hidden status bar, and SystemUI's
  state flaps around that transition, which made the strip pill add and remove itself in a loop.
  It is kept out of the AOD entirely; the activity card is the only thing drawn there, in the line
  + dots look. Leaving the strip needs two agreeing reads, so a single flap cannot bring it back.
- **No per-app allow / deny list yet.** Any app that posts a media session, a chronometer or a
  progress notification becomes a card; individual apps cannot be excluded.
- **The idle pill is a status bar surface only.** Lockscreen and popup disappear when nothing is
  active. The status bar pill never does: it replaces Android's battery and signal icons, so it
  stays on the strip with the placeholder label as its text, or with no text at all when that label
  is blank. There is no switch that hides it; the old Text / Logo / Hide switch is gone, because a
  stored "Hide" looked exactly like a dead bar and nothing ever reset it.
- **The LSPosed hook only hides**, it never draws or lays out: it sets `alpha` / `visibility` on the
  status bar's icon areas and skips their `draw` calls. Everything the pill shows comes from the
  accessibility overlay, which is why a changed Nothing OS build cannot break the pill itself. It
  does hook `draw` on view classes it finds inside those areas, so a build that moves the icons
  somewhere else simply goes unhidden rather than breaking.
- **A module crash at boot puts LSPosed in safe mode.** Safe mode disables every module until it is
  cleared, which looks exactly like "the hook stopped working": no `bbbar_hook_beat`, and the
  phone's own battery / Wi-Fi stay in the strip. Check the beat value before blaming the build.
- **No root daemon.** The `app_process` overlay approach was abandoned: `WindowManager` rejects its
  window with `Unknown pid … uid=0`. Root is used only for the logcat wakeup stream and the two
  `Settings.Global` switches.
- **The published APK is signed with the local debug key.** It is a personal sideload build, not a
  Play Store artefact; install it over the previous build with `adb install -r`.
