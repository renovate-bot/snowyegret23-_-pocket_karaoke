# Pocket Karaoke

Android WebView browser with a karaoke-style key control.

## Features

- Browse web pages in-app with back, forward, reload, and URL entry.
- Raise or lower the current page media key in semitone steps from -12 to +12.
- Keep the current key setting while navigating between pages.
- Save bookmarks with the key value that was active when the bookmark was saved.
- Long-press a bookmark to delete it after confirmation.
- Restore the last URL and key setting when the app starts again.
- Block ad and tracking subresources with downloaded AdGuard/uBlock/List-KR filters and a saved in-app toggle.
- Long-press the AD toggle to force a filter refresh.

## Technical note

Android WebView does not expose a public API for processing the mixed audio output of an arbitrary page. This app injects a Web Audio script that attaches to page `<audio>` and `<video>` elements and applies a real-time pitch shifter. Sites that use DRM, cross-origin protected media, or inaccessible iframes may block this path. The ad blocker loads network-filter rules from AdGuard, uBlock Origin, uBlock Quick fixes, and List-KR filter lists, but it does not implement cosmetic filters, scriptlet injection, APK patching, or media ad skipping.

## Releases

Pushes to `main` build a debug APK and publish a GitHub Release. If no `vX.Y.Z` tag exists, the first release is `v1.0.0`. Later releases increment with a single-digit patch carry: `1.0.0`, `1.0.1`, ..., `1.0.9`, `1.1.0`.

## Build

Open this folder in Android Studio, or build from PowerShell after Android SDK and the Gradle wrapper are available:

```powershell
.\gradlew.bat assembleDebug
```
