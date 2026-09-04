# Phase 1 — PriorityRouter Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver pure `PriorityRouter` seam per PRD US-05 AC-05.1/AC-05.2 and FR-09 — standard FIFO voice-note queue vs alert preemption, without touching AudioManager/STREAM_ALARM, ASR/TTS, Oboe, transport, or Compose UI.

**Architecture:** Single pure Kotlin class `data/router/PriorityRouter` operating on `domain/model/Frame.isAlert`. Maintains `current: PlaybackItem?` (simulated playing) + `pending: List<PlaybackItem>` FIFO for standard queue. Alerts preempt: `route(alert)` clears all pending standard items and replaces current standard item immediately (PlayNow), while a second alert while an alert is playing enqueues behind current alert (non-interruptible). `onPlaybackFinished()` advances to next pending standard if no alert pending. Policy documented and tested; no Android dependencies.

**Tech Stack:** Kotlin 1.9.22, JUnit 4.13.2 + Truth, Android SDK 34 (arm64-v8a only), CMake 3.22.1/ASAN/UBSAN unchanged, manifest 11 permissions no INTERNET.

**Spec:** `docs/PRD.md` §2.2 US-05 AC-05.1 (standard FIFO), AC-05.2 (alert preempt, STREAM_ALARM, non-interruptible), §4.1.1 `priority-router` seam `Router::route(Frame)->Queue{standard, alert}`, §4.4 FR-09, `docs/Offline Multilingual Speech Transceiver Architecture.md` alert/priority section, baselines `089f53e` FrameCodec, `72ad685` VadFsm.

## Global Constraints

- minSdk 24, targetSdk 34, compileSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — verify via `aapt dump permissions`
- Native flags unchanged, no new native code for this slice
- TDD iron law: no production code without failing test first (RED→GREEN) — PRD 5.4
- Do NOT integrate STT/TTS models, sherpa-onnx, Oboe, transport sockets, Compose UI, AudioManager — pure routing logic only
- Use existing `Frame(domain/model/Frame.kt:1)` `isAlert` flag — do not add new Frame fields
- Pure seam: no android.* imports, deterministic unit-testable — PRD 4.1.1

---

### Task 1: Domain Model — PlaybackItem & RouteDecision

**Files:**
- Create: `app/src/main/java/com/itantra/domain/model/PlaybackItem.kt`
- Create: `app/src/main/java/com/itantra/data/router/RouteDecision.kt` (or inside PriorityRouter.kt)

**Interfaces:**
- Consumes: `Frame` (`isAlert`, `seqId`, `payloadText`, `srcLang`, `dstLang`)
- Produces: `data class PlaybackItem(val frame: Frame, val id: Int = frame.seqId, val isAlert: Boolean = frame.isAlert, val text: String = frame.payloadText)` and `sealed class RouteDecision { data class PlayNow(val item: PlaybackItem, val preempted: PlaybackItem? = null): RouteDecision(); data class Enqueue(val item: PlaybackItem): RouteDecision(); object Drop: RouteDecision() }`

- [x] **Step 1: Write PlaybackItem.kt**

```kotlin
package com.itantra.domain.model
data class PlaybackItem(val frame: Frame) {
  val isAlert: Boolean get() = frame.isAlert
  val seqId: Int get() = frame.seqId
  val text: String get() = frame.payloadText
}
```

- [x] **Step 2: Write RouteDecision.kt**

```kotlin
package com.itantra.data.router
import com.itantra.domain.model.PlaybackItem
sealed class RouteDecision {
  data class PlayNow(val item: PlaybackItem, val preempted: PlaybackItem? = null): RouteDecision()
  data class Enqueue(val item: PlaybackItem): RouteDecision()
  object Drop: RouteDecision()
}
```

- [x] **Step 3: Verify compiles**

Run: `./gradlew :app:compileDebugKotlin --info` Expected: no errors for new model

### Task 2: TDD RED — PriorityRouter Tests (Failing)

**Files:**
- Create: `app/src/test/java/com/itantra/data/router/PriorityRouterTest.kt`

**Interfaces:**
- Consumes: `PlaybackItem`, `RouteDecision`, `PriorityRouter` (not yet existent), `Frame` factory
- Produces: 10+ failing tests defining `PriorityRouter` API per §3.1 strict

**Test cases (mapped):**

| # | Test | PRD mapping |
|---|------|-------------|
| 1 | `standard_frame_enqueued_fifo_whenIdle_playsNow` | US-05 AC-05.1 FIFO |
| 2 | `second_standard_enqueued_preservesFifo` (2 standards) | AC-05.1 |
| 3 | `threeStandards_inOrder_pendingFifo` | AC-05.1 |
| 4 | `alert_immediatelyBecomesSoleActiveItem_playNow` when idle | AC-05.2 / FR-09 |
| 5 | `alert_preemptsOngoingStandard_andClearsPending` (standard playing + 2 queued, alert arrives → current alert, pending empty) | AC-05.2 preempt |
| 6 | `alert_arrivingWhileStandardPlaying_forcesPreemption` (simulated current != null) | AC-05.2 |
| 7 | `afterAlertFinishes_pendingStandardsNotRestored` (alert PlayNow then onPlaybackFinished → current null, pending still empty, not restored) | FR-09 policy doc |
| 8 | `emptyQueue_behaviour_currentNull_pendingEmpty` | edge |
| 9 | `clear_removesCurrentAndPending` | clear |
| 10 | `sequenceIdNotReordered_routerPreservesArrivalOrder` (seq 5 then 3 standard → pending order 5,3 not sorted) | seq edge |
| 11 | `secondAlert_whileAlertPlaying_enqueuesBehindCurrent` (alert non-interruptible) | AC-05.2 non-interruptible |
| 12 | `onPlaybackFinished_advancesFromPendingFifo` (standard queue) | AC-05.1 |
| 13 | `routeReturnsCorrectDecisionType_playNowVsEnqueue` | interface |

- [x] **Step 1: Write PriorityRouterTest.kt importing non-existent `com.itantra.data.router.PriorityRouter`**

```kotlin
package com.itantra.data.router
import com.itantra.domain.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class PriorityRouterTest {
  private fun frame(seq:Int, alert:Boolean=false, text:String="hi-$seq"): Frame = Frame(TransmitMode.HALF_DUPLEX, isAlert=alert, isStream=false, pttPressed=false, Language.HINDI, Language.ENGLISH, seq, text)
  @Test fun standard_frame_enqueued_fifo_whenIdle_playsNow() {
    val r=PriorityRouter(); val d=r.route(frame(1,false))
    assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
    assertThat(r.current()?.seqId).isEqualTo(1)
  }
  // ... remaining 12 tests per table, each uses r.route, r.current, r.pending, r.onPlaybackFinished, r.clear
  @Test fun alert_preemptsOngoingStandard_andClearsPending() {
    val r=PriorityRouter(); r.route(frame(1,false)); r.route(frame(2,false))
    assertThat(r.pending().size).isEqualTo(1)
    val d=r.route(frame(99,true))
    assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
    assertThat(r.current()!!.isAlert).isTrue()
    assertThat(r.pending()).isEmpty()
  }
}
```

- [x] **Step 2: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.router.PriorityRouterTest"` Expected: FAIL — `Unresolved reference: PriorityRouter` (and `RouteDecision` if separate)

### Task 3: TDD GREEN — Minimal PriorityRouter (Pure)

**Files:**
- Create: `app/src/main/java/com/itantra/data/router/PriorityRouter.kt`

**Interfaces:**
- Consumes: `PlaybackItem`, `RouteDecision`, test expectations
- Produces: Passing pure `PriorityRouter` with exact API:

```kotlin
class PriorityRouter {
  fun route(frame: Frame): RouteDecision
  fun onPlaybackFinished()
  fun current(): PlaybackItem?
  fun pending(): List<PlaybackItem>
  fun clear()
}
```

Policy (document in KDoc): Standard frames FIFO via pending queue; current is simulated playing item. Alert frames clear all pending standard items and preempt current standard (Drop & PlayNow with preempted payload); alerts are non-interruptible — second alert enqueues behind current alert pending list. After alert finishes via `onPlaybackFinished()`, router advances to next alert if any else to null/empty — previously cleared standard pending is NOT restored (policy per FR-09). `clear()` resets both current and pending. No Android imports.

Implementation sketch (minimal):

```kotlin
package com.itantra.data.router
import com.itantra.domain.model.*
class PriorityRouter {
  private var current: PlaybackItem? = null
  private val pendingStandard = mutableListOf<PlaybackItem>()
  private val pendingAlert = mutableListOf<PlaybackItem>()
  fun route(frame: Frame): RouteDecision {
    val item=PlaybackItem(frame)
    return if(item.isAlert) {
      if(current==null) { current=item; RouteDecision.PlayNow(item) }
      else if(current!!.isAlert) { pendingAlert.add(item); RouteDecision.Enqueue(item) }
      else { val pre=current; pendingStandard.clear(); current=item; RouteDecision.PlayNow(item, pre) }
    } else {
      if(current==null) { current=item; RouteDecision.PlayNow(item) }
      else { pendingStandard.add(item); RouteDecision.Enqueue(item) }
    }
  }
  fun onPlaybackFinished() {
    current = when {
      pendingAlert.isNotEmpty() -> pendingAlert.removeAt(0)
      pendingStandard.isNotEmpty() -> pendingStandard.removeAt(0)
      else -> null
    }
  }
  fun current(): PlaybackItem? = current
  fun pending(): List<PlaybackItem> = pendingAlert + pendingStandard // or only standard visible? expose combined FIFO with alerts first
  fun clear() { current=null; pendingStandard.clear(); pendingAlert.clear() }
}
```

Note: adjust `pending()` to expose only appropriate view for tests — simplest: `pending(): List<PlaybackItem> = (pendingAlert + pendingStandard)` but tests expect standard pending cleared after alert, and alert enqueue visible. Keep doc.

- [x] **Step 1: Implement PriorityRouter.kt minimal to pass 13 tests**

Exact code as above, but ensure `pending()` returns unmodifiable copy, `clear()` works, and `onPlaybackFinished` advances correctly for tests `afterAlertFinishes...` and `onPlaybackFinished_advancesFromPendingFifo`.

- [x] **Step 2: Run GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.router.PriorityRouterTest"` Expected: PASS
Run: `./gradlew :app:testDebugUnitTest` Expected: PASS (46 baseline + ≥13 new = ≥59)

### Task 4: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: ≥59 tests pass (29 FrameCodec + 17 VadFsm + 13 Router)

- [x] **Step 2: Debug APK arm64-v8a**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` only

- [x] **Step 3: No INTERNET**

Run: `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -i INTERNET` Expected: no output; `scripts/check-no-internet.bat` → PASS

- [x] **Step 4: No new native code**

Check: `git diff --stat` shows no `app/src/main/cpp/` changes for this slice

- [x] **Step 5: Git commit**

```bash
git add app/src/main/java/com/itantra/domain/model/PlaybackItem.kt app/src/main/java/com/itantra/data/router/*.kt app/src/test/java/com/itantra/data/router/PriorityRouterTest.kt docs/superpowers/plans/2026-09-04-phase1-router.md
git commit -m "feat(phase1-router): PriorityRouter TDD per PRD US-05 / FR-09"
```

## Self-Review

- Spec coverage: US-05 AC-05.1 FIFO (Task2 #1–3, #12), AC-05.2 alert preempt & STREAM_ALARM future hook + non-interruptible (#4–6, #11), FR-09 preempt until done (#7) ✓; framings/VadFsm not touched ✓
- No placeholders: all steps have concrete Kotlin code and `Run:` commands
- Type consistency: `Frame.isAlert` (existing), `PlaybackItem(Frame)`, `RouteDecision` sealed, `PriorityRouter.route(Frame):RouteDecision` match across tasks
- TDD flow respected: RED (unresolved) → GREEN (minimal pure logic)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-router.md`. Two options:
1. Subagent-Driven (fresh subagent per task) — recommended
2. Inline Execution — implement in session
