# CloneForge

Android app that makes installable copies of apps already on your phone (or from an APK file),
with their own name, package and tweaked manifest. Free: no billing, no premium gating.

## Build
Open this folder in Android Studio (Koala or newer, JDK 17) and run the `app` configuration.
CLI: `gradle wrapper` once, then `./gradlew assembleDebug`.

## How a clone is made
1. Read the app's APK (and split APKs, if any).
2. Rewrite the binary `AndroidManifest.xml` (own AXML engine, `engine/Axml.kt`, `ManifestPatcher.kt`):
   new package, label, removed permissions/widgets/boot receivers, backup + recents flags.
   Relative class names are pinned first, provider authorities and custom permissions are renamed.
3. Repack the APK (`ApkRewriter.kt`): old signature dropped, stored entries zip-aligned.
4. Sign with a key held in the Android Keystore (`CloneSigner.kt`, Google's apksig).
5. Install through `PackageInstaller` (you confirm the system prompt).

## Included
Clone any installed app or APK file, batch of split APKs, name, package, remove permissions,
disable backup, exclude from recents, disable auto-start, remove widgets.

## Not included yet (needs code injected into the clone's DEX)
Password protect, incognito mode, location/Android ID spoofing, hosts blocker, SOCKS proxy,
Wi-Fi only, mute on start, floating window mode, custom icon, language/font overrides, data export/import.

## Limits
Google sign-in, Play Services and in-app purchases fail in clones (certificate changes).
Apps that check their own signature, or look up resources by package name, may not run.
Android 14+ refuses to install apps with a very old targetSdk.
