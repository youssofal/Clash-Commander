Check the state of the connected Android device:

1. Run `adb devices` to confirm connection
2. Use mobile-mcp `mobile_take_screenshot` to capture the screen
3. Use android-mcp `State-Tool` to get active apps and UI elements
4. Report what app is currently visible on screen
5. Report device battery level via android-mcp `Shell-Tool`: `dumpsys battery | grep level`
