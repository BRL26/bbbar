# bbbar — Now Bar overlay for Nothing Phone (3a)

A Now Bar–style pill for Nothing OS 5 (Android 17). **No LSPosed, no SystemUI hooking.**

Formerly "Now Bar": the project and repo are now **bbbar**, the app label is `bbbar`. The package
is still `dev.brl.nowbar.module` (renaming it would unbind the accessibility service and drop the
stored settings), see `task.md`.

The pill is our own window:

| Path | Window type | Shows over apps | Shows on lock screen | Needs |
| --- | --- | --- | --- | --- |
| Accessibility overlay (`NowBarA11yService`) | `TYPE_ACCESSIBILITY_OVERLAY` | yes | **yes** | Accessibility service enabled |
| Normal overlay (`OverlayService`) | `TYPE_APPLICATION_OVERLAY` | yes | **no** | "Display over other apps" |
| Root overlay (`RootOverlayDaemon`) | `TYPE_SYSTEM_ERROR` → `TYPE_SYSTEM_OVERLAY` → `TYPE_SYSTEM_ALERT` | yes | yes | root (`su`) |

**Use the accessibility overlay.** It wins on the lock screen without root: in the window stack it
is `Window #2`, above `NotificationShade` (`Window #7`).

`TYPE_APPLICATION_OVERLAY` is layered *below* the keyguard — that's a platform rule, not a bug,
and why the normal overlay is invisible while locked. Only a system-uid window sits above the
keyguard, so the lock-screen pill comes from the root daemon.

## Build

```bash
cd Projects/nowbar-module
~/tools/gradle/current/bin/gradle assembleDebug
# -> module/build/outputs/apk/debug/module-debug.apk
```

AGP 9.5.0 with built-in Kotlin (do **not** add the Kotlin Gradle plugin), Gradle 9.8.2,
compileSdk/targetSdk 37, minSdk 31.

## Install and run

```bash
adb install -r module/build/outputs/apk/debug/module-debug.apk
adb shell appops set dev.brl.nowbar.module SYSTEM_ALERT_WINDOW allow
```

Open **bbbar** in the app drawer. The settings screen is Material 3 with Material You colours
(from the wallpaper, light and dark), one card per surface, and a live preview of the lockscreen
pill that is redrawn as you drag:

1. **Status** — whether the accessibility overlay and the root unlock watcher are running, a
   switch for the plain `TYPE_APPLICATION_OVERLAY` path (only that path needs
   "Draw over other apps"), and a shortcut to `Settings → Accessibility`.
2. **Preview** — the lockscreen pill at its configured shape, scaled to the card.
3. **Lockscreen pill / Status bar pill / Expanded popup** — the dp sliders for each surface,
   `0…1000 dp` so nothing is capped; every change is written to the shared prefs the overlay
   service watches, so it applies live.
4. **Label** — placeholder text until real items replace it.
5. **Test tooling** — `Open floating tuner` (the old draggable panel) and `Reset to defaults`.

### Current geometry (baked in)

The owner's dialled-in values are the `TuningStore.DEFAULT_*` constants, so a fresh install
starts here with no tuning:

| value | dp | px on this device (1080×2392 @ 420 dpi) |
|---|---|---|
| width | 340 | 893 |
| height | 68 | 179 |
| text | 21 | — |
| corner radius | 40 | 105 |
| from bottom | 150 | 394 |

The unlocked **status bar pill** has its own numbers (`sbWidthDp`, `sbHeightDp`, `sbTextDp`,
`sbRadiusDp`, `sbLeftDp`, `sbTopDp`):

| value | dp | px on this device |
|---|---|---|
| width | 125 | 328 |
| height | 27 | 71 |
| text | 13 | — |
| corner radius | 19 | 50 |
| left edge | 65 | 171 (clear of the clock, over the notification icons) |
| top edge | auto | 23 (vertically centred in the 126 px status bar) |

Only one surface is on screen at a time: the service swaps the keyguard pill for the status
bar pill on `ACTION_USER_PRESENT`, `ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF`, and re-checks
`KeyguardManager.isKeyguardLocked` each time. Geometry comes from live window insets
(`statusBars` top = 126 px, `displayCutout` = x 454–540), never from hardcoded screen values.

### Third surface: the expanded popup

Tapping the status bar pill adds a third window (`popup*` keys, defaults `320 x 120 dp`, text
`16 dp`, radius `32 dp`). It is the surface for detail and controls — the status bar pill itself
stays text + image only. `popupLeftDp` / `popupTopDp` of `-1` mean auto: horizontally centred,
just below the status bar (`statusBars` top + 8 dp). Tap the popup, tap the pill again, or lock
the phone to close it.

### Motion

- **Lock pill tap** — animates to 2.5x (280 ms, slight overshoot); tap again to settle back.
  Width is clamped to the screen: 340 dp x 2.5 is 850 dp on a 411 dp display.
- **Status bar pill tap** — the popup window is created at the mini pill's own rect (same x/y,
  same size) and animated to its full size and position, so it reads as the pill sizing up. The
  mini pill stays visible underneath; tapping the popup shrinks it back into the pill's rect and
  is then removed, leaving the pill where it was.
- **Unlock** — the keyguard pill slides up off the top (180 ms) and the status pill slides in from
  the top; locking reverses it: the status pill slides up and away, the keyguard pill slides in
  from the bottom. Started by the root keyguard watcher below; `USER_PRESENT`, accessibility
  window-state events and a poll that runs at 60 ms for 5 s after the screen comes on (150 ms while the
  keyguard is up, 1 s otherwise), so the handover starts on the unlock itself.

### Status bar pill (unlocked)

Shown instead of the keyguard pill as soon as the keyguard is not locked. It is drawn where the
notification icons live and covers them — its background is fully opaque for that reason —
starting just past the clock and stopping short of the camera cutout.

| value | dp | px on this device |
|---|---|---|
| width | 108 | 284 |
| height | 30 | 79 |
| text | 12 | — |
| corner radius | 15 | 39 |
| left edge | 56 | 147 |
| top edge | auto | 23 (centred in the 126 px status bar) |

Both surfaces are driven from live window insets: status bar height (126 px) and the camera
cutout (`454..540` px) are read at runtime, never hardcoded. `sbLeftDp` / `sbTopDp` of `-1`
mean "place automatically" (right of the cutout, vertically centred). The switch between
surfaces re-checks `KeyguardManager.isKeyguardLocked` on `USER_PRESENT`, `SCREEN_ON` and
`SCREEN_OFF`. Tapping the status bar pill logs and taps haptics; expand/collapse is M5.

### Unlock/lock trigger (root)

Accessibility and broadcasts are too slow to feel instant, so the trigger is now a root log
watcher (`RootKeyguardWatcher`): it runs

```
su -c "logcat -b main -b events -v brief -s KeyguardViewMediator KeyguardServiceDelegate \
        KeyguardStateMonitor StatusBarStateControllerImpl NotificationShadeWindowController"
```

and every line is used only as a wakeup. Real state still comes from
`KeyguardManager.isKeyguardLocked`, which is then checked every 8 ms instead of every 150–1000 ms,
so the slide starts on the frame the platform's own flag flips (`root keyguard signal confirmed the
lock change (locked=…) Nms after the first keyguard line`). One burst of keyguard lines is logged
once, not thirty times. Without root the stream fails, that failure is logged, and the poll remains
the fallback. Retries back off from 1.5 s to 30 s.

## Root daemon

`RootController` writes the values to `files/tuning.json` and runs:

```
su -c "CLASSPATH=<base.apk> app_process /system/bin --nice-name=nowbar_root \
        dev.brl.nowbar.module.RootOverlayDaemon <tuning.json>"
```

`RootOverlayDaemon` gets a system `Context` via `ActivityThread.systemMain()`, adds the window,
and re-reads the JSON every 400 ms so the tuner stays live. It logs under tag `NowBarRoot`;
if a window type is refused it says which one and falls back to the next.

## Logs

```bash
adb logcat -s NowBar        # app + services
adb logcat -s NowBarRoot    # root daemon
adb logcat -s NowBar su     # su output piped back into the app log
```

## Tuning over adb (temporary)

```bash
# change pill numbers
adb shell am broadcast -a dev.brl.nowbar.module.SET -n dev.brl.nowbar.module/.TuningReceiver \
  --ei widthDp 280 --ei heightDp 90 --ei bottomDp 300

# start / stop the root daemon (Magisk prompts once)
adb shell am broadcast -a dev.brl.nowbar.module.SET -n dev.brl.nowbar.module/.TuningReceiver --ez root true
```

The service and receiver are `exported="true"` only while tuning this by hand; they get locked
down when the values are made permanent.

## Known state

- Accessibility overlay: **verified** on the lock screen (`isKeyguardShowing=true`, pill visible
  above the fingerprint circle).
- Normal overlay: shows over apps only — `NotificationShade` is above it, by platform design.
- Root daemon: builds and starts under `su`, but `WindowManager` rejects the window with
  `IllegalStateException: Unknown pid=… uid=0` because a bare `app_process` is not registered with
  ActivityManager, so `openSession` fails. Root is **not needed** for the lock-screen pill now;
  the daemon is kept for reference only.
