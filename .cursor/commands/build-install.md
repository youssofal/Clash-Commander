Build and install the app on the connected Samsung A35:

1. Run `./gradlew assembleDebug` in the terminal
2. If build fails, fix the errors and rebuild
3. Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. If install fails, run `adb uninstall com.yoyostudios.clashcompanion` first, then reinstall
5. Report success or failure
