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
the module must be enabled for `com.android.systemui` in LSPosed. A file kill switch stays available:

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
below, never from hardcoded screen pixels.

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
the pill vanish and reappear.

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
| Swipe left / right | expanded or lockscreen card, media in front | previous / next track |
| Swipe up / down | expanded or lockscreen card | cycle the stack; the new front card is also what the status bar pill shows |

Long lines scroll instead of being cut: a 1.4 s hold, a steady 34 dp/s slide, a 1.1 s hold, then
loop, with the cut ends faded into the card (`scrollText` turns it off). A track change restarts the
line only when the title, artist or cover pixels actually changed — the notification store rebuilds
every item about once a second, so the old identity check restarted the scroll mid-song.

## Settings app

Opening **bbbar** shows a Material 3 screen with Material You colours (from the wallpaper, light and
dark). Every control writes into the shared `tuning` prefs that the service watches, so edits apply
live.

- **Status** — running/stopped, the **Pill enabled** master switch, accessibility state, root
  watcher state, the fallback overlay switch, and a shortcut to Accessibility settings.
- **Surfaces** — one card each for the lockscreen pill, the status bar pill and the expanded popup,
  each with its own on/off switch, a live scaled preview, a `width × height` caption and the dp
  sliders. Sliders run `0…1000 dp` so nothing is capped; `-1` on a left/top value means *auto*.
- **Content** — the item switches (media, timers, live updates), the **ranking** list with ▲/▼
  buttons, notification access state and a link to grant it, and the placeholder label.
- **Maintenance** — open the floating tuner (draggable panel, same prefs), reset to defaults.

Turning the master switch off, or a surface off, takes effect immediately: the service removes
that window at once instead of waiting for the next lock/unlock cycle, and switching it back on
puts it up again.

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
  --ez dump true      # log every stored value
```

## Logs

```bash
adb logcat -s NowBar                 # everything, tag NowBar
adb logcat -s NowBar | grep handover # unlock/lock swaps and their timings
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
  active; the status bar pill can show the placeholder line, the logo, or nothing.
- **The LSPosed hook only hides**, it never draws or lays out: it sets `alpha` on the status bar's
  notification-icon area children. Everything the pill shows comes from the accessibility overlay,
  which is why a changed Nothing OS build cannot break the pill itself.
- **No root daemon.** The `app_process` overlay approach was abandoned: `WindowManager` rejects its
  window with `Unknown pid … uid=0`. Root is used only for the logcat wakeup stream and the two
  `Settings.Global` switches.
- **The published APK is signed with the local debug key.** It is a personal sideload build, not a
  Play Store artefact; install it over the previous build with `adb install -r`.
