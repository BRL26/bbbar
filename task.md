# bbbar — task list

Living work list. `done` means verified on the device (Nothing Phone 3a, Nothing OS 5.0 / Android 17).

## Repo and naming

- [x] Repo `bbbar` created on GitHub; this project is now named **bbbar**
- [x] App label `bbbar`, Material 3 settings screen
- [ ] Rename the module folder `Projects/nowbar-module` → `Projects/bbbar` (paths in docs/scripts)
- [ ] Decide whether to rename the package `dev.brl.nowbar.module` (renaming it unbinds the
      accessibility service and loses the stored prefs, so it is deliberately last)
- [ ] Version bump past `0.1.0-m1` once the next milestone lands

## Working now

- [x] Pill drawn as `TYPE_ACCESSIBILITY_OVERLAY` — visible above the keyguard (`Window #2`)
- [x] Lockscreen pill geometry (340×68 dp, radius 40, 150 dp above the bottom edge)
- [x] Status bar pill geometry (106×27 dp, radius 19, left 75, top auto) over the notif icons
- [x] Expanded popup, opened from the mini pill's own rect, mini pill stays visible
- [x] Tap-outside dismissal for both surfaces (full-screen catcher window)
- [x] Lock pill expands to 2.5× (width clamped to the screen)
- [x] Unlock/lock handover slides instead of waiting for a poll
- [x] Root keyguard watcher (`su -c logcat …`) with an 8 ms confirmation poll
- [x] Material 3 settings screen, Material You colours, live preview, 0–1000 dp sliders

## Next: make it a real Now Bar (user's design pass)

### Pill content and controls
- [ ] Replace the placeholder label with real items — no static text anywhere
- [ ] **Pill has no controls at all** (status bar pill and collapsed lockscreen pill): text + icon only
- [ ] Controls live only in expanded views: the lockscreen expanded pill and the expanded
      status bar popup
- [ ] Tapping the collapsed pill is the only way to open its expanded version
- [ ] Media item: album art (collapsed), title + artist, timeline, play/pause, prev, next,
      shuffle, favourite, output switch (expanded)
- [ ] Timers / stopwatch / alarms with pause, resume, cancel, dismiss, snooze
- [ ] Charging item, missed calls, Maps ETA, live updates from any app's progress notification
- [ ] Optional extras (only after approval): VPN/network, torch, battery, DND, calendar

### Multiple activities
- [ ] Item stack: more than one active item at a time
- [ ] Swipe up/down on the expanded view to cycle items
- [ ] Swipe on the *collapsed lockscreen* pill to cycle (status bar pill stays single)
- [ ] Editable ranking list: which item wins in the status bar by default
- [ ] Manual cycling order remembered until the activity changes; ranking is the fallback
- [ ] Max visible items, per-app allow/deny lists, per-app blacklist

### Look and motion
- [ ] Material You colour roles for the pill itself (primary / surface / on-surface)
- [ ] Blurred album art / blurred behind-pill background
- [ ] Correct expand/collapse animations for every surface; everything 200–300 ms
- [ ] Reduced-motion and "animations off" settings respected
- [ ] Contrast check on text/icons, readable fallback

### Settings app content (the "a lot of things" pass)
- [ ] Master on/off and per-surface switches
- [ ] Feature toggles for every item type above
- [ ] Per-app live-update allow list
- [ ] Ranking order editor (drag or up/down)
- [ ] Size presets (small/medium/large) on top of the raw dp sliders
- [ ] Colour mode: Material You / system accent / fixed colour / palette seed
- [ ] Animation: normal / reduced / off, popup style spring or fade
- [ ] Timeouts (expanded auto-hide, popup dismiss), haptics, debug mode with layout bounds
- [ ] Floating tuner stays as the dev tool but is no longer the main UI

## Milestones from the brief

| # | Milestone | Status |
| --- | --- | --- |
| M1 | Skeleton, hook discovery, plain pill at bottom centre | done, but as an overlay app instead of an LSPosed module (LSPosed pushed SystemUI into safe mode after repeated restarts) |
| M2 | Settings app shell + shared storage read live by the module | **partly done**: Material 3 shell, shared prefs, live updates; master toggle and surface switches still needed |
| M3 | Media and timers/alarms with expand/collapse | expand/collapse done, no data yet |
| M4 | Live updates, per-app control, stacking, AOD passive mode | not started |
| M5 | Status bar popup / Dynamic Island behaviour | popup + positioning done, no live content |
| M6 | Charging, missed calls, VPN, torch, Maps ETA | not started |
| M7 | Appear/disappear rules, auto-hide, dismiss, haptics | not started |
| M8 | Material You + accent colours + contrast | theme in the app done, pill colours not started |
| M9 | Polish: radius presets, debug mode, reduced motion | not started |

## Known limits / deviations from the brief

- **No LSPosed module.** The plan called for one, but LSPosed fell back to safe mode after
  repeated SystemUI restarts, so the pill is an accessibility overlay. Everything below the pill
  works without touching SystemUI.
- **AOD is not covered.** The pill is drawn while the keyguard is up; a true always-on-display
  variant needs a window that survives dozing, which has not been attempted.
- **`KeyguardManager.addKeyguardLockedStateListener` throws** on this build
  (`enforceSubscribeToKeyguardLockedStatePermission`), hence the root logcat watcher.
- **Root overlay daemon** (`RootOverlayDaemon`) is kept for reference: WindowManager rejects its
  window (`Unknown pid … uid=0`), so it is not used and its toggle was removed from settings.
