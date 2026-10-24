# Cricket 07 for Android (bring your own copy)

An Android shell for [Dhaiwat's cricket07-browser](https://github.com/Dhaiwat10/cricket07-browser) port, with
on-screen touch controls. **The APK contains no game files and no game code.** You build the browser version
from your own copy of EA Sports Cricket 07, copy that build and your game folder to the phone, and the app
runs them. Personal sideloading only: don't publish it, and don't share your build or game files.

## What you need

- Your own installed copy of Cricket 07 (the folder with `Data`, `Audio` and the `.exe`).
- A Mac with Apple Silicon to make the browser build (that's what cricket07-browser's build needs).
- An Android 12+ phone with a recent Chrome / Android System WebView, ideally 8 GB RAM or more. The game
  reserves over 1 GB of memory and renders through WebGPU, so mid-range phones may be slow or fail.
- About 1 GB free on the phone for the game files, plus the build.
- Android Studio (or the Android SDK command-line tools + JDK 17) to build this app.

## 1. Make the browser build (on the Mac, once)

Follow "Using this repo" in cricket07-browser's README, steps 1 to 4. You need the folder
`build_web/www`: it holds `recomp.js` and `recomp.wasm` (plus any `.worker.js` next to them).

## 2. Copy two folders to the phone

Over USB (or any file transfer), put these anywhere in the phone's storage, for example `Download/`:

- `build_web/www` → e.g. `Download/c07-build`
- your Cricket 07 game folder → e.g. `Download/Cricket 07`

## 3. Build and install the app

```
./gradlew assembleRelease        # or open the folder in Android Studio and press Run
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key, so it installs directly.

## 4. First start

1. **Choose build folder**: pick `c07-build`. The app copies `recomp.js` / `recomp.wasm` into its storage.
2. **Choose game folder**: pick your Cricket 07 folder. The app only gets read access to it.
3. **Play**, then **Copy game files** on the launcher. It copies the folder into the game's private browser
   storage once (a few minutes). After that the game starts straight away and works offline.

If the launcher says there's no WebGPU or no usable graphics adapter, go back (press back twice) and use
**Open in Chrome instead**. Chrome keeps its own copy, so the first start there copies the files again; keep
the app's notification running while you play in Chrome, and stop it from the notification afterwards.

## Controls

| Touch | Key | In the game |
|---|---|---|
| Left stick (appears under your thumb) | Arrows | Aim shot, place ball, move in menus |
| ✕ | S | Shot, stock delivery, select |
| ○ | D | Advanced shot, take a run |
| □ | A | Cancel run |
| △ | W | Back |
| LOFT (hold with a shot) | Shift | Lofted shot |
| SPL | Alt | Special delivery (with A / D / LOFT) |
| ❚❚ or the phone's back button | Esc | Pause |

The ⋯ menu hides the touch controls (for a Bluetooth controller, which works as a gamepad), changes button
size, presses Enter, or leaves the game. Back twice quickly also leaves the game.

## How it works

- `LocalServer.kt`: a small HTTP server on `127.0.0.1:47107`, behind a random path token so other apps can't
  read it. It serves the launcher page, your build and your game files with the cross-origin isolation
  headers WebAssembly threads need. The port is fixed because the browser's private storage belongs to the
  origin, which includes the port.
- `MainActivity.kt`: setup screen (Storage Access Framework folder pickers) and a full-screen WebView.
- `assets/web/`: cricket07-browser's launcher, unchanged (MIT, see `LICENSE-cricket07-browser.txt`).
- `assets/android/shim.js`: swaps the desktop folder picker for an import from the app's server, hides
  online play (it needs a room server), and offers Chrome if the WebView can't run the game.
- `assets/android/import-worker.js`: streams each file into the browser's storage without holding it in memory.
- `assets/android/touch.js` / `touch.css`: the touch controls. They press the same keys a keyboard does, so the
  game's input code is untouched.

## Troubleshooting

- See the page's console: connect the phone over USB, open `chrome://inspect` on a computer, and inspect the
  WebView. `adb logcat -s Cricket07 Cricket07Server` shows the app's own log.
- "Couldn't start the game server": something else is using port 47107. Close other copies of the app.
- Black screen or crash during play: the phone ran out of memory or its GPU driver is blocklisted for WebGPU.
  Try Chrome, close other apps, or use a phone with more RAM.

Cricket 07 and EA Sports are trademarks of Electronic Arts. This is a personal fan project, not affiliated
with Electronic Arts or Dhaiwat.
