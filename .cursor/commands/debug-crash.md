The app just crashed. Debug it:

1. Run `adb logcat -s ClashCompanion,AndroidRuntime | tail -100`
2. Find the crash stack trace
3. Identify the root cause
4. Fix the code
5. Rebuild and install with ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
