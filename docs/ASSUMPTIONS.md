# Assumptions — verified / unverified

Running list from the project plan. Updated after each milestone.

Device: Nothing Phone (3a), model `A059`, Nothing OS 5.0 on Android 17
(`ro.build.version.release=17`, `sdk=37`, build `C5.0-261023-2146`), SystemUI `17.0.0`
(`/system_ext/priv-app/SystemUI/SystemUI.apk`).

Root stack on the device: Magisk (`magiskd` running, `su` at `/product/bin/su`), Zygisk
(`zygiskd64`), LSPosed (`lspd` running as uid 0; `LSPosedFramework` actively injecting a module
into the `system` process). `su` is **denied for the adb shell user** by policy.

---

## Environment

| # | Assumption | Status | Evidence |
| --- | --- | --- | --- |
| E1 | LSPosed 2.21 (7912) works on Android 17 / Nothing OS 5.0 | **verified** | `LSPosedFramework: (system)[com.souleven.nothingos,XposedBridge,…] NothingTweaks:` lines in logcat; `lspd` running as uid 0 |
| E2 | Zygisk is enabled | **verified** | `zygiskd64` process running |
| E3 | A freshly installed module is detected by the framework | **verified** | installing `dev.brl.nowbar.module` made LSPosed post its `lsposed_module_updated` notification (uid 1000) |
| E4 | The LSPosed manager is launchable from adb/without user interaction | **false** | no `org.lsposed.manager` package exists; the manager runs *parasitically* inside `com.android.shell` (`Window{… com.android.shell/org.lsposed.manager.ui.activity.MainActivity}`). Enabling the module needs a tap in that UI. |
| E5 | `su` is available to the adb shell for config/restarts | **false** | `adb shell su -c id` → `Permission denied`; `/data/adb` unreadable. Needs a Magisk policy change by the owner. |

## Product assumptions from the plan

| # | Assumption | Status | Notes |
| --- | --- | --- | --- |
| M1 | SystemUI class names and view hierarchy on Nothing OS 5.0 | **verified** | module loaded into SystemUI and matched: `KeyguardRootView` (`#keyguard_root_view`, 1080x2392), `SceneWindowRootView` (`#scene_window_root`, the Android 17 scene host the pill actually attaches to), `NotificationShadeWindowView`, `PhoneStatusBarView` (`#status_bar`, 1080x126), `UdfpsTouchOverlay`, and Nothing's own `com.nothing.systemui.biometrics.NTUdfpsView` |
| M2 | Nothing's lock screen and AOD allow custom views in that spot | **verified for keyguard, unverified for AOD** | pill added as a child of `SceneWindowRootView` draws on the lock screen (`PillView drew: 693x200 visible=true`, on screen at 193,1704) and is visible in a screenshot; AOD window not yet exercised |
| M3 | Under-screen fingerprint sensor affects pill placement | **verified** | lock pill at 340x68 dp, `150 dp` above the bottom edge (`893x179 px` at `y=394 px`), sits above the UDFPS ring with a visible gap in captures; offset is adjustable (`bottomDp`) |
| M4 | Camera cutout bounds can be read at runtime and cleared by a status-bar pop-up | **partially verified** | cutout read at runtime from `WindowInsets.displayCutout` -> `454..540 px`, status bar height from `WindowInsets.Type.statusBars()` -> `126 px`; the status bar pill clears the cutout and the right-hand icons. The Dynamic Island-style pop-up itself is not built (M5). |
| A1 | The pill can be drawn above the keyguard without LSPosed or root | **verified** | a `TYPE_ACCESSIBILITY_OVERLAY` window is `Window #2`, above `NotificationShade` (`#7`) and `NTUdfpsView` (`#3`), with `isKeyguardShowing=true`. An app-level `TYPE_APPLICATION_OVERLAY` is below the shade by platform design. |
| A2 | Both surfaces can be driven from keyguard state alone | **verified** | `KeyguardManager.isKeyguardLocked` re-checked on `USER_PRESENT` / `SCREEN_ON` / `SCREEN_OFF`; log shows `surfaces: keyguardLocked=false` then the status bar pill added at `x=147 y=23` |
| M5 | How Nothing renders Android 17 Live Updates in the status bar / lock screen | **partially verified** | SystemUI is a vendor fork (`vendor__noth__packages__apps__NothingSystemUI`) and renders a SystemUI-drawn promoted chip in the status bar; promoted content for the lock screen exists only as an **AOD** area (`AodPromotedNotificationAreaElementProvider`), no bottom-centre lockscreen pill class. `liveStackOnLockscreen` / `liveTileMap` strings exist but no matching class. |
| M6 | Nothing's wallpaper-based Material You palette is available to SystemUI views | **unverified** | M8 concern |
| M7 | The pill appears/disappears on the same triggers Samsung uses | **unverified** | lifecycle rules are partly proposed and need real activities to test |
| M4b | Status bar pill placement, and swapping it with the keyguard pill | **verified** | `displayCutout` = x 454–540 / y 0–126, `statusBars` top inset = 126 px (48 dp), read from `WindowInsets` with no hardcoded px. The unlocked pill is an `TYPE_ACCESSIBILITY_OVERLAY` window (`Window #2`, above the status bar) at x 184–447, y 13 — clear of the clock and the camera cutout. Surfaces swap on `ACTION_USER_PRESENT` / `SCREEN_ON` / `SCREEN_OFF` **and** a 1 s `KeyguardManager.isKeyguardLocked` poll, because a missed `USER_PRESENT` left the big keyguard pill parked on top of the launcher. |
| M4c | A third, independent popup window can open from the status bar pill | **verified** | tap on the pill adds a second accessibility window (`popup added: 320x120dp at x=120 y=147px`, i.e. centred and 8 dp under the 126 px status bar); it closes on tap, on tap of the pill, or when the keyguard returns. Defaults are placeholders the owner is about to tune with the `popup*` sliders. |
| M4d | The expanded popup should read as the mini pill sizing up | **implemented, verified** | the droplet/neck merge was tried and dropped (the wide body deformed and never joined the rounded pill cleanly). The popup is again its own rounded window, created at the pill's rect and animated from it: `popup expanding from pill 286x71 at 184,47 to 1032x315 at 24,147`, and back with `popup collapsed back into the status bar pill`. |
| M4e | The keyguard pill can expand and hand over to the status pill on unlock | **implemented** | tap expands to 2.5x (width clamped to the screen). Handover is now a slide: keyguard pill → up off the top, status pill → in from the top (and the reverse on lock). |
| M4f | `KeyguardManager.addKeyguardLockedStateListener` works on this build | **false** | registration throws `SecurityException: enforceSubscribeToKeyguardLockedStatePermission` from `WindowManagerService.addKeyguardLockedStateListener` (needs a system-only permission). Substitutes: a root log watcher (below), a manifest `USER_PRESENT` receiver (`UserPresentReceiver` → `NowBarBridge` → service), accessibility window-state events, and a poll that runs at 150 ms while the keyguard is up (1000 ms otherwise) as the failsafe. |
| M4g | Root can read the keyguard log lines fast enough to drive the transition | **verified** | `su -c "logcat -b main -b events -v brief -s KeyguardViewMediator KeyguardServiceDelegate KeyguardStateMonitor StatusBarStateControllerImpl NotificationShadeWindowController"` streams into the service (`root keyguard watcher started`), lines arrive within a few ms of the `KeyguardViewMediator` message, and `KeyguardManager` is then re-checked every 8 ms. Measured: screen-off lock confirmed 572 ms after the first keyguard line (the platform's own `isKeyguardLocked` lag), transition 2 ms later (`status pill sliding up`). `KeyguardViewMediator` logs ~30 lines per unlock, so a burst is logged once. Retries back off 1.5 s → 30 s if the stream dies. |

## Device facts worth keeping

- The status bar pill that Android 17 renders for apps is `PromotedNotificationContentExtractorImpl`
  (`com.android.systemui.statusbar.notification.promoted`), fed by
  `Notification.setRequestPromotedOngoing(true)` + `ProgressStyle`.
- Chip tap is handled by `HeadsUpCoordinator$onPromotedNotificationChipTapEvent`.
- Nothing OS 5 notification stack and Glyph are unrelated to this module.

## Status bar surface (verified on device)

- `WindowInsets.statusBars` top = **126 px (48 dp)**; `WindowInsets.displayCutout` = single hole,
  `Rect(454, 0 – 540, 126)`. Both are read live at runtime — no px values are hardcoded.
- The unlocked pill is an `ACCESSIBILITY_OVERLAY` window (`Window #2`), above the status bar, at
  `x 184–447, y 13` with `100×38 dp` (`263×100 px`). It clears the clock (ends ≈157 px) and stops
  before the cutout (454 px), and covers the notification-icon area, hiding it.
- `TYPE_APPLICATION_OVERLAY` cannot do this job: it is layered below `NotificationShade`, which is
  why the first attempt was invisible on the keyguard.
- Surface switching needed a 1 s `KeyguardManager.isKeyguardLocked` poll: `ACTION_USER_PRESENT`
  alone was missed while the service was binding, leaving the keyguard pill on top of the launcher.
- Wiping app data (`pm clear`) kills the bound accessibility service, and re-writing the same
  `enabled_accessibility_services` value does **not** rebind it; the setting must be cleared first.
