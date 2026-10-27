# Scroll-column review

Branch `scroll-column` — 6 feature commits `5f9328a..22686ea` on top of `2b80152`.
Three review-fix commits added: `e31008e`, `c0fa651`, `100a986`.

## Findings by severity

### BUG — `refreshPause()` resumes off-screen slots  *(fixed: e31008e)*

**File:** `SlotTaskCoordinator.kt`, `refreshPause()`

The method resumed a paused slot when `pauseRules.blocker()` returned `NOT_CHOSEN` (the
app is in the chosen list). It ignored `scrolledAway[index]`: an off-screen slot that was
paused by scrolling would be resumed here, and only re-paused ~2 s later via the next
`evaluatePause` cycle. During that window the virtual display ran needlessly.

**Fix:** Skip the resume when `scrolledAway[index]` is true. The `schedulePauseCheck` that
follows re-evaluates correctly (since `evaluatePause` already checks `scrolledAway`).

### MINOR — `resumeAfterLock()` resets scroll AFTER layout  *(fixed: c0fa651)*

**File:** `NeXtepWindowController.kt`, `resumeAfterLock()`

`applySlotLayout` was called first (which ends with `applyScrollOffset(panel.scrollOffsetPx)`
using the stale offset), then `resetScroll()` after — a one-frame glitch. Moved `resetScroll()`
before `applySlotLayout`.

### MINOR — `show()` resets scroll AFTER `reconfigure()`  *(fixed: 100a986)*

**File:** `NeXtepWindowController.kt`, `show()`

Same pattern as `resumeAfterLock`: `reconfigure()` internally calls `applySlotLayout`, which
applies the stale scroll offset, then `resetScroll()` was called after `reconfigure()` returned.
Moved `resetScroll()` before `reconfigure()`.

### Assessment — translationY rendering risk (item e)

**Decision: keep translationY.**

Reasoning:
1. `SidebarPanelView` extends `FrameLayout`; `clipChildren` defaults to `true`, so half-scrolled
   cards are clipped at the panel edges by the view hierarchy.
2. The Android window compositor clips at the window boundary. Cards translated partially
   outside the window surface will be compositor-clipped.
3. The fallback (per-frame `topMargin` relayout) would cause jank on scroll — it triggers a
   full measure/layout pass every frame.
4. The codebase already uses `translationY` for slide-in animations on the same `TextureView`
   cards without reported blanking.
5. Fully-off-screen cards are marked `INVISIBLE` and reported to the coordinator for pause.
6. If blanking/bleeding appears on device (known SurfaceView-under-clipping risk), the marked
   `FALLBACK POINT` in `applyScrollOffset` is ready: switch to a per-card output-layer crop,
   which `TaskSwitcherView` already accesses for output visibility.

**Verdict:** Risk is LOW. Keep translationY; try the fallback only if visual artifacts appear
on device.

## Verified correct

| Area | Notes |
|------|-------|
| **Roster-continuation pitch math** | `rosterSlotBounds` continues at the last visible cell's pitch; remainder rows come first so the last visible cell is always a full-height one. (Finding (a) confirmed.) |
| **Roster shrink re-clamp** | `maxScrollOffsetPx` setter re-clamps the offset. `setScrollOffset` clamps to `[0, max]`. (Finding (b) confirmed.) |
| **Encode/decode v18/19** | `slotCount` inserted at field 1 in encode; decode reads `p[1]`, shifts subsequent indices +1. Size check `22 + count*3 + (wide ? 1 : 0)` matches. Round-trip guard `profile.encode() != encoded` catches any asymmetry. |
| **Touch interception** | Vertical-dominant (`|dy| > slop && |dy| > |dx| * 1.25f`) only. Children get `ACTION_CANCEL`. `TaskSwitcherView` disallows interception during drag. Multi-pointer handoff correct. |
| **Offset clamping and resets** | `setScrollOffset` clamps `[0, max]`. Reset in `show()`, `resumeAfterLock()`, and on profile-instance change inside `applySlotLayout`. All correct after fixes. |
| **Roster growth** | `wanted = (lastOccupied + 2).coerceIn(4, 12)`. `setRosterSlotCount` copies into `baseProfile` and `profile`, publishes, persists, notifies. `refresh()` restores from decoded record. |
| **Off-screen pause/resume** | `evaluatePause` uses `offscreenBlocker` when `scrolledAway[index]`. `pausedOffscreen` tracks scroll-caused pauses; only those resume on scroll-back. `deactivate()` clears both. (Correct after bug fix.) |
| **Scene decode** | Reads `slots.length()` from JSON, capped at `MAX_WORKSPACE_SLOTS`. Builtins stay at 4 entries (all null); harmless. |
| **Share picker** | Offers `1..MAX_WORKSPACE_SLOTS`; picking past the roster triggers `updateRosterSize`. |
| **`withWideSlot`/`withControlAtBottom`** | Both propagate `slotCount` via `.copy(slotCount = slotCount)`. |
| **`maxScrollOffset` (wide case)** | Correctly filters out the wide slot and uses the rail slots' bounds vs `sidebarLogicalHeight`. |

## Changes made

| Commit | Description |
|--------|-------------|
| `e31008e` | `refreshPause()`: guard resume with `!scrolledAway[index]` |
| `c0fa651` | `resumeAfterLock()`: move `resetScroll()` before `applySlotLayout` |
| `100a986` | `show()`: move `resetScroll()` before `reconfigure()` |

## What was left alone

- **Stale `scrolledAway`/`pausedOffscreen` on roster shrink+regrow**: minor — the flags for
  a slot that leaves and re-enters the roster might be stale, but `resume()` is a no-op on a
  non-paused slot, and `evaluatePause` re-evaluates. Cleaning these on roster shrink is
  optional hardening.
- **`withWideSlot` / `withControlAtBottom` round-trips**: reviewed, correctly propagate
  `slotCount`.
- **Landscape / rotation**: design is local-axes-consistent (panel subtree is rotated by
  `WorkspacePanelHost`); untested on device.
- **Drag to off-screen slot**: no edge auto-scroll (documented as phase 2).
- **12-display memory cap**: mitigated by off-screen pause; `MAX_WORKSPACE_SLOTS` is a
  one-line change.
- **Test comment drift** in `WorkspaceLayoutProfileTest`: pre-existing, harmless.
- **HyperOS shim**: not modified (per instructions); consumes geometry Settings keys, not
  the layout-profile record, so the v18/19 format bump doesn't affect it.

## Remaining on-device risks

1. **SurfaceView clipping under partial scroll** — translationY relies on panel/window
   clipping. If blanking occurs, use the marked `FALLBACK POINT` crop path in
   `applyScrollOffset`.
2. **Landscape + scroll + wide slot** — untested; design is consistent but needs device
   verification.
3. **12-display memory pressure** — mitigated by off-screen pause; lower `MAX_WORKSPACE_SLOTS`
   if needed.
