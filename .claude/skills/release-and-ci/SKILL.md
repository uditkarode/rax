---
name: release-and-ci
description: How Rax is built, signed, versioned and published. Covers the release signing key and where it lives, version codes for preview builds, the R8 and resource settings and why each is there, native library rules (ABIs, 16 KB pages), the Go library in the build, the preview workflow, and Google Play requirements. Use when touching build files, R8 rules, signing, versions or the GitHub workflow.
---

# Release builds and CI

## Application IDs

The release build is `de.uditkaro.rax`, and the debug build adds `.debug`, so both can be
installed at once with separate data.

## Signing

- The release key is `release.jks`, with its passwords in `keystore.properties`, both at the
  repository root and both ignored by git. The same key is in the GitHub repository secrets
  `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
  `RELEASE_KEY_PASSWORD`.
- Android installs an update only if it is signed with the same key. If the key is lost, every user
  must uninstall Rax to install a newer build, so keep a backup of it outside the repository.
- Without `keystore.properties`, a local release build signs with the debug key, so it still
  installs for testing. CI always writes the file from the secrets, and a missing secret fails the
  build. A preview is never signed with the debug key.

## Versions

- Gradle properties set the version: `-Prax.versionCode=<n> -Prax.versionName=<name>`. Without
  them the build is versionCode 1, version 1.0.0.
- CI uses the workflow run number as the versionCode and `1.0.0-preview.<n>` as the name, so each
  preview installs over the one before. A local build (versionCode 1) does not install over a
  preview, because Android refuses downgrades.

## R8 and resources

The release build uses R8 in full mode with `proguard-android-optimize.txt`, and in addition:

- `-repackageclasses` moves classes into one package with short names, for a smaller dex.
- Kotlin's parameter, platform-value and field null checks (`Intrinsics.checkNotNullParameter`
  and similar) are removed with `-assumenosideeffects`. `!!` and `checkNotNull()` keep their
  checks, because code relies on them throwing.
- `android.r8.optimizedResourceShrinking` and `isShrinkResources` remove unused resources.
- `localeFilters += "en"` drops the translations inside libraries, because Rax itself is in
  English only.
- `dependenciesInfo.includeInApk = false` leaves out the dependency list that only Google Play
  reads.

Keep rules that must stay:

- The gomobile Java glue (`go.**` and `de.uditkaro.rax.tailnet.libtailnet.**`, in
  `tailnet/consumer-rules.pro`) is called from Go through JNI by name.
- `ghostty/consumer-rules.pro` keeps `GhosttyVt` and `GhosttyVtCallbacks` whole, because the JNI
  library registers and calls their methods by name.
- kotlinx.serialization brings its rules in its own jar, so no rules for `@Serializable` classes
  are needed.

After any rule change, test a release build on a device: R8 problems show up only at run time.

## Native libraries

- Rax ships arm64-v8a and x86_64 only (`abiFilters`). x86_64 is for emulators.
- Google Play requires native libraries built for 16 KB memory pages for apps that target Android
  15 or later. Check after changing a native dependency: `llvm-readelf -lW` on each `.so` must show
  LOAD alignment `0x4000`, and `zipalign -c -P 16 -v 4 app.apk` must pass.
- The Go library is built during the Gradle build by `tailnet/build-go.sh`, with `-trimpath` and
  `-s -w`. See the `tailnet` skill.
- `libghostty_jni.so` is built with Zig during the Gradle build by `ghostty/build-native.sh`, with
  a 16 KB max page size. See `ghostty/README.md`.

## Preview workflow

`.github/workflows/preview.yml` runs on every push to `main`:

1. It installs the SDK packages with `sdkmanager`. The platform package is
   `platforms;android-37.0`, with `.0`. It reads the NDK version from `gradle/libs.versions.toml`.
2. It sets up JDK 21, Go from `tailnet/go/go.mod` and Zig at `minimum_zig_version` in
   `ghostty/native/build.zig.zon`, and restores Zig's cache for the Ghostty build. It writes the
   signing key from the secrets and runs the unit tests and `assembleRelease`.
3. It publishes the APK as a prerelease tagged `preview-<run number>`, with the number padded to
   five digits (`preview-00012`), and the commit message as the notes. GitHub lists releases by
   the day of their commit, newest first, and within one day by tag name as text, so without the
   padding `preview-11` would sort below `preview-6`. Its API uses the same order, and the updater
   reads only the first page.

## Updates

- A release build checks GitHub's releases when it comes to the foreground, at most every 30
  minutes (`update/Updater.kt`). The newest `preview-<n>` tag with an APK is an update when `n` is
  greater than the build's version code. Debug builds do not check, because their application ID
  differs and a preview would not replace them.
- Installing streams the APK into a `PackageInstaller` session. The first time, Android asks the
  user to let Rax install apps, and to confirm the update. Play Protect can also ask to scan the
  app. After Rax installed itself once, `USER_ACTION_NOT_REQUIRED` lets later updates install
  without asking (Android 12 and later).
- Android installs an update only with the same signing key, so a local build, which signs with
  the debug key, can test everything up to the install, which then fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Google Play

- Play does not allow an app to update itself, and it restricts `REQUEST_INSTALL_PACKAGES`. A
  build for Play must leave out the updater and that permission.
- Background alerts run a foreground service of type `specialUse`, with a subtype property in the
  manifest. Play asks for a written reason for this type when the app is submitted.
- `ACCESS_LOCAL_NETWORK` (Android 17) and `POST_NOTIFICATIONS` (Android 13) are runtime
  permissions. Rax asks for them when the user does something that needs them: saving a LAN host,
  or turning on background alerts. Before Android 13, notifications need no permission, and Rax
  opens the notification settings when the user turned them off.
