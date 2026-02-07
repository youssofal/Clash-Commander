# CLASH COMPANION — FINAL SYNTHESIZED HACKATHON PLAN (v2)
## Supercell Global AI Game Hack · Feb 6–8, 2026 · 48 Hours · Solo Build

### CHANGELOG (v2 vs v1)
- **Project renamed:** Voice Commander → **Clash Companion** (narrative matters)
- **Hardware corrected:** Android 14 / One UI 6.1 → **Android 16 / One UI 8.0** (freshly updated from Android 15)
- **dispatchGesture bug:** Known Android 15 freeze on simultaneous touch — likely fixed on Android 16 but `safeTap()` kept as defensive code
- **Restricted Settings:** Android 13+ blocks sideloaded Accessibility apps — one-time bypass documented
- **Build order resequenced:** Queue Path moved from M9 → **M7** (before Smart Path and Roboflow)
- **Roboflow models corrected:** AngelFire is cards/HP bars, NOT troop detection. Added MinesBot + Nejc Zavodnik as alternatives
- **Roboflow pricing updated:** Credit-based system now. Core plan ($99/mo) recommended.
- **Friday schedule extended:** Working until ~4 AM (not 1 AM). More milestones achievable Friday night.
- **Deck share link format confirmed:** `https://link.clashroyale.com/deck/en?deck=26000002;26000001;...` — stable, deterministic, stays primary
- **Dev device prep added:** Strip Samsung bloat, disable battery optimization as pre-M0 step
- **Narrative upgraded:** Voice companion pitch, "$300 mid-range phone", 4K trailer, polished UI emphasis
- **Game audio:** Confirmed non-issue. VAD handles it. Mute for demo as nice-to-have.

---

# TABLE OF CONTENTS

1. [Project Overview](#1-project-overview)
2. [Hardware & Constraints](#2-hardware--constraints)
3. [System Architecture](#3-system-architecture)
4. [Command Tier System (Five Tiers)](#4-command-tier-system)
5. [Deck Loading Pipeline](#5-deck-loading-pipeline)
6. [Model Selection & Rationale](#6-model-selection--rationale)
7. [Card-in-Hand Detection](#7-card-in-hand-detection)
8. [Command Parser & Alias System](#8-command-parser--alias-system)
9. [Arena Vision (Roboflow)](#9-arena-vision-roboflow)
10. [Smart Path: The Two-Model Architecture](#10-smart-path-the-two-model-architecture)
11. [Input Injection (Accessibility Service)](#11-input-injection-accessibility-service)
12. [Latency Budgets](#12-latency-budgets)
13. [Memory Budget](#13-memory-budget)
14. [Confidence Gating & Safety](#14-confidence-gating--safety)
15. [UI Overlay & App Design](#15-ui-overlay--app-design)
16. [Build Order (Milestone-Gated)](#16-build-order-milestone-gated)
17. [Video Demo & Trailer Strategy](#17-video-demo--trailer-strategy)
18. [GitHub Packaging & Testability](#18-github-packaging--testability)
19. [Edge Cases & Failure Modes](#19-edge-cases--failure-modes)
20. [Risk Register](#20-risk-register)
21. [API Keys & Accounts Needed](#21-api-keys--accounts-needed)
22. [Cost Estimate](#22-cost-estimate)
23. [What NOT To Build](#23-what-not-to-build)

---

# 1. PROJECT OVERVIEW

## What It Is

**Clash Companion** is an Android overlay app that lets you play Clash Royale using voice commands. It sits on top of the game, listens to your voice, interprets commands, and executes card placements by injecting touch events through Android's Accessibility Service.

## Why It Wins

The project combines FIVE distinct AI technologies in a single real-time system:

1. **On-device Speech Recognition** (Moonshine via sherpa-onnx)
2. **On-device Voice Activity Detection** (Silero VAD)
3. **Computer Vision Object Detection** (Roboflow YOLO for arena troops)
4. **LLM Strategic Reasoning** (Claude Haiku 4.5 for real-time decisions)
5. **LLM Deep Analysis** (Claude Opus 4.6 for pre-match deck strategy)

The architectural innovation is an **intelligent five-tier routing system** that automatically selects the fastest execution path for each command type — from 170ms on-device-only execution for simple placements, to 800ms cloud-LLM-powered strategic decisions for complex tactical commands.

## The Hackathon Context

- **Event:** Supercell Global AI Game Hack
- **Track:** AI Tech Open Challenge
- **Prize:** Top 3 teams get fast-track interview for Supercell AI Lab (Spring 2026, Japan/Helsinki)
- **Participants:** ~600 across all tracks
- **Submission:** Video demo + public GitHub repo by Sunday 3 PM
- **Format:** Video demo (not live), editable. Judges can also test from GitHub.
- **Requirement:** Everything open-source. APIs permitted.

## Key Constraints (Agreed)

- **Budget:** Unlimited. Cost is not a factor.
- **Device:** Samsung Galaxy A35 (Exynos 1380, 8GB RAM, **Android 16, One UI 8.0**)
- **Device prep:** Dev mode Samsung — will be stripped of bloat, battery optimization disabled, processes cleaned up before starting
- **Build tool:** Cursor IDE with two Android MCPs: `mobile-mcp` (screenshots, UI hierarchy, tap/swipe, app install) + `android-mcp` (shell commands, logcat, device state, notifications)
- **All decks and cards must work** — not limited to a hardcoded set
- **Needs LLM wow factor** — pure rule-based won't impress at an AI hackathon
- **Needs targeting commands** — "fireball the hog rider" must find the hog rider on screen
- **Presentation quality matters** — 4K trailer, polished app UI, compelling landing/junction page
- **Two CR accounts:** Starter account (low-level, slow opponents) for clean demo footage. Main account (high-level, full card library) for testing all cards/features. Can switch between accounts during development.

---

# 2. HARDWARE & CONSTRAINTS

## Samsung Galaxy A35 Specifications

| Spec | Detail |
|------|--------|
| **SoC** | Exynos 1380 |
| **CPU** | 4× Cortex-A78 @ 2.4GHz + 4× Cortex-A55 @ 2.0GHz |
| **GPU** | Mali-G68 MP5 |
| **NPU** | 4.9 TOPS (advertised) |
| **RAM** | 8GB |
| **Display** | 1080 × 2340 (FHD+, 19.5:9) |
| **OS** | **Android 16, One UI 8.0** (freshly updated) |
| **Status** | Dev device — will be heavily optimized pre-build |

## What This Means for the Build

- **8GB RAM is comfortable** but not luxurious. Clash Royale uses ~1.2GB, Android + One UI uses ~2.5GB, leaving ~4.3GB for our app + models.
- **NPU should be treated as bonus, not plan-critical.** If NNAPI delegation works, great. If not, CPU is fast enough for Moonshine Base.
- **1080 × 2340 resolution** means all coordinate math must be calibrated to this exact resolution. Hardcode it — this is the only device that matters.
- **Exynos 1380 is mid-range.** Don't attempt on-device YOLO with TFLite GPU delegate — Mali driver debugging could eat the entire weekend.

## Android 16 / One UI 8.0 Considerations

### `dispatchGesture` Simultaneous Touch Bug (Android 15 — Likely Fixed)

There was a **confirmed Android 15 bug** across ALL auto-clicker apps using `dispatchGesture()`. When the user manually touched the screen at the exact same moment the Accessibility Service dispatched a gesture, the gesture system froze, requiring a full restart. This affected Samsung, Pixel, OnePlus, and Xiaomi on Android 15.

**Android 16 status:** Likely fixed, but unconfirmed on this specific device. `safeTap()` below is kept as **defensive programming** — cheap insurance.

**Mitigations (kept regardless of Android version):**
1. **For the demo video:** Hands-free operation naturally avoids the bug.
2. **For robustness:** `safeTap()` gates on touch state (see Section 11).
3. **For GitHub testers:** Document hands-free as recommended in README.

```kotlin
// Defensive touch-state gate (insurance against gesture bugs)
private var isTouchActive = false

override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    when (event?.eventType) {
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> isTouchActive = true
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
            handler.postDelayed({ isTouchActive = false }, 100) // 100ms cooldown
        }
    }
}

fun safeTap(x: Float, y: Float) {
    if (isTouchActive) {
        Log.w(TAG, "Touch active — skipping gesture (defensive gate)")
        overlay.show("⚠️ Release screen first")
        return
    }
    dispatchGesture(buildTapGesture(x, y), null, null)
}
```

### Restricted Settings for Sideloaded Apps

Android 13+ Restricted Settings blocks sideloaded apps from enabling Accessibility Services, overlays, and other sensitive permissions. Still applies on Android 16.

**Workaround (one-time, 30 seconds):**
Settings → Apps → [Clash Companion] → 3-dot menu → "Allow restricted settings" → authenticate with PIN/biometrics.

**Alternative:** Install via SAI (Split APK Installer from Play Store) which uses the session-based installation API and bypasses the restriction entirely.

**Action:** Add this as step 1 of M0 setup. Not a blocker on a dev device.

### Samsung One UI 8.0 Battery Optimization

One UI aggressively kills background services. MediaProjection and audio capture services will be targeted.

**Pre-build device prep (do before M0):**
1. Settings → Battery → Background usage limits → Never sleeping apps → Add Clash Companion
2. Settings → Battery → Battery optimization → Clash Companion → Don't optimize
3. Pin app in recents (long press → pin icon)
4. Disable Adaptive Battery
5. **Strip Samsung bloat:** Remove/disable unused Samsung apps, clear cached processes, free up RAM
6. Settings → Developer Options → Don't kill background processes (if available)
7. Test that foreground services survive 5+ minutes with CR in foreground

---

# 3. SYSTEM ARCHITECTURE

## High-Level Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                     PRE-MATCH SETUP                         │
│                                                             │
│  Deck Share Link ──→ Parse Card IDs ──→ cr-api-data lookup  │
│                                          │                  │
│                                          ▼                  │
│                              Claude Opus 4.6                │
│                         (Strategic Playbook Gen)            │
│                                  │                          │
│                                  ▼                          │
│                        Playbook JSON (cached)               │
│                                                             │
│  Optional: "Calibrate Deck" mode ──→ pHash templates        │
│            (capture cards from live hand)                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     DURING MATCH                            │
│                                                             │
│  Microphone ──→ Silero VAD ──→ Moonshine Base STT           │
│                 (on-device)     (on-device, sherpa-onnx)    │
│                                       │                     │
│                                       ▼                     │
│                              Raw Transcript                 │
│                                       │                     │
│                                       ▼                     │
│                            ┌──────────────────┐             │
│                            │  COMMAND ROUTER   │             │
│                            │ (Kotlin, <5ms)    │             │
│                            └──┬──┬──┬──┬──┬───┘             │
│                               │  │  │  │  │                 │
│              ┌────────────────┘  │  │  │  └──────────┐      │
│              ▼                   ▼  │  ▼              ▼      │
│         ⚡ FAST            📋 QUEUE │ 🎯 TARGET   🧠 SMART  │
│         ~170ms            ~170ms+  │  ~400ms      ~800ms    │
│         wait               │                               │
│                             ▼                               │
│                        ⏱️ CONDITIONAL                       │
│                        ~400ms+wait                          │
│                                                             │
│  All tiers ultimately ──→ safeTap() with touch-state gate   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   BACKGROUND PROCESSES                      │
│                                                             │
│  MediaProjection (continuous) ──→ Latest frame buffer       │
│                                       │                     │
│                    ┌──────────────────┤                      │
│                    ▼                  ▼                      │
│            pHash hand scan     Roboflow API (1-2 FPS)       │
│           (which 4 cards?)    (what troops on arena?)       │
│                    │                  │                      │
│                    ▼                  ▼                      │
│            Hand State Cache    Arena State Cache             │
│           (updated ~5 FPS)    (updated ~1-2 FPS)            │
└─────────────────────────────────────────────────────────────┘
```

## Key Architectural Decisions & Rationale

**Decision: On-device STT, cloud everything else.**
*Rationale:* STT is the most latency-sensitive component (~80ms matters). Vision and LLM calls can tolerate 200-800ms because they serve different command types where that latency is acceptable or hidden. All analyses agreed: on-device STT is non-negotiable, but on-device YOLO is too risky for 48h on Exynos.

**Decision: Five-tier routing instead of one pipeline.**
*Rationale:* A single pipeline would either be too slow for simple commands (if it always calls LLM) or too dumb for complex commands (if it never does). The router is a ~5ms regex/pattern check that picks the optimal path. This is the core architectural innovation.

**Decision: Two-model LLM architecture (Opus + Haiku).**
*Rationale:* Opus does deep strategic analysis once, pre-match (can take 30s, nobody cares — edit it out in the video). Haiku makes snap decisions during gameplay using Opus's playbook (must be <1s). This is analogous to a coach preparing game film vs. a player calling audibles. Neither model alone achieves both depth and speed.

**Decision: Deck share link for card loading, not vision scan.**
*Rationale:* Share links encode card IDs deterministically — zero error rate, instant, offline. The format is confirmed stable: `https://link.clashroyale.com/deck/en?deck=26000002;26000001;...` with semicolon-separated numeric IDs. RoyaleAPI, DeckShop, and the in-game share feature all use this same format. Parsing is trivial. Vision-based deck scanning introduces hallucination risk for the most critical data in the system.

---

# 4. COMMAND TIER SYSTEM

## Tier Overview

| Tier | Name | Trigger Pattern | Latency | Cloud? | Example |
|------|------|----------------|---------|--------|---------|
| ⚡ | **Fast Path** | `[card] [zone]` | ~170ms | No | "balloon bridge right" |
| 📋 | **Queue Path** | `queue/next [card] [zone]` | ~170ms + wait | No | "queue balloon bridge" |
| 🎯 | **Targeting Path** | `[spell] the [troop]` | ~400-600ms | Roboflow | "fireball the hog rider" |
| 🧠 | **Smart Path** | Ambiguous/strategic | ~700-1000ms | Claude Haiku | "defend against hog rider" |
| ⏱️ | **Conditional Path** | `if [troop] [action]` | ~400ms + wait | Roboflow | "if hog drop cannon" |

## Tier 1: Fast Path (⚡ ~170ms)

**What it handles:** Commands where the user specifies BOTH the card AND the location. No ambiguity, no vision needed.

**Examples:**
- "balloon bridge" / "balloon right" / "loon left bridge"
- "hog rider left" / "hog left"
- "cannon center" / "cannon middle"
- "skarmy right" / "skeleton army bridge"
- "log bridge" / "log the bridge"

**Pipeline:**
```
Transcript → Fuzzy match card name (Levenshtein + alias dict) → 5ms
          → Match location keyword to zone coordinates → 1ms
          → Verify card is in deck → 1ms
          → Look up card slot via pHash hand scan → 5ms
          → safeTap() card slot → safeTap() zone coordinate → 80ms
          ─────────────────────────────────────────────────
          TOTAL: ~170ms from end-of-speech
```

**Why it's fast:** Everything is local. No network calls. The fuzzy matcher and coordinate lookup are pure in-memory operations. The only "AI" here is the on-device STT (Moonshine), which is a real neural network even though the execution feels instant.

**Zone Coordinate Map (1080 × 2340, Samsung A35):**

These coordinates need calibration during Milestone 0, but approximate zones:

| Zone Name | Aliases | Approximate Tap Point |
|-----------|---------|----------------------|
| left_bridge | "left", "left bridge", "bridge left" | (270, 950) |
| right_bridge | "right", "right bridge", "bridge right" | (810, 950) |
| center | "center", "middle", "mid" | (540, 1050) |
| behind_left_tower | "back left", "behind left" | (270, 1300) |
| behind_right_tower | "back right", "behind right" | (810, 1300) |
| front_left | "front left" | (270, 800) |
| front_right | "front right" | (810, 800) |
| back_center | "back", "back center", "behind king" | (540, 1400) |

*Note: These will be calibrated empirically during Milestone 0 by tapping in a training match and recording where units land.*

## Tier 2: Queue Path (📋 ~170ms + wait)

**What it handles:** Commands where the user wants to pre-program a card placement that executes when the card appears in hand.

**This is a key insight from the architecture analysis.** It provides the "strategic planning" wow factor WITHOUT requiring any arena vision. It only watches your hand (via pHash), which is already built for the Fast Path.

**Examples:**
- "queue balloon bridge" → waits for balloon to appear in hand, then plays it at bridge
- "next hog left" → plays hog rider at left bridge when it cycles into hand
- "hog left then log" → plays hog, then automatically plays log when it appears

**Pipeline:**
```
Transcript → Parse card + zone → Store in queue buffer
          → Background: pHash scans hand every ~200ms
          → When target card detected in hand → Execute Fast Path via safeTap()
          ──────────────────────────────────────────────────────
          EXECUTION: Same as Fast Path (~170ms) once card appears
```

**Why this matters for the demo:** You can say "queue balloon bridge, then queue freeze" and the app will wait, watch your hand rotate, and auto-play each card the moment it appears. To a judge, this looks like the AI is managing your hand cycle — very impressive, zero cloud dependency.

**Why this is built BEFORE Smart Path and Roboflow:** Queue Path uses ONLY infrastructure already built in earlier milestones (pHash hand detection). Zero new dependencies, zero new APIs, fully offline. If Roboflow or API integration takes longer than expected, Queue Path is already locked in as a demo-ready tier.

## Tier 3: Targeting Path (🎯 ~400-600ms)

**What it handles:** Commands where the user specifies a card AND a target (a moving troop/building on the arena), so the system must USE VISION to find the target's position.

**Examples:**
- "fireball the hog rider" → find enemy hog rider → drop fireball on it
- "arrows the minion horde" → find minion horde → drop arrows on it
- "zap the inferno tower" → find inferno tower → zap it
- "log the goblin barrel" → find goblin barrel → log its landing zone

**Pipeline:**
```
Transcript → Fuzzy match spell/card name → 5ms
          → Fuzzy match target troop/building name → 5ms
          → Look up target in Arena State Cache (from Roboflow) → 1ms
          → If target found:
              → Calculate optimal spell placement (center of bbox) → 5ms
              → safeTap() card slot → Drag to target coordinates → 80ms
          → If target NOT found:
              → Overlay: "Target not found" + audio beep
              → Do nothing (better than wasting the spell)
          ──────────────────────────────────────────────────────────
          TOTAL: ~100ms if target already cached
                 ~400-600ms if a fresh Roboflow call is needed
```

**If target isn't found:** The app does NOT fire blindly. A missed fireball is 4 wasted elixir and looks terrible in a demo. Always fail gracefully.

## Tier 4: Smart Path (🧠 ~700-1000ms)

**What it handles:** Commands where the user expresses INTENT but doesn't specify which card to play. The system must REASON about the deck, the game state, and strategy to pick the optimal card and placement.

**This is the LLM wow factor. This is what wins the hackathon.**

**Examples:**
- "defend against hog rider" → Haiku checks playbook → picks Cannon at center
- "support my golem" → Haiku picks Night Witch or Baby Dragon behind the golem
- "counter his push" → Haiku analyzes arena state → picks best response
- "push left lane" → Haiku picks an offensive card for left bridge

**Pipeline:**
```
Transcript → No simple pattern match → Route to Smart Path
          → Assemble context:
              {deck_cards, opus_playbook, current_hand (from pHash),
               arena_state (from Roboflow cache), command_text}
          → Call Claude Haiku 4.5 API (structured output) → 500-800ms
          → Receive: {"card": "Cannon", "zone": "center",
                      "reasoning": "Counters Hog Rider for +1 elixir trade"}
          → Validate: is card in current hand? → Yes → Execute via safeTap()
          ──────────────────────────────────────────────────────────
          TOTAL: ~700-1000ms from end-of-speech
```

**Why this latency is acceptable:** When a human says "defend against hog rider," they're making a strategic request. A human would take 2-5 seconds to think about which card to play. The AI doing it in under 1 second IS the wow.

**The Opus playbook makes Haiku dramatically smarter:** Without the playbook, Haiku reasons from general CR knowledge in 500ms. With the playbook, it pattern-matches against pre-computed strategy. This transforms a reasoning problem into a lookup problem.

## Tier 5: Conditional Path (⏱️ ~400ms + wait)

**What it handles:** Pre-programmed rules that watch for opponent actions and auto-respond.

**Examples:**
- "if hog drop cannon" → watches for enemy hog rider → auto-plays cannon at center
- "if balloon drop musketeer" → watches for enemy balloon → auto-plays musketeer

**Pipeline:**
```
Transcript → Parse trigger (enemy card) + response (your card + zone)
          → Store in Conditional Buffer
          → Background: Roboflow polls arena at 1-2 FPS
          → When trigger card detected (debounce: 2 consecutive frames):
              → Check if response card is in hand
              → If yes: Execute Fast Path via safeTap()
              → If no: Overlay "Card not in hand, rule waiting"
```

**Debounce:** Require target in 2 consecutive Roboflow frames before triggering. Prevents false positives.

## Router Logic (Pseudocode)

```kotlin
fun routeCommand(transcript: String, deck: DeckInfo, hand: HandState, arena: ArenaState): CommandRoute {

    // Step 0: Ignore non-commands
    if (isNonCommand(transcript)) return CommandRoute.Ignore

    // Step 1: Conditional trigger pattern
    val conditionalMatch = tryConditionalMatch(transcript, deck)
    if (conditionalMatch != null) return CommandRoute.Conditional(...)

    // Step 2: Queue/buffer pattern
    val queueMatch = tryQueueMatch(transcript, deck)
    if (queueMatch != null) return CommandRoute.Queue(...)

    // Step 3: Targeting pattern ("[spell] the [troop]")
    val targetMatch = tryTargetMatch(transcript, deck)
    if (targetMatch != null) return CommandRoute.Target(...)

    // Step 4: Fast path ("[card] [zone]")
    val fastMatch = tryFastMatch(transcript, deck)
    if (fastMatch != null) return CommandRoute.Fast(...)

    // Step 5: Card only (no zone) → use Opus playbook default placement
    val cardOnlyMatch = tryCardMatch(transcript, deck)
    if (cardOnlyMatch != null) {
        val defaultZone = deck.defaultZone(cardOnlyMatch.card)
        return CommandRoute.Fast(card = cardOnlyMatch.card, zone = defaultZone)
    }

    // Step 6: Everything else → Smart Path (LLM)
    return CommandRoute.Smart(transcript = transcript)
}
```

**Rationale for ordering:** Most specific (conditional) to least specific (smart path). Prevents "if hog drop cannon" from being parsed as a fast-path play of "cannon."

---

# 5. DECK LOADING PIPELINE

## Demo Deck

**Primary demo:** Starter deck from new CR account (likely Giant, Knight, Archers, Bomber, Arrows, Fireball + 2 cards from tutorial chests — probably Mini P.E.K.K.A, Musketeer, or an Epic like Prince/Baby Dragon/Skeleton Army/Witch). Exact 8 cards TBD after tutorial completion — Opus playbook will be generated for whatever deck lands.

**Testing:** Main account with full card library. Can test Hog 2.6, Golem beatdown, or any meta deck to verify all-deck support.

## Three Methods (in Priority Order)

### Method A: Deck Share Link (Primary — Confirmed Working)

**How it works:**
1. User opens Clash Royale → goes to deck → taps Share → selects Clash Companion from Android share sheet
2. App receives intent with URL: `https://link.clashroyale.com/deck/en?deck=26000002;26000001;26000013;26000005;28000000;26000012;26000015;26000016`
3. App parses semicolon-separated card IDs from URL
4. App looks up card IDs in bundled `cr-api-data` JSON files
5. Returns: card names, types (troop/spell/building), elixir costs, stats
6. Triggers Opus analysis (see Section 10)

**Why this is primary:**
- **Deterministic:** Card IDs are stable numeric identifiers (26000xxx for troops/spells, 28000xxx for buildings). Zero ambiguity, zero error rate.
- **Confirmed format:** RoyaleAPI's deck builder, DeckShop, and in-game share ALL use this same URL format. Parsing is trivial — split on semicolons, map IDs via static lookup.
- **Instant:** No API call, no network, no model inference.
- **Works with all 125+ cards:** Any card in the game has a numeric ID in cr-api-data.
- **No IP issues:** Parsing a URL, not bundling Supercell artwork.

**cr-api-data source:** GitHub: `RoyaleAPI/cr-api-data` — open-source static data including `cards.json` with card IDs, names, types, elixir costs, rarity. Build a static map from card ID → card metadata and bundle it in the APK.

**Implementation:** ~1 hour. Intent filter for `link.clashroyale.com` URLs + split + lookup.

### Method B: Calibrate Mode (Secondary — For pHash Templates)

**How it works:**
1. User enters a training match
2. App enters "Calibrate" mode — captures each card as it appears in hand
3. User labels each card (tap from a list or voice)
4. App stores the captured card crops as pHash templates for this deck

**Why this exists:**
- Deck share link gives card NAMES and METADATA, but NOT what the cards LOOK LIKE on this specific device.
- pHash hand detection needs visual templates from the actual device pixels.
- Only needs to be done once per deck (~2 minutes).

### Method C: Vision Scan (Tertiary — Demo Visual Moment)

- Screenshot of deck screen sent to Claude Haiku 4.5 (vision) → returns card names
- Use as a VISUAL moment in the demo video ("the AI is reading my deck"), even if share link is the actual primary method

---

# 6. MODEL SELECTION & RATIONALE

## Complete Model Stack

| Component | Model | Where It Runs | Latency | Why This Model |
|-----------|-------|---------------|---------|----------------|
| **VAD** | Silero VAD | On-device (bundled with sherpa-onnx) | ~30ms | Battle-tested, Apache 2.0, bundled with sherpa-onnx. |
| **STT** | Moonshine Base (62M) via sherpa-onnx | On-device (CPU, arm64) | 60-150ms | See detailed rationale below. |
| **Hand Detection** | pHash / grayscale correlation | On-device (pure Kotlin math) | <5ms | No model needed. 8 candidates only. |
| **Arena Detection** | YOLO via Roboflow Hosted API | Cloud (Roboflow) | 200-400ms | See corrected model info in Section 9. |
| **Real-time Strategy** | Claude Haiku 4.5 (structured output) | Cloud (Anthropic API) | 500-800ms TTFT | Fastest reliable structured output. GA, not beta. |
| **Pre-match Analysis** | Claude Opus 4.6 | Cloud (Anthropic API) | 15-30s (one-time) | Deepest strategic reasoning. Only runs once per deck load. |

## STT Decision: Moonshine Base via sherpa-onnx

### Why Moonshine Base (62M) instead of Tiny (27M)?
- **Moonshine's documented weakness:** For audio <1 second, repeated tokens with WER >100%. Game commands ("hog", "skarmy", "balloon bridge") are exactly in this danger zone.
- **Base is significantly more accurate on short audio.** Extra 35M parameters help the decoder.
- **Latency tradeoff is acceptable:** Base is 60-150ms vs Tiny's 30-80ms. Extra 50-80ms worth it for accuracy.

### Why sherpa-onnx?
- **Pre-built Android pipeline:** Complete examples with mic → PCM → VAD → Moonshine → text. Copy-paste ready.
- **Confirmed:** Latest release v1.12.13 with pre-compiled Android arm64-v8a binaries downloadable directly.
- **Bundled Silero VAD:** Handles speech start/end detection automatically.
- **Kotlin/Java API:** Native Android integration.
### ⚠️ CORRECTED: Hotword Biasing Is NOT Available for Moonshine

sherpa-onnx hotword biasing is **transducer-only**. Moonshine is an encoder-decoder model (Whisper-family), NOT a transducer. The hotwords file will be silently ignored. Do NOT spend time on hotword setup.

**Primary accuracy strategy instead (no hotwords needed):**
1. **Alias dictionary** — maps slang, abbreviations, and known STT misrecognitions to card names (Section 8)
2. **Deck-scoped fuzzy matching** — Levenshtein against only the 8 cards in the current deck, not 125+. With 8 candidates, even noisy transcripts match correctly most of the time.
3. **Post-correction rules** — hardcoded fixes for known Moonshine errors ("scary" → "Skeleton Army", "blue" → "Balloon")
4. **Audio padding** (below) — ensures the model always gets >1s of audio, avoiding the short-utterance repetition bug

This combination replaces hotword biasing with zero accuracy loss in practice, because we're only ever matching against 8 deck cards.

### Audio Padding
Add 300ms silence padding before and after each detected speech segment. Ensures model always receives >1 second of audio context, mitigating short-utterance repetition.

### Alternatives Rejected

| Alternative | Why Rejected |
|------------|-------------|
| Android SpeechRecognizer | Not guaranteed offline on Samsung. Acceptable as emergency fallback only. |
| Moonshine Tiny | WER >100% on sub-1s commands. |
| Whisper Tiny | Slower for streaming. Designed for batch, not live. |
| Cloud STT | Network dependency kills sub-200ms Fast Path. |

---

# 7. CARD-IN-HAND DETECTION

## pHash Correlation (16×16 Grayscale, 8-Card Deck Only)

1. **Screen capture** via MediaProjection → full-screen bitmap (1080×2340)
2. **Crop 4 card-slot ROIs** at fixed pixel rectangles (bottom of screen)
3. **Downsample** each crop to 16×16 grayscale
4. **Brightness-normalize** each crop: subtract mean, divide by standard deviation (see dim card note below)
5. **Compare** against 8 stored deck templates using normalized correlation
6. **Return** `(slotIndex, confidence)` for the best match

### Why pHash Over Alternatives

| Approach | Speed | Dependencies | Risk |
|----------|-------|-------------|------|
| **pHash correlation** | <5ms | None (pure Kotlin math) | Very low |
| OpenCV template matching | ~10-20ms | OpenCV NDK | Medium |
| Claude Haiku vision | ~500ms | Network + API | Medium |
| On-device classifier | ~50-100ms | TFLite + training | High |

Only matching among 8 known cards. Correlation achieves near-perfect accuracy with zero dependencies.

### ⚠️ CRITICAL: Dim/Greyed Card Handling

In Clash Royale, cards are **dimmed/greyed out** when the player doesn't have enough elixir to play them. This significantly changes pixel brightness and WILL break raw correlation if not handled.

**Required mitigation (implement in M6):**
Brightness-normalize every crop before comparison: subtract the crop's mean pixel value and divide by its standard deviation. This makes correlation invariant to the global brightness shift caused by the dim overlay.

```kotlin
fun normalizePixels(pixels: FloatArray): FloatArray {
    val mean = pixels.average().toFloat()
    val std = sqrt(pixels.map { (it - mean).pow(2) }.average().toFloat())
    if (std < 1e-6f) return pixels // avoid division by zero (blank crop)
    return FloatArray(pixels.size) { (pixels[it] - mean) / std }
}
```

Apply this to BOTH the stored templates AND the live crops before correlation. Templates should be stored in normalized form. This is ~5 lines of code and prevents a class of bugs that would make hand detection look randomly broken during real matches.

**M6 acceptance criteria must include:** test with a card that is dim (insufficient elixir) and confirm it still matches correctly.

### Template Acquisition: Calibrate Mode

First match with new deck: capture each card slot → user labels → store as template. ~2 minutes. No Supercell artwork in repo (IP clean).

### Card Slot Coordinates (approximate, calibrate on device)

| Slot | Center Tap (approximate) |
|------|------------------------|
| 1 (leftmost) | (203, 2130) |
| 2 | (403, 2130) |
| 3 | (603, 2130) |
| 4 (rightmost) | (803, 2130) |

### Scan Frequency
Every 200ms (~5 FPS) in background coroutine. <5ms per scan. Cached and available instantly for any command tier.

---

# 8. COMMAND PARSER & ALIAS SYSTEM

## Alias Dictionary

Maps common slang, abbreviations, and STT misrecognitions to official card names. Primary defense against STT errors.

```kotlin
val cardAliases: Map<String, String> = mapOf(
    // Exact names
    "hog rider" to "Hog Rider",
    "balloon" to "Balloon",
    "skeleton army" to "Skeleton Army",
    "fireball" to "Fireball",

    // Common abbreviations
    "hog" to "Hog Rider",
    "loon" to "Balloon",
    "skarmy" to "Skeleton Army",
    "musky" to "Musketeer",
    "e wiz" to "Electro Wizard",
    "xbow" to "X-Bow",
    "mk" to "Mega Knight",
    "valk" to "Valkyrie",
    "gob barrel" to "Goblin Barrel",
    "barbs" to "Barbarians",
    "e barbs" to "Elite Barbarians",
    "baby d" to "Baby Dragon",
    "nado" to "Tornado",
    "gy" to "Graveyard",

    // Moonshine misrecognitions (tune during testing)
    "blue" to "Balloon",
    "hug" to "Hog Rider",
    "skull army" to "Skeleton Army",
    "scary" to "Skeleton Army",
    // ... (full list for all 125+ cards)
)

val zoneAliases: Map<String, String> = mapOf(
    "bridge" to "bridge",
    "left" to "left_bridge",
    "right" to "right_bridge",
    "center" to "center",
    "middle" to "center",
    "mid" to "center",
    "back" to "back_center",
    "behind" to "back_center",
    // ...
)
```

## Fuzzy Matching

After alias lookup fails, Levenshtein distance matching against DECK CARDS first (8 candidates), then full alias dictionary.

```kotlin
fun fuzzyMatchCard(input: String, deckCards: List<String>): Pair<String, Float>? {
    val bestMatch = deckCards
        .map { card -> card to levenshteinSimilarity(input, card.lowercase()) }
        .maxByOrNull { it.second }
    return if (bestMatch != null && bestMatch.second > 0.6f) bestMatch else null
}
```

---

# 9. ARENA VISION (ROBOFLOW)

## ⚠️ CORRECTED: Model Selection

The original plan referenced "AngelFire YOLO (148 classes)" for troop detection. **This is incorrect.** Research reveals:

- **AngelFire** (`angelfire/clash-royale-cylln`): 1,792 images covering **cards, HP bars, towers** — NOT arena troop positions. Good for card/UI detection, wrong for "find where the hog rider is on the arena."

**Models to test for arena troop detection:**

| Model | Images | Classes | Notes |
|-------|--------|---------|-------|
| **MinesBot** | 4,997 | Units, instance segmentation | Potentially best for in-game troop positions. Highest image count. |
| **Nejc Zavodnik** | ~1,300 | 107 troop classes | "Annotations are not very accurate" — may need confidence tuning |
| **ClashB** | 3,624 | Cards | May be cards not troops — need to verify |

**Action during M8:** Test MinesBot first (best image count + segmentation), fall back to Nejc Zavodnik if MinesBot's classes don't match your needs. Test BOTH before committing to one.

## Pricing (Updated)

Roboflow has moved to a **credit-based system**:
- **Free ("Public") plan:** $60/month in free credits at $4/credit
- **Starter plan:** 10,000 monthly hosted inference API calls, $49-99/month
- **Core plan:** $99/month — recommended given unlimited budget

**Action:** Sign up for Core plan before starting Roboflow integration (Saturday afternoon). Don't let rate limits kill testing.

## How It's Used

### Continuous Polling (Background)

```kotlin
while (matchActive) {
    val frame = screenCapture.latestFrame()
    val croppedArena = cropArenaRegion(frame)
    val base64 = croppedArena.toBase64()

    val response = roboflowApi.detect(
        modelId = "{best-model-from-testing}/1",
        image = base64,
        confidence = 0.4
    )

    arenaStateCache.update(response.predictions)
    delay(500) // ~2 FPS polling
}
```

### Detection Result Caching

```kotlin
data class DetectedTroop(
    val className: String,       // e.g., "enemy_hog_rider"
    val confidence: Float,
    val bbox: BoundingBox,
    val center: Point,
    val timestamp: Long
)
// Old detections (>2 seconds) pruned
```

### Demo Scope

### ⚠️ CORRECTED: Demo on Slow/Stationary Targets

Fast-moving troops (Hog Rider, Balloon) are poor demo targets — they cross the arena in 2-3 seconds, and at 1-2 FPS Roboflow polling the detection is often stale by the time the spell fires. A mis-targeted fireball looks terrible in a demo video.

**Demo targeting with SLOW or STATIONARY targets:**
- `enemy_wizard` — stands still while attacking, high visibility
- `enemy_witch` — stands still while summoning
- `enemy_skeleton_army` — large clumped group, easy to hit with splash
- `enemy_inferno_tower` — building, completely stationary

**Keep for conditional triggers (less precision needed):**
- `enemy_hog_rider` — conditional "if hog drop cannon" works because cannon placement is fixed (center), no aiming needed
- `enemy_balloon` — conditional "if balloon drop musketeer" same logic

The system handles all model classes, but rehearse with slow targets for Targeting Path (Tier 3) and fast targets for Conditional Path (Tier 5) where placement is predetermined.

### ⚠️ M9 IS EXPLICITLY CUTTABLE

If you are behind schedule by Saturday 6 PM, cut Roboflow entirely. Fast Path + Queue Path + Smart Path is a winning demo without arena vision. Targeting and Conditional are impressive additions, not requirements.

---

# 10. SMART PATH: THE TWO-MODEL ARCHITECTURE

## The Concept: Coach + Player

**Claude Opus 4.6 (Coach):**
- Runs ONCE per deck load, before the match
- Takes 15-30 seconds (edited out in video, acceptable wait for GitHub testers)
- Generates comprehensive strategic playbook as structured JSON
- Deep reasoning about matchups, synergies, counter-tables, placement rules

**Claude Haiku 4.5 (Player):**
- Runs per-command during match, only when Smart Path triggered
- Must respond in <800ms
- Uses Opus playbook as context in system prompt
- Makes snap tactical decisions with playbook as cheat sheet

## Opus Prompt (Deck Analysis)

```
You are an expert Clash Royale analyst. Analyze this deck for a real-time voice
command system that will make tactical decisions during live gameplay.

DECK (loaded from game data):
{deck_json_from_cr_api_data}

Generate a comprehensive strategic playbook as JSON with these sections:

1. "archetype": Name and one-sentence playstyle summary

2. "win_conditions": Array of primary and secondary win conditions with strategy

3. "defense_table": Object mapping common opponent cards to best counter FROM THIS
   DECK. Each entry: counter_card, placement, timing, elixir_trade, note.
   Cover: Hog Rider, Balloon, Golem, Royal Giant, Giant, Mega Knight, Elite
   Barbarians, Goblin Barrel, Lava Hound, Graveyard, Minion Horde, Skeleton Army,
   Wizard, Witch, Sparky, X-Bow, Mortar, Three Musketeers, P.E.K.K.A, Prince

4. "offense_plays": Recommended offensive sequences with cards, order, placement

5. "card_placement_defaults": For each card in deck, default offensive + defensive zone

6. "synergies": Key card combinations and how they work

7. "never_do": Critical mistakes to avoid with this specific deck

8. "elixir_management": When to push vs defend based on elixir

Respond ONLY with valid JSON. No other text.
```

## Haiku System Prompt (During Match)

```
You are a real-time Clash Royale tactical advisor. Speed is critical — be decisive.

PLAYER'S DECK: {deck_json}
STRATEGIC PLAYBOOK: {opus_playbook_json}
CURRENT HAND (4 cards): {hand_cards_from_pHash}
ARENA STATE: {arena_state_from_roboflow}

Rules:
- ONLY suggest cards currently in player's hand
- Use defense_table for "defend against X"
- Use card_placement_defaults when no specific zone implied
- For ambiguous commands → safest positive-elixir-trade play

Respond ONLY: {"card": "<name>", "zone": "<zone>", "reasoning": "<max 15 words>"}
```

## Why Opus + Haiku > Haiku Alone

Without playbook: Haiku must recall general CR knowledge, reason about deck strengths, evaluate matchups, choose card + placement — all in ~500ms.

With playbook: Haiku looks up the relevant entry, checks if card is in hand, returns. ~200ms of actual reasoning, rest is network latency. Transforms reasoning into lookup.

---

# 11. INPUT INJECTION (ACCESSIBILITY SERVICE)

## Implementation with Defensive Touch Gate

```kotlin
class ClashCompanionAccessibilityService : AccessibilityService() {

    private var isTouchActive = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> isTouchActive = true
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                handler.postDelayed({ isTouchActive = false }, 100)
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * Safe tap with defensive touch gate.
     * Prevents simultaneous gesture/touch conflicts.
     */
    fun safeTap(x: Float, y: Float): Boolean {
        if (isTouchActive) {
            Log.w(TAG, "Touch active — skipping gesture (defensive gate)")
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun safeDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 200): Boolean {
        if (isTouchActive) return false
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun playCard(slotCenter: Point, targetZone: Point) {
        if (!safeTap(slotCenter.x, slotCenter.y)) {
            overlay.show("⚠️ Release screen first")
            return
        }
        handler.postDelayed({
            safeTap(targetZone.x, targetZone.y)
        }, 50)
    }
}
```

### Manifest & Config

```xml
<service
    android:name=".ClashCompanionAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### M0 Setup Checklist

1. Install APK via ADB or SAI
2. Settings → Apps → Clash Companion → 3-dot menu → "Allow restricted settings" → authenticate
3. Settings → Accessibility → Clash Companion → Enable
4. Verify overlay permission granted
5. Test tap in training match

---

# 12. LATENCY BUDGETS

| Tier | Target | Breakdown |
|------|--------|-----------|
| **⚡ Fast** | <200ms | VAD 30ms + STT 60-150ms + Parse <5ms + pHash <5ms + Tap 75ms |
| **📋 Queue** | <200ms + wait | Same as Fast + variable wait for card cycle |
| **🎯 Target** | <600ms | STT ~170ms + Roboflow cache <5ms (or fresh call 200-400ms) + Tap 100ms |
| **🧠 Smart** | <1200ms | STT ~180ms + Context <10ms + Haiku 500-800ms + Validate <5ms + Tap 100ms |
| **⏱️ Conditional** | 1100-2100ms from opponent play | Roboflow poll 500-1000ms + Debounce 500-1000ms + Tap 100ms |

**VAD trailing silence setting:** 200-250ms recommended. Aggressive (150ms) risks cutting words. Conservative (400ms) adds felt latency.

---

# 13. MEMORY BUDGET

| Component | RAM Usage |
|-----------|----------|
| Android 16 + One UI 8.0 | ~2.5 GB |
| Clash Royale | ~1.2 GB |
| Moonshine Base ONNX model | ~250 MB |
| sherpa-onnx runtime | ~50 MB |
| Silero VAD | ~2 MB |
| pHash templates | <1 MB |
| App + overlay | ~50 MB |
| Screen capture buffer | ~30 MB |
| Arena state cache | ~1 MB |
| Playbook JSON | ~10 KB |
| **TOTAL** | **~4.1 GB** |
| **Available (8GB)** | **~3.9 GB free** |

Set `android:foregroundServiceType="microphone"` for higher priority in Android's low-memory killer.

---

# 14. CONFIDENCE GATING & SAFETY

### ⚠️ CORRECTED: Confidence Gating Is Parser-Level, Not STT-Level

sherpa-onnx with Moonshine does **not** provide calibrated per-utterance confidence scores. The original `sttResult.confidence` check is invalid — that field may not exist or may always return 1.0.

**Gate on parser similarity instead.** The fuzzy matcher already returns a Levenshtein similarity score (0.0–1.0) for the best card match. Use THAT as the confidence signal.

```kotlin
// Gate on parser match quality, NOT STT confidence
val (matchedCard, similarity) = fuzzyMatchCard(transcript, deckCards) ?: run {
    overlay.show("🔇 Didn't catch that")
    return
}
val threshold = getThreshold(matchedCard)
if (similarity < threshold) {
    overlay.show("🔇 Unclear: \"$transcript\"")
    return
}
```

### Parser Confidence Scaled by Elixir Cost
```kotlin
fun getThreshold(card: CardInfo): Float = when {
    card.elixirCost >= 5 -> 0.85f  // Golem, PEKKA — can't afford mistakes
    card.elixirCost >= 3 -> 0.70f  // Mid-cost
    else -> 0.60f                   // Ice Spirit, Skeletons — low risk
}
```

### Location Required for Expensive Cards
Cards costing 5+ elixir REQUIRE explicit location word. Don't guess placement for Golem.

### Hand Verification
Before ANY execution: verify card is in current hand via pHash. If not → "Not in hand" error.

### Smart Path Validation
After Haiku returns: verify card is in hand. If not → Queue it instead of erroring.

---

# 15. UI OVERLAY & APP DESIGN

## Overlay Elements

```
┌─────────────────────────────────┐
│ 🎙️ Listening...                │  ← Microphone state
│ ⚡ "balloon bridge" → 147ms     │  ← Last command + tier + latency
│ 🧠 Cannon at center            │  ← LLM reasoning (Smart Path only)
│   "Counters Hog, +1 elixir"   │
│ 📋 Queue: Balloon → Bridge     │  ← Active queued commands
│ ⏱️ Rule: if Hog → Cannon      │  ← Active conditional rules
└─────────────────────────────────┘
```

**Color coding by tier:** ⚡ green, 📋 blue, 🎯 yellow, 🧠 purple, ⏱️ orange
**Audio feedback:** Confirm beep, error beep, queue trigger sound

## App UI Quality

**This is a differentiator.** Most hackathon apps look like dev prototypes. Clash Companion should have:
- Clean, branded landing screen with "Clash Companion" title
- Setup wizard for permissions (Accessibility, Overlay, Microphone, Screen Capture)
- Deck display showing 8 cards with elixir costs after share link import
- Opus analysis progress indicator with streaming results
- Polished overlay that doesn't look like debug output
- Consistent color scheme and iconography

**The app UI + 4K trailer + landing page are part of the competition strategy.** Judges evaluate presentation quality alongside technical depth.

---

# 16. BUILD ORDER (MILESTONE-GATED)

## ⚠️ SCOPE TRIAGE (Added Post-Review)

### MUST SHIP — Wins hackathon even if everything else dies
- M0: Tap injection works reliably
- M1: Screen capture works
- M2: On-device STT works (Moonshine or SpeechRecognizer fallback)
- M3: Command parser + end-to-end Fast Path
- M6: pHash hand detection (with dim-card normalization)
- M7: Queue Path
- Polished overlay showing command + tier + measured latency

### SHOULD SHIP — Adds LLM wow factor, moderate risk
- M4/5: Deck loading + Opus playbook generation
- M8: Smart Path (Claude Haiku with Opus playbook)

### CUT IF BEHIND BY SATURDAY 6 PM — Highest risk, lowest predictability
- M9: Roboflow targeting + conditional triggers
- If attempted, demo targeting on slow/stationary targets only

**The "must ship" list alone is a killer demo: voice commands at 170ms on a $300 phone, with queue automation and a polished UI. Smart Path adds the AI reasoning narrative. Roboflow adds visual spectacle but is not required to win.**

## Non-Negotiable Rules

1. **Test on Samsung A35 after EVERY milestone.** Emulators lie.
2. **Commit after every working milestone.** Revertible state.
3. **Don't add features until current milestone is green.**
4. **If Milestone 0 fails, pivot immediately** (ADB-tethered approach).

## PRE-BUILD: Device Prep (Before M0)

- [ ] Strip Samsung bloat (disable unused apps, clear cache)
- [ ] Disable battery optimization for development
- [ ] Enable Developer Options + USB debugging
- [ ] Verify ADB connection from Cursor/terminal
- [ ] Set up Android MCPs in Cursor: `mobile-mcp` (via npx @mobilenext/mobile-mcp@latest) + `android-mcp` (via uvx android-mcp)
- [ ] Verify Anthropic API key has billing enabled
- [ ] Install SAI from Play Store (for bypassing restricted settings)

## FRIDAY NIGHT (~10:30 PM → 4:00 AM = 5.5 hours)

*Phone update + MCP setup + device prep eating first 1.5 hours. Adjusted accordingly.*

### Milestone 0: Tap Injector Works (10:30 PM – 12:30 AM, 2 hours) ⛔ GATE

**Goal:** Prove you can inject taps inside Clash Royale on Samsung A35 running Android 16.

**Steps:**
1. Create Android project in Cursor
2. Install APK via SAI (bypasses restricted settings) OR via ADB + manual "Allow restricted settings"
3. Implement `ClashCompanionAccessibilityService` with `safeTap()` (defensive touch gate)
4. Add overlay with "Tap Test" button
5. Enable Accessibility Service in settings
6. Open Clash Royale → open training match
7. Press "Tap Test" → verify it taps in the arena
8. **Calibrate coordinates:** Screenshot via MediaProjection, measure exact card slot and zone positions
9. Test 10 taps in a row without freeze

**Success criteria:** Reliably tap a card slot and place a card from overlay button. No freezes.

**If this fails by 12:30 AM:** Pivot to ADB-tethered approach (laptop sends `adb shell input` commands via USB).

**Why this is first:** Every other component produces coordinates. If coordinates can't become taps, nothing works.

```
✅ COMMIT: "M0 — Tap injection working in Clash Royale on Android 16"
```

### Milestone 1: Screen Capture Works (12:30 – 1:15 AM, 45 min)

**Goal:** MediaProjection captures game frames reliably.

**Steps:**
1. Implement MediaProjection setup (user grants permission)
2. Capture frames to ImageReader → convert to Bitmap
3. Verify exact resolution (should be 1080×2340)
4. Confirm card slot boundaries and arena zones from captured image

```
✅ COMMIT: "M1 — Screen capture working, coordinates calibrated"
```

### Milestone 2: Voice → Text on Device (1:15 – 3:15 AM, 2 hours)

**Goal:** On-device STT produces text from voice.

**Steps:**
1. Add sherpa-onnx AAR dependency
2. Bundle Moonshine Base ONNX model + Silero VAD in assets
3. Create hotwords file with card names + aliases
4. Implement mic capture → sherpa-onnx streaming ASR pipeline
5. Display transcript in overlay
6. Configure endpointing (200-250ms trailing silence)
7. Add 300ms silence padding

**Success criteria:** "balloon bridge" → recognized within ~500ms. "skarmy" → recognized (hotword biasing).

**Fallback:** If sherpa-onnx doesn't work after 2 hours, switch to Android `SpeechRecognizer` API.

```
✅ COMMIT: "M2 — On-device STT working with Moonshine Base"
```

### Milestone 3: Command Parser + End-to-End Fast Path (3:15 – 4:00 AM, 45 min)

**Goal:** Voice → parsed command → card played in game.

**Steps:**
1. Implement alias dictionary (all cards + slang)
2. Implement zone alias dictionary
3. Implement Levenshtein fuzzy matcher
4. Implement command router (all 5 tier patterns)
5. Implement confidence gating
6. Wire STT → parser → Accessibility tap (hardcoded card positions for now)
7. Test in training match: "balloon bridge" → balloon plays

**Success criteria:** 3 voice commands in a row execute correctly without crash. If time is short, get at least the parser working (alias dict + fuzzy match) and wire end-to-end Saturday morning.

```
✅ COMMIT: "M3 — End-to-end voice to card play working"
🎉 REALISTIC FRIDAY TARGET: M0 + M1 + M2 done. M3 started or complete.
```

### Optional Friday Bonus: Milestone 4: Deck Loading (only if M3 done AND energy remains)

If M3 is complete and you're still sharp, start deck loading. Otherwise, first thing Saturday.

```
✅ COMMIT IF DONE: "M4 — Deck share link loading"
💤 SLEEP by 4:00 AM maximum. No exceptions.
```

## SATURDAY (9:00 AM → 2:00 AM = 17 hours)

*If M3 (parser + end-to-end Fast Path) wasn't completed Friday night, finish it FIRST before starting M4/5. Budget 1-1.5 hours. All Saturday times shift accordingly.*

### Milestone 4/5: Deck Loading + Opus Playbook (9:00 – 11:00 AM, 2 hours)

**Goal:** Load any deck via share link + generate Opus playbook.

**Steps:**
1. Implement Android share intent receiver for CR deck links
2. Parse card IDs (split on `;` from URL query param `deck`)
3. Bundle cr-api-data JSON, build card ID → metadata lookup
4. Implement Opus API call with deck analysis prompt
5. Parse and cache playbook JSON locally
6. Pre-generate playbook for demo deck as fallback
7. Display "✅ Deck loaded: [archetype name]"

```
✅ COMMIT: "M5 — Deck loading + Opus strategic playbook"
```

### Milestone 6: pHash Hand Detection (11:00 AM – 1:30 PM, 2.5 hours)

**Goal:** Accurately identify which card is in which hand slot.

**Steps:**
1. Card slot ROI cropping from screen capture
2. 16×16 grayscale downsampling
3. Normalized correlation comparison
4. "Calibrate" mode: capture + label cards
5. Continuous hand scanning (every 200ms)
6. Replace hardcoded positions from M3 with dynamic pHash
7. Test 10 commands with pHash-detected slots

**Success criteria:** >95% accuracy among 8 known cards. <5ms per scan.

```
✅ COMMIT: "M6 — pHash hand detection, full Fast Path polished"
```

### Milestone 7: Queue Path (1:30 – 3:00 PM, 1.5 hours) ⬆️ MOVED UP

**Goal:** Buffered commands work.

**Why moved up:** Uses ONLY M6 infrastructure (pHash hand scanning). Zero new dependencies. Zero new APIs. Fully offline. If everything after this fails, you still have Fast Path + Queue Path = two strong demo tiers.

**Steps:**
1. Implement command queue buffer data structure
2. Watch pHash hand scan → execute when queued card appears
3. Test "queue balloon bridge" → auto-plays when balloon cycles in
4. Implement sequential macro: "hog bridge then log"
5. Add overlay display of active queues

```
✅ COMMIT: "M7 — Queue Path working"
```

### Milestone 8: Smart Path / Claude Haiku (3:00 – 5:30 PM, 2.5 hours)

**Goal:** Strategic voice commands via Haiku + Opus playbook.

**Steps:**
1. Implement Anthropic API client (Kotlin + OkHttp)
2. Build Haiku system prompt template with playbook injection
3. Implement context assembly (hand + playbook + command)
4. Structured output config → parse JSON response
5. Validate card in hand → execute via safeTap()
6. Display reasoning on overlay
7. Test: "defend against hog rider" → Cannon at center

**Success criteria:** Strategic commands return correct cards in <1.2s. Reasoning displayed.

```
✅ COMMIT: "M8 — Smart Path with two-model architecture"
```

### Milestone 9: Roboflow Targeting + Conditional (5:30 – 8:30 PM, 3 hours)

**Goal:** "Fireball the hog rider" + "if hog drop cannon"

**Steps:**
0. **Sign up for Roboflow Core plan ($99/mo) + get API key** (do this FIRST, 5 minutes)
1. Implement Roboflow API client
2. **Test multiple models:** Start with MinesBot, try Nejc Zavodnik if needed
3. Implement arena region cropping + background polling (1-2 FPS)
4. Implement arena state cache with timestamp pruning
5. Wire Targeting Path: cache lookup → coordinate extraction → safeTap()
6. Implement "Target not found" graceful failure
7. Implement Conditional Path: buffer + Roboflow trigger + debounce
8. Test: "fireball the hog" → fireball on hog. "if hog drop cannon" → auto-counter.

**Success criteria:** Targeting works for 2-3 troop types. Conditional triggers correctly.

```
✅ COMMIT: "M9 — Targeting + Conditional Paths working"
```

### Milestone 10: Polish & Robustness (8:30 – 11:00 PM, 2.5 hours, including dinner)

**Steps:**
1. Play 5 full training matches using voice only
2. Log every command with outcome
3. Fix top 3 failure modes
4. Tune fuzzy match thresholds
5. Add audio feedback sounds
6. Polish overlay (colors, tier badges, animations)
7. Polish app UI (landing screen, setup wizard, deck display)
8. Handle edge cases: notifications, battery, screen lock, game audio

```
✅ COMMIT: "M10 — Polished and robust"
```

### Milestone 11: Safety Video (11:00 PM – 1:00 AM, 2 hours)

**Goal:** Record a "good enough" demo video as safety net.

1. Write demo script (Section 17)
2. Record 3-4 takes with screen recording
3. Quick edit: trim dead air, cut between tiers
4. Verify one clean take showing all tiers

```
✅ COMMIT + PUSH: "M11 — Safety video, repo pushed"
💤 SLEEP by 2:00 AM maximum.
```

## SUNDAY (9:00 AM → 3:00 PM = 6 hours)

### Fresh Testing (9:00 – 10:00 AM)
Reboot phone, fresh APK install, run through demo script, fix regressions.

### Final 4K Trailer Recording (10:00 AM – 12:00 PM)
- Multiple takes with perfect execution
- **4K quality** — use external recording if internal capture is limited
- Show each tier with clear examples + latency counter
- Show Opus deck analysis moment
- Record until one GREAT take

### Landing Page / Junction Page (12:00 – 12:30 PM)
- Build a polished project page (can be GitHub Pages or simple HTML)
- Embed video, architecture diagram, download link
- This is the first thing judges see — make it count

### GitHub Packaging (12:30 – 1:30 PM)
- Clean README (see Section 18)
- Clean code (remove debug logs, add key comments)
- MIT License
- Roboflow attribution (CC BY 4.0)
- Pre-built APK as GitHub Release (avoids 300MB+ repo clone for testers)
- Note Moonshine model size in README

### Submission (1:30 – 2:30 PM)
- Upload video
- Fill submission form
- Verify GitHub is public
- Share in hackathon Discord

### Buffer (2:30 – 3:00 PM)
Emergency fixes only. No new features.

---

# 17. VIDEO DEMO & TRAILER STRATEGY

## Demo Structure (3-4 minutes, 4K)

*Note: Commands below use Hog 2.6 as examples. Adapt to actual demo deck (likely starter deck with Giant, Knight, Archers, Fireball, Arrows, etc.). Giant-based examples: "giant back", "fireball the [troop]", "defend against [X]" → Knight at center. Can also switch to main account for Hog 2.6 demo takes if preferred.*

### Opening (30 seconds)
- "**Clash Companion** — Play Clash Royale With Your Voice"
- "Built solo in 48 hours for Supercell AI Hackathon"
- "Running on a **$300 Samsung Galaxy A35** — not a flagship, not a desktop, a mid-range phone."
- "Five AI technologies. One real-time routing system."

### Deck Loading (30 seconds)
- Show sharing deck link from CR to app
- Show Opus analysis (cut to result)
- "Claude Opus analyzes your specific deck and generates a strategic playbook"

### Fast Path (45 seconds)
- "balloon bridge" → ⚡ 147ms
- "hog left" → ⚡ 163ms
- "cannon center" → ⚡ 152ms
- "Simple commands execute in under 200 milliseconds, fully on-device"

### Targeting (45 seconds)
- Opponent plays hog → "fireball the hog rider" → fireball lands on hog
- 🎯 412ms
- "Real-time computer vision tracks moving troops and targets spells dynamically"

### Smart Path (45 seconds)
- "defend against his push" → Claude picks optimal card
- 🧠 Cannon at center — "Counters Hog, +1 elixir trade" — 834ms
- "Claude Haiku uses the Opus playbook to make pro-level decisions in under a second"

### Queue + Conditional (30 seconds)
- "if he plays balloon, drop musketeer" → auto-triggers
- "queue hog at the bridge" → auto-plays when card cycles in
- "Buffer commands. Set conditional rules. Hands-free gameplay."

### Positioning (15 seconds)
- "Voice companion overlay — not a fully autonomous bot."

### Closing (15 seconds)
- Architecture diagram briefly
- "Open source on GitHub" + link
- "Clash Companion: five AI technologies, one intelligent routing system."

## Video Production Notes

- **4K quality** — well-edited trailer, not a screen recording with voiceover
- **⚠️ Recording method TBD:** A35 internal screen recording caps at 1080p. For true 4K: external camera filming phone screen, capture card, or upscale in post. Decide before Sunday morning.
- **Edit aggressively** — cut dead air, failed takes, thinking time
- **Latency counter visible** in every command execution
- **Use slangy commands** at least once ("yo drop a loon") for natural language resilience
- **Total <4 minutes** — judges watch many videos, respect their time

---

# 18. GITHUB PACKAGING & TESTABILITY

## README Structure

```markdown
# 🎮 Clash Companion — Play Clash Royale With Your Voice

> Five-tier AI routing system combining on-device speech recognition,
> computer vision, and cloud LLM strategic reasoning. Running on a
> $300 mid-range Android phone.

## 📱 Demo Video
[4K trailer embed/link]

## ⚡ Latency
- Fast Path: ~170ms (on-device)
- Targeting: ~400ms (computer vision)
- Smart Path: ~800ms (LLM reasoning)

## 🚀 Quick Start

### Prerequisites
- Android phone (tested on Samsung A35, Android 16)
- Clash Royale installed
- API keys: Anthropic (Claude), Roboflow

### Install
Option A: Download pre-built APK from [Releases]
Option B: Build from source: `./gradlew assembleDebug`

### Setup
1. Install APK
2. Allow restricted settings: Settings → Apps → Clash Companion → ⋮ → Allow
3. Enable Accessibility: Settings → Accessibility → Clash Companion
4. Open app → share deck link from Clash Royale
5. Wait ~20s for Opus analysis
6. Enter training match → Calibrate cards → Start commanding!

### ⚠️ Known Issue: Android 15
If running Android 15, don't touch screen while voice commands execute (gesture freeze bug).
Android 16+ likely fixes this, but hands-free operation is recommended regardless.

## 🎯 Commands
[command table with tiers]

## 🏗️ Architecture
[diagram]

## 📦 Note on APK Size
Moonshine Base model is ~250MB. Total APK is ~300MB+.
Pre-built APK available in Releases to avoid building from source.
```

---

# 19. EDGE CASES & FAILURE MODES

| Edge Case | How System Handles It |
|-----------|----------------------|
| **User touches screen during voice command** | `safeTap()` gates on touch state. Skips gesture, shows "⚠️ Release screen first". |
| **Card not in deck** | Parser can't match → "Card not in your deck" overlay. No action. |
| **Card not in hand** | pHash detects absence → "Not in hand" + error beep. |
| **Elixir too low** | System doesn't check elixir. Card fails to play in-game. Acceptable. |
| **Similar card names** | "Musketeer" vs "Three Musketeers" → alias dict disambiguates. |
| **Background noise / game audio** | Silero VAD filters non-speech. Game audio not a major issue. Mute for demo as nice-to-have. |
| **Rapid-fire commands** | FIFO queue. Execute sequentially with 100ms gap. |
| **Notification interrupts** | Detect non-game screen, pause command execution. |
| **Phone rotation** | Locked to portrait in manifest. |
| **Screen brightness change** | pHash with mean/std normalization is brightness-invariant. |
| **Card dimmed (insufficient elixir)** | Brightness normalization (subtract mean, divide by std) before correlation handles the dim overlay. Templates stored in normalized form. |
| **Opponent plays same card twice** | Conditional fires on first detection. Default "fire once" for demo safety. |
| **Smart Path returns card not in hand** | Validate → if not in hand, Queue it automatically. |
| **Network down** | Smart/Targeting paths fail gracefully. Fast + Queue paths fully offline. |
| **Haiku malformed JSON** | Try-catch → retry once → error. Structured output GA makes this very rare. |
| **Roboflow model wrong for troop detection** | Test MinesBot AND Nejc Zavodnik during M9. Fall back to manual coordinate targeting. |
| **Samsung kills background service** | Foreground service type + battery optimization disabled + pinned in recents. |

---

# 20. RISK REGISTER

| Risk | Severity | Probability | Status | Mitigation |
|------|----------|-------------|--------|-----------|
| **dispatchGesture freeze** | MEDIUM | Low (~5%) on Android 16, Medium (~20%) on 15 | ✅ LIKELY FIXED | `safeTap()` with touch-state gating kept as defensive code. Demo is hands-free. |
| **Restricted Settings blocking Accessibility** | LOW | 100% (expected) | ✅ SOLVED | One-time bypass via Settings or SAI install. |
| **Moonshine accuracy on short commands** | HIGH | Medium (~25%) | ✅ MITIGATED | Moonshine Base + alias dictionary + deck-scoped fuzzy matching + audio padding + SpeechRecognizer fallback. (Hotword biasing NOT available — transducer-only in sherpa-onnx.) |
| **sherpa-onnx integration** | MEDIUM | Low (~10%) | ✅ CONFIRMED | v1.12.13 with pre-built arm64-v8a binaries. Android examples exist. |
| **Roboflow model wrong for troop detection** | MEDIUM | Medium (~35%) | ⚠️ NEEDS TESTING | AngelFire is cards/UI only. Test MinesBot + Nejc Zavodnik. |
| **Roboflow latency spikes** | MEDIUM | Medium (~25%) | ⚠️ | Core plan for rate limits. Reduce to 0.5 FPS if >600ms. |
| **Samsung kills background services** | MEDIUM | Medium (~30%) | ✅ MITIGATED | Dev device prep: disable battery optimization, foreground service, pin in recents. |
| **Opus generates bad playbook** | LOW | Low (~10%) | ✅ MITIGATED | Validate JSON. Pre-cache demo deck playbook as fallback. |
| **Time overrun** | HIGH | Medium (~30%) | ⚠️ | Hard time boxes. Queue Path moved up as insurance. |
| **Game audio interferes with STT** | LOW | Low (~10%) | ✅ CONFIRMED OK | VAD handles it. Mute for demo as nice-to-have. |
| **Deck share link format changed** | LOW | Very low (~5%) | ✅ CONFIRMED | Format stable: semicolon-separated IDs. Tested. Calibrate Mode as fallback. |

**Overall: ~85% chance of submitting a working project. ~40-50% chance of top 3.**

---

# 21. API KEYS & ACCOUNTS NEEDED

| Service | What | Action |
|---------|------|--------|
| **Anthropic API** | API key with billing | platform.claude.com → API Keys |
| **Roboflow** | Core plan ($99/mo) + API key | app.roboflow.com → signup → upgrade |
| **SAI** | Split APK Installer | Install from Play Store (for restricted settings bypass) |

Store keys in `local.properties` (git-ignored):
```properties
ANTHROPIC_API_KEY=sk-ant-...
ROBOFLOW_API_KEY=...
```

**No backend. No Supabase. No edge functions.** Keys live on-device only. For GitHub testers: README instructs them to create their own API keys and add to `local.properties`. Pre-generated demo deck playbook bundled as fallback for testers without Anthropic key.

---

# 22. COST ESTIMATE

| Service | Usage | Cost |
|---------|-------|------|
| Claude Opus 4.6 | ~20 analyses | ~$2.50 |
| Claude Haiku 4.5 | ~500 commands | ~$1.50 |
| Roboflow Core | 1 month | $99 |
| **Total** | | **~$103** |

Budget is unlimited. Irrelevant but noted for completeness.

---

# 23. WHAT NOT TO BUILD

| Don't Build | Why |
|-------------|-----|
| **Multiple games** | One game flawless wins. Three games janky loses. |
| **On-device YOLO** | Mali GPU delegate debugging = weekend gone. Roboflow works. |
| **On-device LLM** | 2-26 second latency on mobile. Wrong tool. |
| **Full 109-card template library** | Only match among 8 deck cards. |
| **Automatic elixir reading** | Fragile OCR. Opus playbook encodes elixir awareness. |
| **LLM for every command** | "Balloon bridge" doesn't need Claude. Route to Fast Path. |
| **Custom-trained vision model** | Use existing Roboflow models. |
| **iOS version** | No Accessibility Service tap injection on iOS. |
| **Full autonomous bot** | You're building a voice COMPANION, not a bot. |

---

# APPENDIX A: REFERENCE PROJECTS

| Project | What to Use | Link |
|---------|------------|------|
| **claude-royale** | Coordinate mappings, card play functions | github.com/houseworthe/claude-royale |
| **ClashRoyaleBuildABot** | Arena coordinates, template approach | github.com/Pbatch/ClashRoyaleBuildABot |
| **sherpa-onnx Android** | Complete Android ASR pipeline | github.com/k2-fsa/sherpa-onnx/tree/master/android |
| **RoyaleAPI cr-api-data** | Card data (IDs, names, types, elixir) | github.com/RoyaleAPI/cr-api-data |
| **Roboflow MinesBot** | Arena troop detection model | universe.roboflow.com (search MinesBot) |
| **Roboflow Nejc Zavodnik** | 107 troop classes (backup) | universe.roboflow.com |
| **mobile-mcp** | Cursor MCP for screenshots, UI hierarchy, tap/swipe, app install | github.com/mobile-next/mobile-mcp |
| **android-mcp** | Cursor MCP for shell commands, logcat, device state, notifications | github.com/CursorTouch/Android-MCP |

---

# APPENDIX B: THE NARRATIVE (FOR JUDGES)

> "**Clash Companion** uses a five-tier AI routing system to play Clash Royale by voice — a voice-controlled companion for hands-free gameplay.
>
> Before each match, **Claude Opus** analyzes your specific deck and generates a strategic playbook. During gameplay, simple commands execute in **170 milliseconds** using on-device Moonshine speech recognition. Spell targeting uses **real-time YOLO vision** to track moving troops. Strategic commands use **Claude Haiku** with the Opus playbook to make pro-level decisions in under a second.
>
> All running on a **$300 Samsung Galaxy A35**. Five AI technologies. One intelligent system. Open source."

---

*Document synthesized from three independent AI analyses plus iterative human review. Updated with Android 16 upgrade, corrected Roboflow models, resequenced build order, and adjusted timeline. Every major decision validated by at least two independent analyses.*
