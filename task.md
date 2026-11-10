# bbbar — task list

Living work list. `done` means verified on the device (Nothing Phone 3a, Nothing OS 5.0 / Android 17).

## Current state

**v2.2.1** (`github.com/BRL26/bbbar`). Base is settled: the accessibility
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

## Done

### 2.2.1 — a Sys tab, and the hook switches moved to it
- [x] **One tab for everything outside the bar.** The `Sys` tab holds the **Everything off** switch,
      the LSPosed hook (its status row and the three hiders) and **Restart SystemUI**. Home keeps the
      live status readout without a switch, and the hook/hider switches and the restart are gone
      from the Items and Debug tabs.
- [x] **All-off never touches accessibility.** The switch stops the pill, the root keeper, the
      keyguard log stream and the watchdog; `enabled_accessibility_services` is not written, and
      neither is the notification-access grant. Turning it back on needs nothing else.
- [x] **Restart SystemUI is on the Sys tab**, next to the hook it reloads. Verified on the phone:
      the button kills SystemUI (with the app's own root grant), and the hook row goes from
      `not loaded (last beat Ns ago)` to `loaded` once LSPosed has the module enabled and scoped to
      `com.android.systemui`.

### 2.2.0 — hook choices, AOD colour, all-off, handover
- [x] **Issue #12: what the hook hides is a choice per element.** **Sys → Android status bar** has
      Android's clock, the battery / network cluster and the privacy indicator.
      The hook reads `bbbar_hide_clock`, `bbbar_hide_sys_icons` and `bbbar_hide_privacy`; a missing
      key follows the placement, so old installs behave exactly as before. Showing the cluster also
      stops the pill drawing its own battery / network glyphs (`sbDrawsStatusGlyphs`), because there
      is nothing left to replace.
- [x] **Issue #11: the AOD mark stays grayscale.** Only the pixels a progress dot covers are
      one-bit, and they flip the colour the mark rounds to on a black-and-white copy of itself -
      a light pixel gets a dark dot, a dark pixel a light one. App icons read as themselves instead
      of as a solid block.
- [x] **All-off is one switch.** The master switch stops the bar, the root keeper loop, the
      keyguard logcat stream and the watchdog alarm, and stops the service's timers. The
      accessibility permission is left alone - it is the app's grant, not a session's - so turning
      it back on does not need system settings.
- [x] **The idle line survives a boot or a reinstall.** The status pill only drew its placeholder
      once an unlock had been marked, but a poll-confirmed unlock never marked one - so after a
      boot, or a service start on an already-unlocked phone, the pill drew an empty capsule.
      `updateSurfaces` now marks any confirmed unlock, and a service that starts unlocked counts
      as having had its unlock. Verified `5:36 PM :)` in the pill after reinstalling.
- [x] **Unlock handover cannot blink.** A `locked` read inside the 800 ms post-unlock trust window
      is ignored by the poll, the accessibility event, the keyguard listener, `updateSurfaces`, the
      watchdog and `addLockPill`; `transitionToLocked` refuses to reverse a handover in that window;
      and a second unlock trigger within 600 ms no longer tears the pill down again.

### 2.1.0 — strip placement and the pill's clock
- [x] **Placement is a real choice**: **Right** (Android's battery / network corner, the inverse
      layout), **Left** (after the clock, over the notification icons) and **Left, no clock**. The
      clock-off left layout is the right-hand one mirrored: same width, height, top and margin, with
      `sbRightDp` read as a left margin. Verified `sb pill updated: 240x113px at x=26 y=6px` at a
      10 dp margin, and the pill kept `y=6` while moving ends.
- [x] **The bar's clock is hidden for the clock-off layout**, portrait only (landscape stays at the
      right end, clock and all). `bbar_hide_clock` follows the placement, and the mask is a
      `ContentObserver` away plus a per-tick reconcile. Verified steady `bbbar_clock_right_px=0`.
- [x] **The clock cannot paint a frame**: `Clock` is a `TextView` with no `draw` of its own, so the
      instance's inherited `onDraw` is skipped while masked (gated on the instance, 2 methods
      hooked). Verified from the log: `hook: clock draw hooked (2 methods,
      com.android.systemui.statusbar.policy.Clock)`, no re-show events, no SystemUI crash.
- [x] **Idle clock**: idle text can be **Text**, **Clock**, or **Clock + text**. The time uses the
      bar's own `DateFormat.getTimeFormat`, repaints on the minute boundary and the pill refits to
      it. A live card always wins. Verified `5:12 PM` alone and `5:17 PM  Now Bar` with text.
- [x] **A trimmed clock is not allowed**: the idle clock is the one line that may measure past the
      tuned width (up to the strip), because a cut-off time is wrong information. Verified: the
      default 106 dp pill grew to fit `5:11 PM` beside the battery / Wi-Fi glyphs.
- [x] **Defaults are the phone's own config**: left, no clock; 160 × 27 dp, radius 19, 20 dp from
      the edge; signal before battery; idle line `clockThenLabel`; placeholder `:)`.
- [x] **The clock-off layout has no strip pill on the keyguard** (the keyguard's own bar is left
      alone). Verified on the lock screen: its own carrier label, clock widget and stock Wi-Fi /
      battery icons, no strip pill, and the now-playing card still drawn at the bottom.
- [x] **Settings labels fit**: the three placement buttons read **Right**, **Left**, **Left, no
      clock**; longer labels were ellipsized to `Left, after…` and `Left, cloc…` in the toggle row.

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
- [x] Idle look is the placeholder label itself: blank label hides the status bar pill, any text
      shows it (the Text / Logo / Hide switch is gone - a stored "Hide" was the cause of a bar that
      looked dead, and the logo option went with it)
- [x] "Apply settings" button, because the app's own prefs are not the service's live prefs
- [x] Fake activities from the settings screen drive the real pill

### Keepers, idle rule and width morph (verified on the device)
- [x] **Idle rule**: `TuningStore.idleLabel()` / `showIdle()` — always text, and a blank label is
      what hides the idle status bar pill. The switch that could sit on "Hide" is removed from the
      Items card (it now explains the label instead), and `emptyForStatus()` / `applyContent()` /
      `PillView` all read the same rule
- [x] **Suppressed handover no longer strands the pill**: `slideLockPillOut`, `transitionToLocked`
      and `onScreenOff` schedule a forced resync 320 ms after `handoverAllowed()` said no
- [x] **Window watchdog** in the service: every 2.5 s it compares wanted vs attached windows
      (`lockPill` / `sbPill`), repairs a mismatch that survives two checks, re-adds a missing
      surface, removes one that should not be up, and drops a detached popup
- [x] **Root keeper**: detached root `sh` loop (`/data/local/tmp/bbbar-watchdog.sh`) that restores
      `enabled_accessibility_services` within 20 s. Proved on the device: app killed and the
      service dropped (`enabled_accessibility_services = null`) → loop wrote it back, SystemUI-free
      pill window came back on its own (`sb pill added`, 1 window)
- [x] **Graceful stop is respected**: `wasConnected` is set on connect and cleared in `onDestroy`,
      so switching the service off by hand is not undone; a crash never reaches `onDestroy` and is
      repaired
- [x] **Alarm fallback** (`NowBarWatchdog` + `WatchdogReceiver`, boot/update re-arm). Learned the
      hard way that `setInexactRepeating` is too lazy to be the primary keeper: it sat in a batching
      window with `origWhen` in the past and never fired inside 75 s
- [x] **Width morph**: the status bar pill animates its width (230 ms, decelerate) instead of
      snapping, retargets mid-flight, and does not restart when several prefs land in one applyAll
      (`sbWidthTarget`); `applySbTuning` keeps the current width for its first frame so the pill
      never flashes to maximum
- [x] **Hook status was wrong, not the hook**: the row took the *last* `hook:` log line (which is
      `hook: icon area child …`) — now it greps `attached|kill switch` over the whole buffer

### Status rows and switches (verified on the device)
- [x] **Status hero and rows read live state**: master switch, `NowBarBridge.service`,
      `NowBarA11yService.windowsUp()`. The old version OR-ed in "the service is listed as enabled",
      so it said Running with the pill switched off and Stopped while it was drawing
- [x] Rows re-read every 1.5 s while the screen is open (`ticker` + `onPause`), instead of only on
      resume
- [x] **Root watcher switch** (`TuningStore.ROOT_WATCHER`): stops and starts
      `RootKeyguardWatcher` from `applyAll` → `syncRootWatcher()`, also with the pill switched off
- [x] Watcher row tells `streaming (root)` from `reconnecting` from `off (switch)`
      (`RootKeyguardWatcher.isStreaming` = running *and* the `su` child is open)
- [x] **Hook row now has a real signal**: the hook writes `Settings.Global → bbbar_hook_beat`
      (`SystemClock.elapsedRealtime()`) at attach and every 30 s (SystemUI has
      `WRITE_SECURE_SETTINGS` granted on this build). Fresh beat → loaded, stale → not loaded with
      the age, kill-switch file → off. Logcat is only the fallback for an older hook build.
      Verified: `beat: 16268036`, `hook: icon area found
      (com.android.systemui.statusbar.AlphaOptimizedFrameLayout)`, row reads **loaded**
- [x] The pill survives a SystemUI restart (`am crash com.android.systemui`) - one window before
      and after, because it is our own overlay

### 1.0.2 — GitHub issues #1–#8 (verified on the device)
- [x] **#3 media scrubber**: expanded media cards draw a seek slider under the title with the elapsed
      time beside it (peeked while dragging, one seek on release through `MediaActions.seek`), and
      the bottom progress strip is skipped for them. `NowBarItem` carries `positionMs`/`durationMs`,
      filled from the session in `tickMedia` and when the card is built. Verified on the lockscreen:
      slider + `0:32` label, drag previews, release seeks
- [x] **#4 auto close**: `AUTO_CLOSE_MS` = 10 s, armed on expand (lock pill and popup), disarmed on
      collapse/remove/screen-off, re-armed by any control, swipe or seek. Verified: tap → expanded,
      log `auto close after 10000ms: lock pill` exactly 10 s later, card back to one line
- [x] **#5 AOD greyscale**: `PillView.setGray` runs a `ColorMatrix` saturation layer over the whole
      card; screen-off fades to 1 over 650 ms, `ACTION_SCREEN_ON` fades back over 160 ms. New switch
      **Greyscale on the always-on display** (`aodGray`). Verified by flipping `aodGray` from adb and
      comparing AOD screencaps: the same bar renders blue / grey
- [x] **#6 swipe**: the card follows the finger (`dragX`/`dragY`), commits past `max(44 dp, 30 % h)`
      and otherwise springs home with an `OvershootInterpolator` bounce (280 ms). Media direction is
      now right = previous (it used to be the other way round). Verified in a 30 fps screen record:
      the card rises with the finger, overshoots ~22 px past its place and settles; a leftward drag
      logs `media: next`
- [x] **#8 lockscreen collapse stutter**: two causes, both fixed — the content flipped to one line
      at the *start* of the shrink, and `applyContent()` re-filled the card mid-animation (it runs
      every second for a timer). The card now keeps its expanded layout while the window closes,
      flips at `onAnimationEnd`, `applyContent` skips `setItems` while `lockAnimator` runs, and both
      directions use one curve. Verified frame by frame: slider + controls stay through frames
      94–100, single line from 102 (the last frame of the animation)
- [x] **#2 compactification**: `PillView.preferredHeight()` measures what the front card needs and
      the popup uses `min(tuned, needed)` — `popupHeightDp` is a ceiling now. A card switch animates
      into its new height (180 ms) instead of snapping
- [x] **Same rule for the lockscreen expansion** (`lockCardSize`): width is still 2.5×, height is
      `preferredHeight` clamped to `[collapsed, 2.5×]`, measured on the live view so it follows the
      text size and the card in front. Verified: a media card expands to 1038×211px instead of
      1038×447px, with title, artist, scrubber and controls all still inside
- [x] **#7 landscape**: new **Extra top offset in landscape** (`popupLandscapeTopDp`, 24 dp) applied
      to the popup's y only in landscape, where the bar is shallower and its clock sits closer
- [x] **#1 / #7 status bar parity**: the hook samples the status bar view's `isShown` every 400 ms and
      writes `Settings.Global → bbbar_sb_hidden` on change; the service hides the pill and the popup
      with the bar and re-adds them when it returns, with a stale-beat guard so a dead hook can never
      switch the pill off. The old `-1`-as-*auto* slider values also display as *auto* again in the
      settings screen (they used to be clamped to 0 and could not have been fed to the Slider)

### 1.0.4 — the reopened issues (verified on the device)
- [x] **#3 scrubber frozen**: `PlaybackState.position` is a snapshot from `lastPositionUpdateTime`,
      and a player only posts a new state when something happens, so the raw value sat still. It is
      now interpolated (`NowBarState.livePosition`: base + elapsed × speed, clamped to the duration)
      in both the tick and the builder. A seek or play/pause is also *held* over the player's own
      state (`NowBarState.holdMedia`, 1.5–2 s) so the thumb no longer snaps back, and the glyph
      flips immediately instead of on the next tick. Verified with a session that reports a static
      position: the label advances; controls and seek reach the player (`media: pause`, `seek to
      45831ms`, session position matches)
- [x] **#6 swipe cut off**: a window clips to its own bounds, so a card that followed the finger was
      sliced where the window ended. The window now grows around the card for the duration of a drag
      (64 dp each way, capped at half the card) and closes again when the card is back
      (`growForDrag`/`shrinkAfterDrag`, `PillView.setFixedBox`). The drag is measured in **raw**
      screen coordinates, because moving the window changed where a touch landed in view
      coordinates and the card chased its own window. The status bar pill no longer follows the
      finger at all (it ignores swipes). Verified from a 30 fps recording: the whole card moves,
      rounded corners and text intact, and settles back
- [x] **#8 AOD look, third iteration**: thin outline instead of a filled card, text inside, and the
      progress drawn as a lattice of small dots - **white** on the bar, and **black** punched
      through the glyphs (the text is the inversion of the same lattice, clipped with a text path).
      Tiled with a `BitmapShader`, so a full bar is one draw call. The lattice drifts: a new random
      direction and speed every 2.2-4.5 s, eased in over ~0.9 s, never faster than 8 dp/s, advanced
      once per frame from `dt`. Spacing and dot size come from one setting (`aodDotCellDp`, 2-20 dp,
      default 6). Verified on the AOD slice the display composites: outline plus a row of dots over
      the played 35 %
- [x] **#7 landscape configurable in the app**: `sbLandscapeLeftDp` / `sbLandscapeTopDp` (both -1 =
      same as portrait) plus the existing popup landscape offset, all as rows in the settings screen
- [x] **#1 hook reload without restart-loops**: the status bar parity lives in the hook, which only
      loads with SystemUI. One broadcast restarts it exactly once:
      `am broadcast -a dev.brl.nowbar.module.RESTART_SUI -n dev.brl.nowbar.module/.TuningReceiver`
      (`TuningReceiver`). Verified: new SystemUI pid, `hook: attached`, `hook: icon area found`,
      `hook: status bar visible`, `bbbar_sb_hidden = 0` written for the first time
- [x] **Test lever**: `--es fake media|timer|live|none` puts a card on the real pill from adb, for
      screens the settings UI cannot be reached from

### 1.0.4 — status bar visibility is pushed, not polled
- [x] **The pill now leaves with the bar, including the launcher's fade-out.** The barrier was that a
      hidden bar can be a *window* the window manager fades or scales, so no view property changes:
      `isShown`, `alpha` and `getGlobalVisibleRect` all stayed "visible", which is why the pill sat
      in a strip the bar had already vacated. Window insets were tried instead and were worse: a
      window that stops drawing stops receiving inset updates, so the last answer stuck at "hidden"
      and the pill stayed gone. What actually works is the platform's own push:
      `CommandQueue.Callbacks.setWindowState` → `StatusBarWindowStateController#setWindowState`,
      hooked in `NowBarHook`. It writes `bbbar_sb_hidden` on the change, and the service watches that
      key with a `ContentObserver` — no timer on the fast path. Verified on the device:
      `hook: status bar visible (pushed)` → pill slides in; home screen → `hidden (pushed)` → pill
      rises out 190 ms later (that 190 ms is its own exit animation, not detection lag)
- [x] **Exit/return animation.** The pill rises and fades when the bar goes, and comes back down from
      the top edge. An instant removal read as the pill falling off while the bar was still moving
- [x] **Shade and Quick Settings keep the pill.** They are not a hidden bar, so the pill stays visible
      there by design
- [x] **Fallback kept.** If the push cannot be hooked, the hook samples the bar view every 500 ms:
      still correct for immersive apps, just blind to the fade-out kind
- [x] **AOD dot size is its own setting** (`aodDotSizeDp`, 1-16 dp, default 5) next to spacing
      (`aodDotCellDp`, 3-24 dp, default 8), so the dots can be made visibly bigger without changing
      how tightly they are packed. Dot diameter is clamped to the spacing, so the maximum is dots
      that just touch rather than merge into a solid bar

### 1.0.4 — shade position, drag feel, AOD text
- [x] **Pill position while the shade / Quick Settings is open.** That screen has its own header, so
      the strip the pill shares with the clock is not there. Two new settings,
      `sbShadeLeftDp` / `sbShadeTopDp` (`-1` = stay put), applied on the push from
      `NotificationShadeWindowControllerImpl#apply` (field `shadeOrQsExpanded`). Verified on the
      device: shade opened → `sb pill updated: … at x=105 y=788px` 11 ms after the push; shade
      closed → back to `x=194 y=27px`
- [x] **Drag start reads as a stretch, not a wrong guess.** Until the direction is clear (12 dp, one
      axis leading by 1.25×) the card follows 22 % of the finger; the axis locks once and cannot
      flip, then the grip eases to 1 over ~130 ms so the card bounces over to the finger and tracks
      it. Sideways stays at 75 %, since it commits to a skip. The release now commits on the locked
      axis instead of whichever delta was bigger at lift
- [x] **Controls while dragging.** The drag window grows up to 160 dp each way (capped at the card
      height) instead of 64 dp, so a long drag no longer runs out of window and slices the card and
      its controls. Control taps are hit-tested where the card is drawn (`event.x - dragX`), so a tap
      during the spring-back lands on the control it looks like
- [x] **AOD text dots are the progress.** The black dots in the AOD text are clipped to the filled
      fraction as well as to the glyphs, so they only exist as far as the playhead reached; beyond
      it the line is solid, and at 0 % there are no holes

### 1.0.4 — the shade screen
- [x] **Quick Settings uses the top pill, locked or unlocked.** The keyguard's bottom pill sat behind
      the notification list, so while the shade is open the top pill is the one shown; the bottom one
      and the popup are removed. Default height there is unchanged; **Shade / Quick Settings top
      edge** (`sbShadeTopDp`) moves it. The left slider was removed: that strip has no clock
- [x] **Visible only, never tappable in the shade.** `FLAG_NOT_TOUCHABLE` on the window plus
      `PillView.interactive = false`, so taps fall through to the shade's own header. Verified: a tap
      on the pill with the shade open produced no expand and no popup
- [x] Both driven by the shade push, so they land with the shade animation

### 1.0.4 — landscape, the clock, and the shade margin
- [x] **Landscape is centred**, in the status bar and with the shade open; a tuned left edge is
      ignored there (the clock owns the left end, the icons the right). Width morphs stay centred
- [x] **The pill clears the clock** instead of a fixed margin: the hook publishes the clock's right
      edge (`bbbar_clock_right_px`, `0` when the clock is hidden), and the portrait left edge is
      pushed to the right of it. Verified: `193px` portrait, `0` with the shade open
- [x] **Shade margin**: with the clock hidden, the pill uses a plain **16 dp** left margin instead of
      the clock-sized gap. Verified: `sb pill updated … at x=42 y=27px`
- [x] **Rotation re-places everything.** A service keeps its display metrics from start-up and does
      not reliably get `onConfigurationChanged`, which is why a landscape pill survived a turn. The
      hook now publishes SystemUI's screen size (`bbbar_screen_w/h`) and the app watches it plus a
      display listener, then re-places every window that is up
- [x] **Wider when there is room**: half the screen with the shade open, a third in landscape, on top
      of the tuned cap (which still applies in portrait)
- [x] **Lock pill cannot appear in the shade**: `addLockPill` refuses while the shade is open, so a
      lock-state event landing mid-open no longer draws both pills at once

### 1.0.4 — the drag was losing the card
- [x] **Why the card vanished.** A window clips to its own bounds, so a drag offset that ended up
      larger than the room the window had grown by drew the card outside it: gone until something
      else moved it. Three things conspired: a cancelled spring left a non-zero offset behind, a
      new touch did not reset it, and the drag offset was never held inside the grown room. Fixed:
      offsets are clamped to the grown box while dragging, a new touch cancels any spring and zeroes
      both offsets, `setFixedBox(0, 0)` (the window closing) zeroes them too, and the drag axis now
      force-locks after 26 dp instead of staying in the damped "stretched" state
- [x] **No more resizing mid-drag.** A content change, a preference or a placement landing while the
      finger was down moved or resized the window under the card, and the drag then shrank back to a
      stale height. `applySbTuning` / `applyLockTuning` / `applyPopupTuning` now defer while a drag
      owns the window, and the deferred apply happens right after the drag closes it
- [x] Verified on the device: `drag: window grown to 1032x579` on the expanded card, then a committed
      vertical swipe switched the card and a horizontal one sent `media: previous` — the card stayed
      whole and in place both times (screenshots), instead of collapsing
- [ ] Still open: the drag cannot be watched frame by frame over adb (`input motionevent` does not
      build one gesture, and the expanded view auto-closes after 10 s), so the *feel* of the start
      still wants your finger

### 1.0.4 — the dragging mechanic, reworked
- [x] **The window itself follows the finger.** Growing the window and drawing the card offset
      inside it cannot work: the window clips to its own bounds, which is the "imaginary box" cutting
      the card off, and any offset left over when the window shrank put the card outside it — gone.
      Now the window stays the card's size and the WM moves it, so there is nothing to clip and
      nothing to restore. Verified: `drag: window follows the finger from 24,147`, and the card is
      whole and back in place afterwards (screenshot)
- [x] **Why it "could not be dragged":** a window that moves with the finger makes the tail of its
      own gesture land outside itself, arriving as `ACTION_OUTSIDE` — which was wired to collapse.
      Ignored while dragging or seeking now, so the card no longer collapses the instant the drag
      starts
- [x] **No replay after the drag**: expansion animators are cancelled on drag start, which is what
      produced "teleports twice, then falls from the top" (the expand animation re-running)
- [x] **The return is animated**: the offset animation now drives the window, so the card slides
      home (~0.42 s with overshoot short of a swipe, ~0.32 s after one) instead of snapping
- [x] **Seek no longer freezes the clock**: the held command is a *prediction* (position + elapsed
      time, advancing at playback speed) rather than a pinned value, so the elapsed time keeps
      counting up through the 2 s hold instead of stopping dead
- [ ] Open: card icons for calls/messenger — the buttons next to the title fall back to the first
      letter of the action label, because the notification's own action icons are not captured yet

### 1.0.4 — placement: landscape centred, portrait gap
- [x] **Landscape kept shifting after the first frame.** `applySbTuning` centred the pill using the
      *tuned maximum* width, then put the width on screen back to the fitted one, so the centre was
      off by half the difference and snapped a moment after the placement. The x is now worked out
      from the width the window actually has — verified: a 524 px pill lands at x=934 on a 2392 px
      screen, `(2392 − 524) / 2`, and stays there
- [x] **The expanded card is centred in landscape too** (it was pinned to a 16 dp left margin while
      the pill it grows from sat in the middle). Verified from the expansion log:
      `popup expanding from pill 315x63 at 934,34 to 1032x367 at 680,179` — `(2392 − 1032) / 2 = 680`
- [x] **Portrait keeps a gap after the clock** (`SB_CLOCK_GAP_DP = 8`). Clamping the pill exactly
      onto the clock's right edge read as one long status bar item, and moved a tuned left edge off
      its spot — verified: clock ends at 170 px, pill starts at 190
- [x] **A boot-time stale display size can no longer strand the pill.** SystemUI starts before its
      configuration settles and publishes the *landscape* size on a portrait screen, so the pill
      was placed centred for 2392 px on a 1080 px screen — i.e. off the right edge, which is the
      "it's not where it used to be". The published size is now cross-checked against the display's
      rotation, and a size change re-places every window from the poll
- [x] **Log flood silenced**: the per-notification `rebuild sees …` line is verbose now; at info level
      it pushed the placement lines out of the buffer within seconds

### 1.10.0 — settings, the boot flash, and the last of the media nits

- [x] **No placeholder on a lock screen, ever.** The boot flash was the *keyguard flag*: on a cold
      boot `isKeyguardLocked` answers "unlocked" while the lock screen is still being built, so the
      status pill was drawn with the idle line and taken away again a moment later. The lock pill and
      the expanded card now refuse the placeholder outright (`PillView.allowIdle`), and the status
      pill only draws it after an unlock has really happened in this boot — remembered in
      `nowbar_session` with the boot marker, so a keeper/service restart mid-session keeps it while a
      fresh boot still starts without it. Seen on a reboot: three `keyguardLocked=true (boot …)`
      readings before the keyguard settled, no pill on the lock screen, card drawn at the bottom.
- [x] **A second, worse cause of the same flash** found in the root stream: the platform prints
      `E/KeyguardViewMediator … Do not proceed to finish keyguard exit and unlock.` right after a
      relock. `looksLikeUnlock` matched "keyguard exit" inside it and forced an unlock. Error lines
      and the "do not proceed"/"mPendingLock=true" guards are ignored now.
- [x] **Media transport glyphs were mirrored**: `drawSkip` put the bar on the *opposite* side of the
      arrow, so prev showed the next glyph and next showed the previous one. Bar and arrow now point
      the same way (⏮ / ⏭).
- [x] **A live update at 100 % is finished**: the card is dropped from the stack (`NowBarItems`), so
      the pill stops showing a full, silent bar that the source app left up.
- [x] **Reset all settings** on the About tab: confirmation dialog, clears `tuning` (and the session
      marker), reloads the module, returns to Home.
- [x] **Shade / Quick Settings width** setting: `0` keeps the fit-the-line behaviour, any other value
      is the fixed width the pill takes while the shade is open; the value is a width, not a ceiling,
      and is clamped to the screen.
- [x] **Home preview is the bar itself**: `NowBarState.items()` feeds the real `PillView`, so the card
      on the screen is the card in the app, in rank order at the tuned sizes, refreshed on the 1.5 s
      tick. Caption says what it is showing (kind, collapsed/expanded, stack depth).
- [x] **Back walks the settings screen**: sub-page → its tab → Home → out (`OnBackPressedCallback`).
- [x] **Bottom tabs stay honest**: `syncTabChips()` after every render, so a reset or any other
      programmatic page change moves the selected chip with it.
- [x] **The `\u2190` literal** in the Back button was written as an escaped backslash in the source;
      it renders as an arrow now.

### Status strip and the SystemUI hook
- [x] **Inverse layout**: the pill owns the right end of the strip, draws its own battery and
      Wi-Fi / cellular glyphs, and the hook hides Android's icon cluster (`system_icon_area`)
- [x] Network glyph follows the connection: Wi-Fi when on Wi-Fi (even with data up), cellular when
      only data is up, nothing when offline; battery always drawn
- [x] Glyph order is a setting, and a short rounded hairline divides the line from the cluster;
      the gap is measured from the text's ink and halved, so both sides are equal in pixels
- [x] The pill's battery is a lattice of dots rather than a solid fill; the cluster rides the right
      end of the strip and the line stops before the rule's own room
- [x] **Lock screen**: the same strip pill, icons only (battery, network, earbuds), centred, with no
      line, divider or progress strip; locking/unlocking morphs it instead of swapping windows
- [x] The lock-screen strip pill is not interactive, and the shade while locked removes it
      altogether (the shade has its own header)
- [x] **Keyguard's own status bar** is hooked too: it draws a second copy of the icons from its own
      tree, which is why they came back on the lock screen after the strip's copy was hidden
- [x] **Privacy chip** hidden by the hook while the pill owns that corner (it is in its own window,
      above the overlay, so it sat on top of the pill)
- [x] The strip pill is out of the AOD (doze reports a hidden bar and flaps, which looped it)
- [x] **KDE Connect** cards draw a play button instead of the launcher icon; the AOD inverts the
      whole glyph slot by luminance, so the play button inverts to black like any app icon
- [x] SystemUI's icons are stopped at `draw` / `dispatchDraw`, not just alpha: SystemUI rewrote
      alpha and visibility during screen-off, which is the frame where they flashed through
- [x] The strip pill's window is 8 dp taller than its card at both ends, so a tap just above or
      below the shape still opens the expansion instead of hitting the status bar; the card keeps
      its tuned size and place (`setFixedBox`)

### Hook fixes worth remembering
- [x] **SystemUI crashed on every boot → LSPosed safe mode**: `hookAllMethods(cls, "draw")` also
      caught `View.draw(Canvas, ViewGroup, long) -> boolean`. Cancelling it made `ViewGroup.drawChild`
      unbox a null into a boolean (`NullPointerException` in the middle of the frame), SystemUI
      crash-looped, and LSPosed answered with safe mode — every module off, which looked exactly
      like "the hook stopped working". The hook now walks `declaredMethods` and hooks only methods
      that return `void`. Safe mode has to be cleared (reboot / the manager) before a module loads
      again.
- [x] **Draw guard with no area refs** skipped *every* instance of the hooked class (a common view
      class included). It now draws instead: an unhidden icon is recoverable, a blank shade is not.

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
- **The LSPosed hook only hides** (`NowBarHook.kt`): it never draws and never lays out — it sets
  `alpha` / `visibility` on the status bar's icon areas and skips their `draw` calls. Everything the
  pill shows still comes from the accessibility overlay. **A crash inside SystemUI at boot makes
  LSPosed enter safe mode** (all modules off, `bbbar_hook_beat` stays `null`, Android's own battery
  and Wi-Fi stay in the strip); clear safe mode before reading a stale hook as a broken build.
- **The hook needs the module enabled for `com.android.systemui`** in LSPosed, and one SystemUI
  restart after installing a new build. `touch /data/local/tmp/bbbar_hook_off` disables it entirely.
- **Status bar visibility is verified both ways** (owner confirmed). Home screen: the launcher's
  auto-hide fades the bar, the push reports hidden, the pill rises out. Any app with a bar: the
  push reports visible and the pill slides back down. Shade/QS: the pill stays visible on purpose.
- **The drag feel and the AOD text clip were not watched on the device.** Both are compiled and
  reachable, but confirming them needs a finger on the screen (a mid-gesture frame) and the AOD,
  which cannot be entered without locking the phone to a PIN screen I cannot unlock. Worth a look:
  drag sideways and watch the lean-then-catch-up, and read the AOD text at a small played fraction.
- **Scrubber interpolation was not watched on a real player.** There was no playing session at test
  time (both media sessions were `NONE`), so the fix is verified by the code path and by a session
  whose position moves, not by a track advancing on the lock screen.
- **No root overlay daemon**: `app_process` is not registered with ActivityManager, so
  `WindowManager` refuses its window (`Unknown pid … uid=0`). Root is only the logcat wakeup.
