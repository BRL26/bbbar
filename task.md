# bbbar — task list

Living work list. `done` means verified on the device (Nothing Phone 3a, Nothing OS 5.0 / Android 17).

## Current state

**v1.0.0, published** (`github.com/BRL26/bbbar`, tag `v1.0.0`). Base is settled: the accessibility
overlay draws the lockscreen pill, hands over to the status bar pill on unlock, and both expand on
tap. The pill shows real items — media, timer/stopwatch, live progress — as a stack of up to several
cards, with controls only in the expanded views and swipes to change track or card. Master and
per-surface switches work live. Android's own notification icons and Live Update chips are hidden
from inside SystemUI by the LSPosed hook.

Enable / disable:

- **Settings → Accessibility → bbbar → Use bbbar**, or
- ```bash
  adb shell settings put secure enabled_accessibility_services \
      dev.brl.nowbar.module/dev.brl.nowbar.module.NowBarA11yService
  adb shell settings put secure accessibility_enabled 1
  ```

## Done (verified on the device)

### Pill and surfaces
- [x] Pill as `TYPE_ACCESSIBILITY_OVERLAY`, above the keyguard (`Window #2`)
- [x] Lockscreen pill geometry: 340 × 68 dp, radius 40, 150 dp above the bottom
- [x] Status bar pill: 106 × 27 dp, radius 19, left 75, top auto — covers the notification icons
- [x] Expanded popup opened from the mini pill's own rect; the mini pill stays visible
- [x] Lock pill expands to 2.5× on tap, width clamped to the screen
- [x] Tap-outside dismissal via `FLAG_WATCH_OUTSIDE_TOUCH` + `ACTION_OUTSIDE` (the full-screen
      catcher window is gone); tapping inside no longer closes
- [x] Lock pill window uses TOP gravity from creation, so the handover slide is a pure y animation
- [x] Geometry from live insets (`statusBars`, `displayCutout`), never hardcoded pixels

### Handover / triggers
- [x] Root log watcher (`su -c logcat …`) as the fast wakeup, with an 8 ms confirmation poll
- [x] `ACTION_USER_PRESENT` → `UserPresentReceiver` → `NowBarBridge` as an instant path
- [x] Screen off handled on `ACTION_SCREEN_OFF` itself, not when the keyguard reports locked
- [x] Lock direction staged: `bar pill off → 450 ms → lock pill on` (`SCREEN_OFF_LOCK_DELAY_MS`)
- [x] AOD unlock waits for the display to be interactive plus two rendered frames before sliding
- [x] One handover per 400 ms (`handoverAllowed()`), so the flag + broadcasts + root log do not
      fight each other
- [x] A lone `isKeyguardLocked=false` read is ignored (needs a streak, or `USER_PRESENT`)

### Fixed bugs worth remembering
- [x] **Triple flicker on unlock**: the incoming pill was added at alpha 1 and *then* set to 0.
      Alpha 0 now goes into `LayoutParams` before `addView`.
- [x] **Animations cancelled each other**: every `animateY` stored its animator in `lockAnimator`,
      so a running status bar slide died mid-flight (the jumps). Each surface owns its animator.
- [x] **Animations stopped a pixel short**: int rounding ended the loop before 1.0. Each animation
      now lands exactly on its target once, at the end.
- [x] **Broadcasts never arrived**: the receiver was registered `RECEIVER_NOT_EXPORTED`, which
      silently dropped `SCREEN_OFF` / `SCREEN_ON` / `USER_PRESENT`. Registered plainly now.
- [x] **Handover was ~1 s late**: replaced with `removeViewImmediate` + a 120 ms fade on the
      incoming pill (`lock window removed in 28ms`, `first frame 44ms after the change`).

### Content (items)
- [x] `NowBarItem` model + `NowBarState` priority holder (media → timer → live → charging)
- [x] **Ranking**: item order is editable in settings (▲/▼), and `NowBarState.items()` returns
      items in that order, skipping switched-off sources
- [x] Per-item on/off switches in settings (`featureMedia` / `featureTimers` / `featureLive`)
- [x] Charging item removed at the owner's request (source, glyph, toggle and preview all gone)
- [x] Media item from `EXTRA_MEDIA_SESSION` notifications: title, artist, large icon as art,
      play/pause taken from the notification's own action (no media permission)
- [x] Timer/stopwatch from `EXTRA_SHOW_CHRONOMETER`, counted down locally once a second
- [x] Live update from any progress notification
- [x] Pill renders art (circular), bold title, second line when expanded, thin progress bar
- [x] Collapsed pills show no control; expanded views show play/pause and dispatch it
- [x] `NotificationListenerService` + manifest entry; settings shows the grant state and links to it

### Settings / project revamp
- [x] Settings rewrapped: **Status** hero, **Surfaces** (one card per surface with a live scaled
      preview and a `width × height` caption), **Content**, **Maintenance**
- [x] Sliders still `0…1000 dp`, `-1` shown as *auto* on left/top values, edits apply live
- [x] **Master switch** and **per-surface switches**, enforced immediately both ways
- [x] **Items** card with media / timers / live / charging switches and notification access state
- [x] Removed the debug dot (`DebugDot.kt` and every reference in the service)
- [x] Removed dead code: `RootOverlayDaemon.kt`, `RootController.kt` (the `app_process` overlay
      path `WindowManager` rejected), and the root branch in `TuningReceiver`
- [x] README rewritten to match the project as it is (three surfaces, real triggers, real limits)

### Research and sources (added after reading the other islands)
- [x] Read `ajitpal/dynamic-island-android`, `ProNoob2450/Android-Island`, `Islandify` for their
      activity ingestion, priority queue and cutout anchoring; notes in `docs/ACTIVITIES.md`
- [x] Live updates also accept **promoted** notifications and the categories call / navigation /
      progress / transport, with `android.shortCriticalText` as the line (Maps, rides, calls)
- [x] A card always has controls when expanded: media transport, otherwise the source app's own
      notification actions (`PendingIntent.send()`), so Pause / Add 1 min / Answer work
- [x] Timer state from the metric sort key: EXPIRED / RESET are not cards, PAUSED freezes the
      countdown
- [x] Settings "Fake activities": a switch per item type puts that card on the real pill
- [x] Status bar pill shrinks to its line (cut off from the right) instead of keeping the tuned
      width, including when it falls back to the idle label
- [x] Progress is a hairline along the bottom two pixels of the card, so the short status pill gets
      one too and the text keeps the full height to centre in
- [x] An idle pill (placeholder text or logo) ignores taps and swipes: nothing to expand into
- [x] A new item adds a missing surface by itself - "Apply settings" is no longer needed to make
      the pill appear
- [x] Icon is centred in the card's content band, not parked at the top padding
- [x] Status card asks AccessibilityManager instead of a static, so it stops reporting "Stopped"
      while the overlay is running
- [x] Long lines scroll: hold 1.4 s, slide the hidden part into view at 34 dp/s, hold 1.1 s, loop,
      with the cut ends faded; off switch cuts with an ellipsis instead (`scrollText`)
- [x] Status bar pill width rule: a line that fits shrinks the pill to it (as far as a circle), a
      line that does not fit keeps the tuned width and scrolls inside it
- [x] **Android's own notification icons hidden** from inside SystemUI (`NowBarHook`, LSPosed
      scope `com.android.systemui`): verified on device — icons gone, pill untouched
- [x] **Android's own Live Update chips hidden** the same way, on its own switch
- [x] Both switches write `Settings.Global` (`bbbar_hide_notif_icons`, `bbbar_hide_live_chips`)
      through root; the hook watches them with a `ContentObserver`, so no restart to toggle
- [x] Settings shows whether the hook is really loaded (reads the SystemUI log through root), plus
      **Restart SystemUI** in Test tooling for the first load after an install

### 1.0.0 additions (verified on the device)
- [x] Media card shows the **player's own app icon** next to the line (cached per package), and the
      status bar pill's width calculation gives the icon its room
- [x] **No content cross-fade**: a track change no longer fades the card, and the*scroll* restarts
      only when title / artist / cover pixels really changed (`Bitmap.sameAs`), not when the store
      re-decoded the same cover a second later
- [x] Media transport in the expanded views: previous / play-pause / next through the notification's
      own `MediaSession` token
- [x] Any other card's expanded controls come from its own notification actions
- [x] **Stack**: every active item is a card, each peeking out under the one in front, and each
      hidden card shows its own title in its strip
- [x] Swipe up / down cycles the stack on the expanded views and on the lockscreen card; the new
      front card is also what the status bar pill shows
- [x] Swipe left / right skips tracks when the media card is in front
- [x] Swipe animations, and taps that leave the card alone
- [x] Long line scrolls (hold 1.4 s → 34 dp/s → hold 1.1 s → loop) with faded cut ends
- [x] Art treatment: normal / tinted / blurred / both, blur strength and tint intensity sliders,
      cropping decided by aspect (never stretched)
- [x] Next-alarm countdown card, only when the alarm is inside the next hour
- [x] Idle look is a status bar setting: placeholder line, logo, or hidden
- [x] "Apply settings" button, because the app's own prefs are not the service's live prefs
- [x] Fake activities from the settings screen drive the real pill

## Next

### Pill content
- [x] **No controls on any collapsed pill** (lockscreen or status bar): text + icon only
- [x] Controls only in expanded views: lockscreen expanded pill and status bar popup
- [x] Media item: album art, title + artist, play/pause, prev, next
- [ ] Media item: shuffle, favourite, output switch
- [x] Timers / stopwatch with pause / resume
- [ ] Alarm ringing / snoozed actions (dismiss, snooze)
- [x] Live updates from any app's progress notification
- [ ] Missed calls, Maps ETA
- [ ] Optional extras (only after approval): VPN/network, torch, DND, calendar

### Activity backlog
See `docs/ACTIVITIES.md` for the researched list: navigation/ride/delivery, call in progress,
multiple live slots, KDE Connect transfers, screen recording, hotspot, Bluetooth audio, Focus,
torch, VPN, next calendar event.

### Multiple activities
- [x] Item stack with more than one active item
- [x] Swipe up/down on the expanded view to cycle, and on the lockscreen card
- [x] Editable ranking list: which item wins in the status bar by default
- [x] Manual cycle order until the activity changes; ranking is the fallback
- [ ] Max visible items, per-app allow/deny lists — **next up**
- [ ] The idle line (placeholder / logo) kept in apps, with per-app exclusions

### Look and motion
- [ ] Material You colour roles for the pill itself (primary / surface / on-surface)
- [x] Blurred / tinted album art with strength sliders
- [ ] Reduced-motion and "animations off" respected
- [ ] Contrast check on text/icons with a readable fallback

### Settings app content
- [ ] Master on/off and per-surface switches
- [ ] Feature toggles for every item type
- [ ] Per-app live-update allow list, blacklist
- [ ] Ranking order editor
- [ ] Size presets (small/medium/large) on top of the dp sliders
- [ ] Colour mode: Material You / system accent / fixed colour / palette seed
- [ ] Animation mode, popup style, timeouts, haptics, debug mode with layout bounds
- [ ] Floating tuner stays as a dev tool, no longer the main UI

## Milestones from the brief

| # | Milestone | Status |
| --- | --- | --- |
| M1 | Skeleton, view discovery, plain pill at bottom centre | done as an overlay app, not an LSPosed module |
| M2 | Settings shell + shared storage read live | **done** (shell, sections, live previews, live prefs) |
| M3 | Media and timers/alarms with expand/collapse | **done**: media + timer + stopwatch + expand/collapse (alarms pending) |
| M4 | Live updates, per-app control, stacking, AOD passive mode | not started |
| M5 | Status bar popup / Dynamic Island behaviour | popup + positioning done, no live content |
| M6 | Charging, missed calls, VPN, torch, Maps ETA | not started |
| M7 | Appear/disappear rules, auto-hide, dismiss, haptics | not started |
| M8 | Material You + accent colours + contrast | theme in the app done, pill colours not started |
| M9 | Polish: radius presets, debug mode, reduced motion | not started |

## Known limits / deviations from the brief

- **AOD is not covered.** A screencap of AOD shows only the fingerprint ring: an accessibility
  overlay is not composited while the display dozes. Both handovers are timed against the
  *display*, never the doze screen.
- **`KeyguardManager.addKeyguardLockedStateListener` throws** on this build
  (`enforceSubscribeToKeyguardLockedStatePermission`), hence the root logcat watcher.
- **The LSPosed hook only hides** (`NowBarHook.kt`): repeated SystemUI restarts pushed LSPosed into
  safe mode once and a restart cleared the wallpaper bind, so it never draws and never lays out —
  it only sets `alpha` on the status bar's notification icon area children. Everything the pill
  shows still comes from the accessibility overlay.
- **The hook needs the module enabled for `com.android.systemui`** in LSPosed, and one SystemUI
  restart after installing a new build. `touch /data/local/tmp/bbbar_hook_off` disables it entirely.
- **No root overlay daemon**: `app_process` is not registered with ActivityManager, so
  `WindowManager` refuses its window (`Unknown pid … uid=0`). Root is only the logcat wakeup.
