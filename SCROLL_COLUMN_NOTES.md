# Scrolling slot column — implementation notes

Branch: `scroll-column` (from `hyperos-shim` HEAD `2b80152`). Not pushed anywhere.
Spec: the sidebar slot column scrolls vertically with a continuous offset; exactly 4 slots
visible at today's sizes; the roster grows dynamically to 12 with a trailing empty slot kept
as the add position; a vertical-dominant drag ON a slot scrolls the column (slot apps lose
vertical scrolling; taps, horizontal gestures and long-press drag keep working).

## What was implemented, per sketch step

1. **Constants** (`display/SlotState.kt`, commit 5f9328a) — `WORKSPACE_VISIBLE_SLOT_COUNT = 4`
   (sizing) and `MAX_WORKSPACE_SLOTS = 12` (roster cap). `WORKSPACE_SLOT_COUNT` remains as an
   alias of the visible count so unrelated callers/tests keep compiling.
2. **Geometry solve** (`workspace/WorkspaceGeometry.kt`) — `forDisplay` now feeds the solver
   `WORKSPACE_VISIBLE_SLOT_COUNT`; card sizes and the main window are byte-identical to before.
3. **Layout profile** (`workspace/WorkspaceLayoutProfile.kt`, commit 3a16294)
   - New constructor field `slotCount` (default 4), validated `4..12`; `wideSlotIndex` bound
     is now `0 until slotCount`.
   - `slots` emits bounds for the whole roster via `rosterSlotBounds(total, visible, fit)`:
     the first `visible` entries are exactly `slotBounds(visible)` (unchanged math); entries
     beyond continue at the same pitch (`base + slotGapPixels`, derived with the same integer
     math as `slotBounds`) with the last visible card's size and centering. Wide layout: rail
     uses `rosterSlotBounds(slotCount - 1, 3, fit = mainCanvasHeight > 0)`.
   - `withWideSlot`'s shared-aspect solve still uses `VISIBLE - 1` rail cards (geometry is a
     visible-window property); `withWideSlot`/`withControlAtBottom` preserve `slotCount`
     across their `create()` round-trips.
   - Record format: `slotCount` inserted as field 1 (after the version); versions bumped
     `16/17 -> 18/19`, so every older record is rejected and re-measured, per the format's own
     contract. `decode` reads the count from the record, checks `size == 22 + count*3 (+1)`,
     and still verifies by full re-encode equality.
   - `maxScrollOffset` = lowest rail card bottom − `sidebarLogicalHeight`, floored at 0
     (wide slot excluded from the rail set).
4. **Scroll state** (`systemui/NeXtepWindowController.kt`, commits 3770b23, 22686ea)
   - `applySlotLayout` keeps base margins untouched; a new `applyScrollOffset(offsetPx)` sets
     each rail card's `translationY = -offset`, marks fully-out-of-window cards `INVISIBLE`,
     and reports them to the coordinator as off-screen. Sign/axis: everything is in
     panel-local coordinates — `WorkspacePanelHost` rotates the panel subtree and Android
     transforms touch/drag into local axes, and slot tops are logical-axis values, so local
     `-offset` translation is correct for portrait and both landscape rotations (the same
     reasoning `SlotBackgroundSwipeGesture` documents for its slot-local axes).
   - Offset resets: in `show()`, in `resumeAfterLock()`, and inside `applySlotLayout`
     whenever the applied profile instance changes (geometry/wide/roster rebuild). Re-applying
     the same cached profile (task changes) preserves the scroll position.
   - The wide card is excluded from scrolling (it lives in the top strip) and its
     `translationY` is forced to 0 when it leaves the rail.
5. **Gesture** (`systemui/SidebarPanelView.kt`, new) — a `FrameLayout` replacing the plain
   panel in `sidebarPanel()`. `onInterceptTouchEvent` claims a MOVE when
   `|dy| > touchSlop && |dy| > |dx| * 1.25` (children get `ACTION_CANCEL`, which
   `TaskSwitcherView`/`SlotBackgroundSwipeGesture` already handle); `onTouchEvent` applies
   deltas and runs an `OverScroller` fling/settle that writes the offset per animation frame
   via `computeScroll()`. No snapping: the offset is continuous. Multi-pointer handoff is
   handled so lifting the first finger does not jump the column.
   - Drag protection: `TaskSwitcherView` now calls
     `parent?.requestDisallowInterceptTouchEvent(true)` when a long-press drag actually
     starts and `(false)` in `finishDrag()` (the common end of every drag path), so a drag
     travelling vertically along the rail is never stolen by the column scroll. The existing
     background-swipe gesture already did the same for its horizontal dismiss.
6. **Roster** (`NeXtepWindowController` + `systemui/WorkspaceViewport.kt`)
   - `slotWindows` pre-allocates `MAX_WORKSPACE_SLOTS` (12) `VirtualDisplaySlot`s; the
     coordinator's list never changes size. In `applySlotLayout`, indices
     `>= profile.slotCount` get `visibility = GONE` and no layout params — a GONE
     TextureView/SurfaceView produces no surface, so `VirtualDisplaySlot` never creates a
     display for them.
   - `rosterSize` starts at 4. `observeTasks` → `updateRosterSize(states)`:
     `wanted = (indexOfLast Occupied + 2).coerceIn(4, 12)` — i.e. it grows +1 when the
     trailing empty slot becomes Occupied, never below occupied+1 or 4, capped at 12 (at the
     cap the last slot cannot keep a trailing empty; accepted).
   - Growth path: `viewport.setRosterSlotCount(n)` copies the count into `baseProfile` and
     `profile`, re-publishes the layout (`SystemServerWorkspaceBridge.publishLayout`),
     persists it (`saveSnapshot`), and posts the viewport-changed notification, which
     re-runs `applySlotLayout` with the new bounds. `WorkspaceViewport.refresh()` restores
     `rosterSlotCount` from a decoded record; `updateSnapshot` stamps the current count onto
     every rebuilt base profile, so the roster survives restarts via the record.
7. **Coordinator** (`display/SlotTaskCoordinator.kt`, commit b81efc5) — the init `require`
   relaxes to `slots.size in WORKSPACE_VISIBLE_SLOT_COUNT..MAX_WORKSPACE_SLOTS`. See audit
   findings below.
8. **Scenes / share** (commit 6519ac3) — `WorkbenchScene.decode` reads the stored array's own
   length capped at 12 (length-agnostic; builtins stay at 4 entries, they are all-null
   tools). `ShareToPaneActivity` offers panes `1..MAX_WORKSPACE_SLOTS`; choosing one past
   the roster occupies it (all coordinator index paths are `slots.indices`-guarded) and the
   roster then grows to reach it.
9. **Resource policy** (commit b81efc5) — `SlotTaskCoordinator.setSlotOffscreen(index,
   offscreen)` driven from `applyScrollOffset`: a fully-out slot goes through the existing
   pause machinery (`schedulePauseCheck` → `evaluatePause` → `VirtualDisplaySlot.pause(cover)`)
   with a new `SlotPauseRules.offscreenBlocker` — the chosen-apps list is waived (nothing is
   on screen), but media playback, an active call and the wide pane still keep a slot live.
   `pausedOffscreen[]` records which slots scrolling paused; scrolling back resumes exactly
   those. Both flags clear on `deactivate()`. Displays are never created for empty or
   beyond-roster slots (no surface exists for GONE views; activation is surface-driven).

## Audit findings (step 7) — hidden fixed-count assumptions in SlotTaskCoordinator

- The only literal coupling was the init `require` (relaxed). Everything else is
  `slots.indices` / `slotRecords`-driven: `slotRecords = MutableList(slots.size)`,
  `pauseBlockers = arrayOfNulls(slots.size)`, `staleFrameChecks`/`pauseChecks` are built per
  index, and `validActiveSlot` guards every externally supplied index.
- `scenePackages()` returns one entry per record, so saved scenes now carry up to 12 entries
  (trailing nulls) — consistent with the length-agnostic scene decode.
- `usagePanes()` reports all records; harmless.
- No other file hard-codes 4 for slots: the remaining `WORKSPACE_SLOT_COUNT` references are
  the alias itself, `WorkspaceGeometry` (now the visible alias), and unit tests (the alias
  keeps them compiling; `WorkspaceLayoutProfileTest` still asserts the 4-slot defaults,
  which are unchanged when `slotCount` is left at its default).

## TODOs / stubs / unverified assumptions

- **NOT BUILT, NOT RUN.** Host memory never reached the 2500 MB gate during this work
  (available ~460–520 MB of 8 GB), so per instructions no Gradle attempt was made. All code
  was verified by close reading only. First step when memory allows:
  `GRADLE_USER_HOME=~/workspace/.gradle-nextep bash gradlew :app:assembleRelease`
  (SDK platform android-36.1 is still missing; a configuration failure on that is expected
  until it is installed.)
- **RISKIEST UNKNOWN — SurfaceView under partial visibility.** Scroll rendering is
  translation-only, relying on the panel/window to clip half-scrolled cards. If a
  half-scrolled card blanks, bleeds or smears on device (this codebase has scars here:
  placeholder outputs, rebind frame drops, emptied-slot frame bleed), the marked fallback
  point is `NeXtepWindowController.applyScrollOffset` (search `SCROLL RENDERING —
  FALLBACK POINT`): replace the translation with a per-frame `topMargin` relayout
  (`bound.top - offsetPx`), or set a crop on the card's output layer.
- **Landscape unverified.** Rotation composition (host-rotated panel, logical-axis tops,
  drag rects from `getScreenVisibleRect`) follows the existing local-axes design, but no
  device check was possible. The wide-slot-under-scroll combination is also untested;
  phase-1 behavior: wide promotion works at any roster index, the wide card itself never
  scrolls, and rail bounds under a wide layout extend by the same roster rule.
- **Drag to an off-screen slot** has no edge auto-scroll (phase 2, per sketch). A
  long-press drag can still drop onto any *visible* card; targets are computed from
  screen rects, which already include the scroll translation.
- **12 full-res virtual displays** is the cap's cost only if all 12 slots are occupied and
  visible-adjacent; off-screen occupied slots pause (display off, surface detached) via
  step 9, and beyond-roster slots have no display at all. If on-device memory pressure
  appears, lowering `MAX_WORKSPACE_SLOTS` is a one-line change.
- `WorkbenchScene.builtins()` still builds 4-entry slot lists (all null); harmless because
  builtin scenes never match `active()` and carry no packages.
- Pre-existing test comment drift: `WorkspaceLayoutProfileTest
  .inconsistentSavedDimensionsAreRejected` calls field 14 "canvas width"; in format 18/19
  that index is `contentScale`. The assertion (tampered record is rejected) is unaffected.
- The HyperOS shim consumes the *geometry* Settings keys, not the layout-profile record,
  so the format bump does not affect it; the profile record is written/read only inside
  this app (viewport persistence + `publishLayout`).

## Opus review addendum (2026-10-27)
An external Opus 4.6 review pass (via Antigravity CLI) audited the 6 commits above and added 3 fix commits + the report commit (OPUS_SCROLL_REVIEW.md):
- e31008e — refreshPause() must not resume a scrolled-away slot (guard !scrolledAway[index]).
- c0fa651 — resumeAfterLock(): resetScroll() moved before applySlotLayout (one-frame stale-offset glitch).
- 100a986 — show(): same fix, resetScroll() moved before reconfigure().
- Rendering decision: KEEP translationY scrolling (clipChildren + compositor clipping); the FALLBACK POINT in applyScrollOffset stays as the contingency if on-device half-scroll blanking/bleeding appears.
- Verified correct by the review: v18/19 encode/decode symmetry, touch interception, offset clamping/resets, roster growth/shrink, pause lifecycle, scenes, share picker, wide-slot propagation. One stale-flag edge (scrolledAway surviving a roster shrink→grow cycle) was judged harmless and deliberately left.
- Still uncompiled and untested on device — the build still waits for a host-memory window.

## Argon cross-review addendum (2026-10-27, web Gemini 4 Argon, checked against code by Muse)
Argon disputed the Opus rendering decision and raised 6 risks. Verified against the code:
- CONFIRMED CONCERN: the cards ARE real SurfaceViews — TaskSwitcherView.textureView is a SurfaceView with setZOrderMediaOverlay(true); the field NAME "textureView" misled the Opus report (it called them TextureView). Whether ancestor clipping reliably crops a media-overlay SurfaceView inside the rotated panel host on HyperOS is NOT settled by argument (BLAST usually crops correctly in scrolling lists; the wide-slot intra-window overlap is the risky variant). Decision stands (keep translationY) but confidence is downgraded: Argon's Test 1 (wide slot + half-scroll clip + touch) is the GATING on-device test; if it fails, implement the setCrop fallback at the marked FALLBACK POINT first.
- REFUTED: "resetScroll does not abort the fling" — SidebarPanelView.resetScroll() calls scroller.abortAnimation() (line 57-58); DOWN during fling also aborts (lines 70/95).
- REFUTED (mostly): share-to-beyond-roster race — VirtualDisplaySlot already has pendingLaunch + Listener.onDisplayReady gating.
- PLAUSIBLE, recorded as follow-ups before/with first build: (i) clear a slot's scrolledAway/pausedOffscreen flags when it leaves and re-enters the roster (shrink->regrow + share path); (ii) when a card is promoted to wide slot or demoted back, explicitly clear INVISIBLE/scrolledAway since applyScrollOffset skips the wide slot; (iii) INVISIBLE teardown of SurfaceView surfaces means fast scroll-back may show 50-200ms blank — covered by Argon Test 1's scroll-back step.
- Argon's 3 on-device tests (wide-slot clip/touch; drag vs scroll vs fling-stop gesture arbitration; offscreen-pause + roster-shrink + share state machine) are adopted as the delivery test protocol for the first build.

## On-device bug 2026-10-28: roster growth only took effect on re-entry
User report: with 4 slots full, no trailing add slot appeared and the column would not
scroll; after exiting and re-entering the workbench, scrolling worked and the blank add
slot was there. Root cause: updateRosterSize grew the profile via
viewport.setRosterSlotCount -> notifyChanged -> SystemUiRuntime.reconfigureForViewport ->
WorkspaceController.reconfigure(), which early-returns when
windowController.currentGeometry() == previous.geometry. The roster size lives in
WorkspaceLayoutProfile, NOT in WorkspaceGeometry (geometry derives from the VISIBLE
count), so a 4->5 growth leaves the geometry unchanged and the re-layout was skipped;
show()/entry paths apply the layout unconditionally, hence "works after re-entry".
Fix: updateRosterSize now re-applies applySlotLayout to the live panel directly
(panel.post, guarded by workspaceVisible) after setRosterSlotCount. The LSPosed log
export cannot confirm NeXtepLog lines (they go to logcat, not the modules log).
