# Clash Companion — Agent Handoff Document (Updated Through M7 Completion + Polish)

## What Was Done — Full Status

### Pre-Build: Device Prep (COMPLETE)
- Samsung A35 (SM-A356E) via ADB — serial R5CXB26K8HD
- Android 16 / One UI 8.0, Screen: 1080x2340, arm64-v8a, Exynos 1380
- 151 Samsung bloatware packages disabled
- Animations 0, WiFi sleep NEVER, background process limit 2, screen timeout 30min

### M0: Tap Injector (COMPLETE)
- ClashCompanionAccessibilityService.kt — safeTap(), safeDrag(), playCard() with touch-state gating
- Coordinates.kt — relative coordinates scaled from 1080x2340 reference
- OverlayManager.kt — TYPE_APPLICATION_OVERLAY floating panel with test buttons + listen toggle

### M1: Screen Capture (COMPLETE)
- ScreenCaptureService.kt — foreground service, MediaProjection, ImageReader, AtomicReference bitmap buffer
- Delayed bitmap recycling keeps previousFrame alive for one extra capture cycle
- Verified: 1080x2340 capture working

### M2: Voice to Text (COMPLETE)
- SpeechService.kt — Zipformer Transducer int8 with hotword biasing via sherpa-onnx
- Silero VAD (250ms trailing silence), 300ms silence padding
- Model loads in ~7 seconds, inference 150-600ms
- Deck-aware hotwords: DeckManager writes boosted hotwords file to filesDir at deck load (deck cards weight 6.0, non-deck 1.0). SpeechService checks filesDir first, falls back to assets.

### M3: Command Parser + Fast Path (COMPLETE)
- CommandParser.kt — five-tier routing: FAST, QUEUE, TARGETING, SMART, CONDITIONAL, IGNORE
- CommandRouter.kt — routes parsed commands, FAST path executes via safeTap(), "then"/"than" chaining splits compound commands
- CardAliases.kt — 140+ card aliases, 30+ compound aliases (pecarite, peklift, musketeeroit, mini peckerite, etc.), elixir costs, card types
- Fuzzy match levels 3/4 resolve through findDeckVariant (e.g., P.E.K.K.A → Mini P.E.K.K.A when deck has Mini)
- Voice commands WORK end-to-end: 86% accuracy across 5 live-match iterations

### M4/5: Deck Loading + Opus Playbook (COMPLETE)
- DeckManager.kt: parses CR deck share links, resolves card IDs via bundled card-data.json, writes deck-boosted hotwords
- AnthropicClient.kt: OkHttp wrapper for Anthropic Messages API (claude-opus-4-6)
- OpusCoach.kt: pre-match deck analyzer generating strategic playbook JSON
- Deck persisted to SharedPreferences, playbook cached to internal storage

### M5.5: Compose UI + CR Share Fix + Card Art (COMPLETE)
- Migrated from XML to Jetpack Compose with Supercell-styled theme
- CrCard, CrButton, CrBackground, TierIcons components
- Card art loaded from RoyaleAPI CDN via Coil

### M6: Hand Detection (COMPLETE — On-Device ML Classifier)

#### What works:
- YOLOv8n-cls model trained on Roboflow in-game card dataset (103 CR card classes, Aug 2025)
- 98.1% top-1 accuracy on validation set, ~3ms inference per card on CPU
- CardClassifier.kt loads card_classifier.ptl (6.1MB) via PyTorch Mobile Lite
- Deck-restricted classification: output masked to only 8 loaded deck cards (not all 103)
- Temporal smoothing with DECAY: cards persist for 5 missed scans (~1s grace), then drop from hand state. Prevents stale cards after match end or card play.
- Confidence threshold at 5% (very low since only 8 candidates)
- Live accuracy on Samsung A35: ~80-90% across the 8 demo deck cards
- Inference latency: ~500ms per 5-card scan (includes bitmap copy + crop + resize + inference)
- Batch getPixels(): single JNI call reads all 50K pixels instead of per-pixel getPixel() calls. Reusable IntArray buffer. FloatArray is NOT reusable (Tensor.fromBlob holds reference).
- Match-exit detection: 10 consecutive non-match frames (2s) resets matchDetected, clears hand state. Multi-slot brightness check (slots 0 AND 2) for robustness.

#### Known limitations:
- Dimmed/greyed cards (low elixir) are NOT recognized — model was trained on full-color cards only. Temporal smoothing decay drops them after ~1 second.
- Slot 0 (leftmost) has slightly lower accuracy than other slots
- Training dataset was only 342 images (3-6 per class)
- The model only covers 103 cards from Aug 2025

#### Architecture decisions:
- PyTorch Mobile Lite chosen over TFLite because TensorFlow doesn't support Python 3.14 (user's version)
- ONNX Runtime rejected because sherpa-onnx bundles its own libonnxruntime.so — linker conflict
- Model output is ALREADY softmax probabilities. DO NOT apply softmax again.
- Tensor.fromBlob() stores a REFERENCE to the Java float array, NOT a copy. DO NOT reuse a shared FloatArray across sequential classify calls — each call needs its own array.

### M7: Queue Path (COMPLETE — Full Implementation)

#### What works:
- "queue/next/then [card] [zone]" commands parsed by CommandParser, executed by CommandRouter
- "then"-chaining: "knight left then archers right" plays knight immediately, queues archers
- "than" and "and then" normalized to "then" for STT misrecognition handling
- Queue entries stored as QueuedCommand(card, zone, timestamp, lastAttemptMs) in FIFO list
- checkQueue() called on BOTH hand state changes AND a periodic 2-second timer
- Elixir retry with cooldown: if card is in hand but tap fails (low elixir), retries every 1.5s
- 5-second grace period before removing "played" entries — prevents dimmed cards (temporarily invisible) from being incorrectly marked as played
- Queue cleared on overlay hide, entries expire after 120 seconds
- Queue status displayed in overlay hand text area

#### Key design decisions:
- All queue access on main thread (Looper guarantee) — no synchronization needed
- Periodic 2s timer ensures retries happen even when hand state is stable (all cards dimmed)
- "then" segments ALWAYS queued (never immediate) — respects user intent for sequencing
- executeQueuePath always adds to queue first, then triggers checkQueue if card is in hand

### M8: Smart Path / Gemini Flash (NOT STARTED — GeminiClient.chat() built but not wired)
### M9: Roboflow Targeting + Conditional (NOT STARTED — CUT from scope)
### M10: Polish (PARTIALLY DONE — minimize/maximize overlay, but still has debug buttons)
### M11: Safety Video (NOT STARTED)

## Current Files State

### Last pushed commit: b3d8b91 (refactor(M7): Polish, perf, and STT accuracy) on main

### Uncommitted changes (functional — need to be committed):
- `CommandRouter.kt` — queue retry fix: 5s grace period for played entries + QUEUE_PLAYED_GRACE_MS constant
- `OverlayManager.kt` — periodic 2s queueCheckRunnable timer for elixir retry
- `ClashCompanionAccessibilityService.kt` — LF vs CRLF warning only (no functional change)

### Untracked files:
- `HANDOFF.md` — this file
- `zone_annotations.png` — annotated screenshot for coordinate verification
- `app/src/main/res/drawable/bg_button_gold.xml`, `bg_card.xml`, `bg_card_item.xml`, `bg_elixir_badge.xml`, `bg_status_pill.xml` — Compose UI drawables from M5.5
- `app/src/main/res/values/colors.xml` — color definitions from M5.5

### Key files and what they do:
- `CardClassifier.kt` — loads PTL model, batch getPixels(), brightness normalization, deck-restricted classification
- `HandDetector.kt` — background scanner, temporal smoothing with 5-scan decay, match-exit detection (10 frames), multi-slot brightness check
- `CommandRouter.kt` — handleTranscript splits on "then"/"than", routeCommand dispatches tiers, executeQueuePath adds to queue, checkQueue with elixir retry + periodic timer, executeFastPath with confidence gating
- `CommandParser.kt` — five-tier parsing, fuzzy match with findDeckVariant on levels 3/4, "then" prefix for queue
- `OverlayManager.kt` — minimize/maximize toggle (CC button), periodic 2s queue check timer, hand display with queue status
- `DeckManager.kt` — deck share link parsing, writeDeckHotwords() for STT boosting
- `SpeechService.kt` — loads deck-boosted hotwords from filesDir if available
- `Coordinates.kt` — 40+ zone aliases including defend, top, his/my tower, king, STT misrecognitions
- `CardAliases.kt` — 140+ card aliases, 30+ compound aliases from 5 live-match log analysis sessions
- `GeminiClient.kt` — Gemini 3 Flash API client with text + vision, JSON mode, thinkingLevel=minimal. BUILT AND TESTED but not wired into SMART tier.
- `OpusCoach.kt` — Opus playbook generator. cachedPlaybook available as String for SMART tier.
- `AnthropicClient.kt` — Anthropic Messages API wrapper (claude-opus-4-6)
- `MainActivity.kt` — Compose UI, permission checklist, deck intent handling, CardClassifier.init()

## API Keys
- Anthropic: `BuildConfig.ANTHROPIC_API_KEY` (set in local.properties)
- Gemini: `BuildConfig.GEMINI_API_KEY` (set in local.properties)
- Roboflow: `BuildConfig.ROBOFLOW_API_KEY` (empty — not used at runtime)
- Supercell API token: eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiIsImtpZCI6IjI4YTMxOGY3LTAwMDAtYTFlYi03ZmExLTJjNzQzM2M2Y2NhNSJ9.eyJpc3MiOiJzdXBlcmNlbGwiLCJhdWQiOiJzdXBlcmNlbGw6Z2FtZWFwaSIsImp0aSI6IjU4NjlhNTc3LWFiNjUtNGM3Mi04NmJmLTZmMjRhMDdiMjAzNSIsImlhdCI6MTc3MDUyNjg3MSwic3ViIjoiZGV2ZWxvcGVyLzEyMThhMTkzLThjNjktZWM5Yi02ZDQzLTM1MTkzMzZlOTUzMyIsInNjb3BlcyI6WyJyb3lhbGUiXSwibGltaXRzIjpbeyJ0aWVyIjoiZGV2ZWxvcGVyL3NpbHZlciIsInR5cGUiOiJ0aHJvdHRsaW5nIn0seyJjaWRycyI6WyIxNTkuMjYuMTA2LjE2Il0sInR5cGUiOiJjbGllbnQifV19.MhJV9eZ2rOFG-BDsGzja5upaWCC3Ewy4D0-eNc9OdfwSa53jg4hVp85ZOUrR5c-x1yiDEi6oBvQlXTlZQn-y4w (IP-whitelisted to 159.26.106.16)
- Roboflow account API key: wdpkXFEbMtF7Tg3umxlu

## Git State
- Local git repo on branch `main` with remote `origin` → `github.com/youssofal/Clash-Companion`
- Latest pushed commit: b3d8b91 (refactor(M7): Polish, perf, and STT accuracy from 5 live-match iterations)
- 2 uncommitted functional changes (CommandRouter queue grace + OverlayManager periodic timer) — should be committed
- 4 stale PRs (#2-#5) CLOSED with superseded-by-M6 comments
- 0 open PRs

## Demo Deck
Knight, Archers, Minions, Mini P.E.K.K.A, Fireball, Giant, Musketeer, Arrows

## Critical Warnings for Next Agent
1. NEVER run `adb shell am force-stop com.yoyostudios.clashcompanion` — permanently breaks accessibility on Samsung. Use `adb reboot` if needed.
2. After EVERY APK install, accessibility service needs re-toggling: Settings → Accessibility → Clash Companion → off → Turn off → wait 2s → on → Allow.
3. The overlay MUST call `setPassthrough(true)` before any `dispatchGesture` call targeting arena coordinates.
4. ScreenCaptureService.getLatestFrame() returns a LIVE bitmap that can be recycled. ALWAYS copy with `frame.copy()` before processing in coroutines.
5. MediaProjection screenshots INCLUDE the overlay. The overlay is in the upper portion of the screen; card slots are at the bottom so they're not obscured.
6. Logcat on Samsung is unreliable. Use `adb logcat -s ClashCompanion:D` for clean capture.
7. The app uses Jetpack Compose for MainActivity UI but the OverlayManager is still XML WindowManager (not Compose).
8. SharedPreferences persist across reinstalls — deck data survives APK updates.
9. Gemini 3 Flash model ID: `gemini-3-flash-preview`. Must set `thinkingLevel: minimal` in generationConfig.
10. Match-exit detection now resets after 10 consecutive non-match frames (2s). Single-frame dips during card animations are safe.
11. YOLOv8 TorchScript export includes softmax — model output is ALREADY probabilities. DO NOT apply softmax again.
12. Tensor.fromBlob() stores a REFERENCE to the float array. DO NOT reuse a shared FloatArray for the tensor input — each classifyRaw() call needs its own array. The reusable IntArray (pixelBuffer) for getPixels() is safe.
13. Python 3.14 on this machine. TensorFlow does NOT support it (requires <3.13). Use PyTorch Mobile Lite (.ptl).
14. sherpa-onnx bundles `libonnxruntime.so` in jniLibs/arm64-v8a — adding onnxruntime-android AAR causes linker conflict.
15. Temporal smoothing decay: cards drop from hand state after 5 consecutive missed scans (~1 second). This prevents stale cards but means dimmed cards (low elixir) temporarily disappear. Queue retry handles this.
16. Queue periodic timer runs every 2 seconds via handler.postDelayed in OverlayManager. Ensures retries happen even when hand state is stable.
17. Queue entries only removed after card has been absent for >5 seconds post-attempt (QUEUE_PLAYED_GRACE_MS). Prevents premature removal of dimmed cards.
18. The "then" split in handleTranscript uses " then " (with spaces) to avoid matching "then" at start of word. Standalone "then X" is handled by CommandParser's "then" prefix in step 5b.
19. Zone extraction uses longest-match-first with word-boundary regex. "his left tower" (14 chars) beats "left tower" (10 chars) beats "left" (4 chars).
20. Closing the overlay kills functionality: HandDetector.stopScanning(), CommandRouter.clearQueue(), periodic timer stopped. Keep overlay open (minimized is fine).

## Priority for Next Agent

**IMMEDIATE (do first):**
1. Commit the 2 uncommitted changes (queue grace period + periodic timer) and push
2. M8: Smart Path / Gemini Flash (1-1.5h) — GeminiClient.chat() is BUILT AND TESTED. Wire it into CommandRouter SMART tier:
   - Assemble context: deck JSON, Opus playbook (OpusCoach.cachedPlaybook), current hand (HandDetector.currentHand), command text
   - Call GeminiClient.chat() with system prompt + context on Dispatchers.IO
   - Parse JSON response: {"card": "...", "zone": "...", "reasoning": "..."}
   - Validate card is in hand → execute via executeFastPath()
   - If card NOT in hand → queue it
   - Display reasoning on overlay
   - Handle timeout/error gracefully

**THEN:**
3. Demo video recording — THE submission deliverable
4. README polish for judges reviewing GitHub
5. Optional: overlay HUD polish (replace debug buttons with clean status display)

**Time remaining: ~3-4 hours until 3 PM Sunday submission**

## Roboflow Dataset & Model Location
- Downloaded dataset: `roboflow_model/` (YOLO detection format, 347 images, 103 classes)
- Classification dataset: `cls_dataset/` (converted, 244 train / 103 val)
- Trained model: `runs/classify/runs/classify/card_train/weights/best.pt`
- Exported PTL: `app/src/main/assets/card_classifier.ptl` (6.1MB)
- Class mapping: `app/src/main/assets/card_classes.json` (103 entries)
- Roboflow project: `clashroyaleenemydetector/clash-royale-card-detection-en-0ltp2/2`
- Base model: `yolov8n-cls.pt` (ImageNet pretrained, downloaded automatically by ultralytics)

## STT Accuracy Evolution (from log analysis)
- Match 1: 60% (12/20) — "pekka" heard as "pecarite"/"mega knight", "arrows" as "ours", many zone misses
- Match 2: 79% (22/28) — compound aliases fixed pecarite/peklift, "ours" alias, fuzzy findDeckVariant
- Match 3: 89% (16/18) — "rye tower" alias, "fenderite" alias, tower zones remapped to enemy
- Match 4: 85% (28/33) — "defened" alias, bare "king" zone, "musketeeroit" compound
- Match 5: 86% (18/21) — "topright" zone, "mini peckerite" compound, "fund/friend right" aliases
- Primary remaining STT weakness: "mini pekka" + "defend" in same utterance → STT produces garbage like "mini peckered the fund right". Individual words work; compound phrases with unusual words fail.
