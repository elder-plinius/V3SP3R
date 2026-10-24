# Upgrading Vesper's toolchain

This document is the plan for moving Vesper from its current (working, but
frozen) dependency set to the 2026 line. It is deliberately separate from the
code changes in this release: **no dependency version was changed** because
doing so without a verifiable build would risk shipping something that does not
compile.

## Current state (verified with `assembleDebug`)

| Component | Version |
|---|---|
| Gradle | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin + Compose compiler plugin | 2.2.10 |
| KSP | 2.3.6 |
| Compose BOM | 2024.06.00 (Compose 1.6.8, Material3 1.2.1) |
| Hilt | 2.59.2 |
| Room | 2.7.2 |
| Navigation Compose | 2.7.7 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| JDK | 17 |

The Kotlin 2.2.10 / Gradle 9.6 / AGP 9.4 combination sits outside JetBrains'
published support matrix and only works because `gradle.properties` sets
`android.builtInKotlin=false` and `android.newDsl=false`. Those escape hatches
are removed in AGP 10, so this migration is scheduled, not optional.

## Why the versions are frozen in this release

- The build machine used for this work has no outbound network. New artifacts
  cannot be downloaded, so version bumps cannot be compiled or tested.
- `compileSdk 36` requires the Android 36 platform to be installed, which is
  also a download.
- Shipping unverified version bumps is worse than shipping none.

## Target versions (checked against upstream metadata, October 2026)

| Component | Current | Target | Note |
|---|---|---|---|
| Gradle | 9.6.0 | 9.6.0 | Keep; regenerate the wrapper |
| AGP | 9.4.1 | 9.4.1 | Keep; 9.3.3 if you want the strict KGP matrix |
| Kotlin | 2.2.10 | 2.4.21 | Compose compiler plugin must match exactly |
| KSP | 2.3.6 | 2.3.12 | Decoupled from Kotlin since 2.3.0 |
| Compose BOM | 2024.06.00 | 2026.09.00 | Needs Kotlin 2.3+ and compileSdk 36 |
| compileSdk | 35 | 36 | Required by 2026 AndroidX AARs |
| targetSdk | 35 | 36 | Google Play requirement since 2026-08-31 |
| Hilt | 2.59.2 | 2.60.1 | |
| Room | 2.7.2 | 2.8.5 | |
| Navigation Compose | 2.7.7 | 2.10.2 | Also enables predictive-back support |
| Lifecycle | 2.8.4 | 2.11.0 | |
| Activity Compose | 1.9.1 | 1.13.0 | |
| core-ktx | 1.13.1 | 1.19.1 | |
| DataStore | 1.1.1 | 1.3.0 | |
| OkHttp | 4.12.0 | 5.5.1 | Small API deltas |
| kotlinx-serialization | 1.8.0 | 1.12.0 | |
| coroutines | 1.10.1 | 1.11.0 | |
| Coil | 2.5.0 | 3.6.3 | Package rename to coil3 |
| java-diff-utils | 4.12 | 4.18 | |
| protobuf-java | 3.25.5 | 3.25.x | Do NOT bump without regenerating the vendored gencode |

## Order of operations

1. **Clean the repo** — `app/build` is no longer tracked; the Gradle wrapper is
   regenerated so `gradlew.bat` exists. Verify `git status` is quiet.
2. **Make tests run** — the unit-test dependencies are now declared. Run
   `./gradlew testDebugUnitTest` on a machine with network access and fix the
   tests that never compiled (they construct `CommandExecutor` with an outdated
   signature).
3. **CI first** — `.github/workflows/android.yml` builds, tests, and lints on
   every push. Do not start version bumps before it is green.
4. **compileSdk/targetSdk 36 only** — one commit, no library changes. Fix any
   Android 16 behavior regressions (edge-to-edge is already handled).
5. **Kotlin + KSP + Compose BOM together** — they are coupled. Expect
   Material3 1.4 API drift in screens (deprecated overloads, renamed
   components). The current code deliberately uses only stable 1.2 APIs.
6. **AndroidX sweep** — one family per commit (Hilt, Room, Navigation,
   Lifecycle/Activity/core, DataStore).
7. **Coil 3, OkHttp 5, security-crypto 1.1.0** — separate commits with a smoke
   test each. security-crypto is deprecated upstream; plan to migrate to a
   Keystore-backed store (Vesper already has one for the API key).
8. **R8 and baseline profile** — the keep rules were pruned in this release.
   After the upgrade, build a release APK, install it, and test BLE, Room, and
   serialization. Then add a baseline profile measured on a device.
9. **AGP 10 readiness** — drop `org.jetbrains.kotlin.android` for AGP's
   built-in Kotlin, replace `kotlinOptions` with `kotlin { compilerOptions }`,
   remove both `android.builtInKotlin` and `android.newDsl`.

## Verification commands

```bash
./gradlew --no-daemon clean assembleDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleRelease   # R8: catches reflection breakage
```

Install the release APK on a device and exercise: BLE scan/connect/reconnect,
one LOW action, one MEDIUM write (diff + approval), one HIGH action
(hold-to-confirm), file browse, audit log, and rotation on each screen.
