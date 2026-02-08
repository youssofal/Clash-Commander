# Download Pre-Built APK

The APK (457MB) is too large for GitHub file uploads. Download from the release page or build from source.

## Option A: Build from source (recommended)

```bash
# 1. Clone
git clone https://github.com/youssofal/Clash-Commander.git
cd Clash-Commander

# 2. Download STT models
./scripts/bootstrap_sherpa_deps.sh   # macOS/Linux
# or
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap_sherpa_deps.ps1  # Windows

# 3. Build
./gradlew assembleDebug

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Option B: Contact for pre-built APK

The APK includes all on-device models (~457MB). Contact the developer for a direct download link.
