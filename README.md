# Clash Companion

Android overlay app for voice-controlled Clash Royale gameplay. It listens on-device, parses short commands, and injects gestures into the game via an Android `AccessibilityService`.

Built for the Supercell Global AI Game Hack (Feb 6–8, 2026): https://ailab.supercell.com/

## Status (what works today)
- M0: Gesture injection (`safeTap` / `safeDrag` / `playCard`) via `AccessibilityService`
- M1: Screen capture service (MediaProjection) + screenshot save
- M2: On-device speech-to-text (sherpa-onnx Zipformer transducer + Silero VAD) with hotwords
- M3: Command parser + Fast Path router (card + zone -> `playCard`)

Note: Card-to-slot mapping is currently hardcoded (some plays will hit the wrong slot until hand detection is implemented).

## Quick start

### 1) Download STT runtime + model assets
macOS/Linux:
```bash
./scripts/bootstrap_sherpa_deps.sh
```

Windows (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_sherpa_deps.ps1
```

This downloads large files into gitignored paths expected by `SpeechService`:
- `app/src/main/assets/sherpa-onnx-zipformer-en-2023-04-01/`
- `app/src/main/assets/silero_vad.onnx`
- `app/src/main/jniLibs/arm64-v8a/`

### 2) Build
```bash
./gradlew assembleDebug
```

### 3) Install (device)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## On-device setup
1. On Android 13+, you may need to allow restricted settings for sideloaded accessibility services:
   - Settings -> Apps -> Clash Companion -> (menu) -> Allow restricted settings
2. Open the app and use the setup buttons:
   - Grant overlay permission
   - Enable Accessibility service
   - Grant microphone permission
   - Start screen capture (optional for M3 Fast Path; required for later vision milestones)
   - Start Speech service (wait for "[Models loaded ...]")
   - Start overlay
3. In the overlay, tap "Start Listening", then switch to Clash Royale.

## Fast Path command examples
- "knight left"
- "fireball center"
- "archers right bridge"
- "giant back"

## API keys (optional)
Claude/Roboflow keys are read from `local.properties` into `BuildConfig`:
- `ANTHROPIC_API_KEY`
- `ROBOFLOW_API_KEY`

They are not required for the current on-device-only milestones. See `local.properties.example`.

## Disclaimer
Hackathon prototype. Respect Supercell's ToS and use at your own risk.

