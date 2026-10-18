# The prompt

Paste this into Claude Code (or any coding agent with shell access) on a computer that's on the
same Wi-Fi as your TV. It works on Android TV and Google TV sets (TCL, Xiaomi/Redmi, Hisense, Sony,
OnePlus, Realme, Nvidia Shield, Chromecast with Google TV…). It won't work on Samsung (Tizen),
LG (webOS) or Fire TV.

**Before you paste it**, on the TV: Settings → System (or Device Preferences) → About → press OK on
**Build** / **Android TV OS build** 7 times. Go back → Developer options → turn on **USB debugging**
(and Network/Wireless debugging if your TV shows it). Keep the TV on.

If you cloned this repo, start the agent inside it and say so. It can then reuse `tools/` and
`launcher/` instead of writing them from scratch.

---

```text
My Android TV is slow and full of ads. I want you to debloat it and replace its home screen with a
clean, fast, Apple TV style launcher. Work on the real TV over Wi-Fi, step by step, and check every
change with a screenshot pulled from the TV. Ask me before anything that depends on my preferences.
Everything must be reversible: disable packages, never uninstall system ones.

1. Find and connect
- Find the TV on my network yourself: scan the subnet for port 5555 (ADB) and 8008 (Cast), check
  `dns-sd -B _androidtvremote2._tcp` and the ARP table. Only ask me for the IP if that fails.
- Connect over ADB on port 5555 using the Python `adb-shell` package in a venv, not the adb binary
  (on macOS the adb binary is often silently blocked by Local Network privacy). Tell me when the
  "Allow USB debugging?" prompt will appear so I can tick "Always allow" and accept it.
- Read the model, Android version, screen size and density.

2. Back up first
- Save the full package list, disabled packages, user-installed packages, getprop, all three
  `settings list` namespaces, the current HOME activity and default input method to a backup folder.
- Record memory use (`dumpsys meminfo`) so we can compare before/after.

3. Debloat
- Sort every package into: manufacturer telemetry/analytics, manufacturer ad/content services
  (content tabs, promo popups, "channel" apps, partner launcher customisers), background clutter
  (cleaners, hotel mode, duplicate cast/mirroring), preinstalled apps, streaming apps, and core
  system (never touch: settings, TV input/tuner, system server, keyboard, remote pairing, mic).
- If a package name is unclear, inspect its activities/services before deciding.
- Ask me with a checklist which of these I use: voice assistant, Chromecast, screen mirroring,
  USB media player, and each preinstalled streaming app. Disable only what I don't tick.
- Write the list to a debloat file with a comment per line, then disable with
  `pm disable-user --user 0`, ONE pm command per shell call (chained pm calls can silently stop).
- Write a restore script that re-enables everything, including the stock launcher.

4. Launcher
- Build a tiny native launcher yourself (Java, plain Android framework views, no AndroidX, no
  network, no background services). Build it with the bare SDK tools (aapt2 → javac --release 11
  with android.jar on the classpath → d8 → zipalign → apksigner) so there's no Gradle download.
  Install JDK 17 + Android command-line tools (platform 34, build-tools 34) via Homebrew if needed.
- Design: black background; top-left a greeting, a large thin clock and the date; top-right frosted
  pills for Inputs (dropdown of HDMI/AV from TvInputManager plus Live TV), Wi-Fi (shows the network
  name; grant ACCESS_FINE_LOCATION over ADB so Android 11+ reveals it) and Settings. At the bottom a
  dock in a lighter rounded band holding the apps I pick (ask me which and in what order); above it
  the focused app's name in large light type over an ambient glow coloured from its banner. Press
  Down and every other app appears in a grid that scrolls up. Focused tiles scale up with a spring
  and show their name.
- D-pad navigation must be deterministic: explicit nextFocus ids, one row per press, row ends don't
  wrap, Up from the dock goes to the pills, Back from the grid returns to the dock, Home while home
  jumps to the first dock app. Remember the last focused tile.
- Ask whether I want wallpapers. If yes, use freely licensed photos (e.g. Wikimedia Commons
  Featured Pictures via its API; Unsplash blocks scripted downloads with a bot check, don't fight
  it), crop to 1920×1080, bundle them in assets, crossfade every 10 minutes, darken with a scrim so
  text stays readable, and keep a credits file.
- Re-scan installed apps in onResume using PackageManager.getChangedPackages, not only a package
  broadcast receiver, or apps installed while another app is in front won't appear.

5. Make it the real home screen
- Install, set it with `cmd package set-home-activity`, then disable the stock launcher.
- On Google TV, also disable `com.google.android.tungsten.setupwraith`. Its RecoveryActivity has
  a higher HOME priority than any third-party launcher and hijacks the Home button once the stock
  launcher is gone. Verify with `cmd package resolve-activity -c android.intent.category.HOME`.
- Then force-stop the launcher and com.android.tv.settings and press Home, so the launcher lands
  in the default home task. Otherwise Home can bring it up BEHIND the current app.
- Test the physical Home button from inside an app: open YouTube, inject KEYCODE_HOME, screenshot,
  and confirm the launcher is actually visible (not just "resumed" in dumpsys).
- Check the on-screen keyboard still works (some firmwares point the default IME at an app that
  isn't installed).

6. Verify and hand over
- Navigate with injected key presses and look at screenshots after every change. Fix clipping,
  readability and focus issues you see.
- Compare memory before/after.
- Give me: the debloat list, a restore script, a build-and-install script, a script to type text
  into the TV from my computer, and a README explaining how to re-enable an app, change the dock
  and change the wallpapers.
```

---

Expect three or four questions along the way: which features you use, which apps go in the dock,
whether you want wallpapers. Everything is reversible with `tools/restore.sh`.
