# Changelog

All notable changes to Vesper are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [1.1.1] — 2026-10-23

### Fixed

- **Launch crash after the 1.1.0 redesign.** The destination catalogue was
  built in the sealed class initializer. Touching `VesperDestination.Chat.route`
  first initialized `Chat`, whose construction re-entered the superclass
  initializer while `Chat.INSTANCE` was still null, so the catalogue stored a
  null entry and the router crashed on the first lookup. The catalogue is now
  initialized lazily, after the class hierarchy exists.
  Verified on an Android 37 emulator: cold launch, every tab, every More
  destination, rotation, and the `vesper://open/settings` deep link.

## [1.1.0] — 2026-10-23

A full quality pass: the app shell, the design system, connection reliability,
permission handling and the security posture were rebuilt. No dependency
versions were changed; see [docs/UPGRADING.md](docs/UPGRADING.md) for that
roadmap.

### Added

- **Onboarding.** First-run flow explaining Bluetooth and the API key before
  requesting anything, with a skip path and a returning-user bypass.
- **Complete navigation.** A destination model with five primary tabs (Chat,
  Device, Signals, Forge, More) plus a More screen that reaches every tool:
  App Library, Files, Audit Log, Spectral Oracle, Payload Lab, Ops Center,
  Settings, Field Manual.
- **Design system.** Semantic color tokens with verified contrast, a seven-step
  type scale, one spacing scale, one radius scale, motion tokens, and shared
  components (scaffold, cards, rows, empty/error/loading states, buttons,
  badges, status pills, hold-to-confirm control).
- **Light theme.** A fully specified "Bone" palette, selectable in Settings.
- **Global recovery UI.** A Bluetooth-permission banner with a grant action
  instead of a silently dead app.
- **Stop control** in chat for a running agent turn.
- **Diagnostics sheet** on Device, moving transport telemetry off the main screen.
- **App shortcuts** (long-press the launcher icon) and a `vesper://open/{route}`
  deep link.
- **CI.** GitHub Actions workflow building, testing and linting on every push.
- **Documentation.** Rewritten README, upgrade guide, and this changelog.
- **Audited direct saves.** Forge deployments and Payload Lab saves are written
  by explicit user action, so they still record a MEDIUM-risk audit entry with
  the path, kind and byte count.

### Changed

- Edge-to-edge is handled once in the app shell: status bar, gesture bar and IME
  insets are consumed correctly on Android 15/16.
- Every screen uses one scaffold and one top-bar style; nested Scaffolds and
  double insets are gone.
- Chat renders structured tool-call cards (action, target, risk, expandable
  payload) rather than the literal string "execute_command".
- Device, Files, Audit and Ops screens now have real empty, loading and error
  states, stable list keys and 48dp touch targets.
- File previews are size-capped and scrollable instead of laying out hundreds of
  kilobytes in a single Text.
- The foreground BLE service is started only when needed, updates its
  notification with connection state, and stops when idle.
- Scan results are conflated and published off the scan callback; BLE frame
  parsing is serialized.
- Cleartext HTTP is disabled app-wide (localhost and the emulator host are
  exempt). Smart-glasses bridges must use `wss://`.
- The app theme launches dark, removing the white flash at cold start.
- Release builds warn when no signing config is present instead of silently
  producing an unsigned artifact.
- Repository hygiene: `app/build` is no longer tracked (3,319 files, ~105 MB);
  `gradlew.bat` now exists and the wrapper is regenerated for Gradle 9.6.0.

### Fixed

- **Keyboard covering the chat input** on Android 15+.
- **The web file server was unauthenticated** and is now token-gated with
  socket timeouts, connection and size caps, and traversal protection.
- **Denying notifications or location disabled BLE entirely.** Only the two
  Bluetooth permissions gate the feature now.
- **Potential SecurityException** when a connected-device foreground service
  started without Bluetooth permission.
- **Protected-path bypass:** `/int` without a trailing slash, mixed-case
  extensions, and traversal segments no longer reach BLOCKED areas.
- **Auto-approve was a blank check** for every MEDIUM action. It now applies
  only to file writes inside the project folder.
- **API key stored in plaintext** and included in cloud backups. It is now
  encrypted with an Android Keystore AES-GCM key and the settings file is
  excluded from backup and device transfer.
- **Prompt-injection boundary:** tool output from the device and the network is
  now delimited and labelled as untrusted data for the model.
- **Web file server** required no authentication and could hold sockets open
  forever. It now requires a per-session token, has socket timeouts, a
  connection cap and a request-size cap.
- **Retry/error reporting** in the OpenRouter client: bounded attempts, capped
  Retry-After, and actionable 401/402/429 messages instead of "null".
- **Approval dialog accessibility:** it is a scrollable sheet, operable by
  TalkBack, with a hold-to-confirm control and a visible countdown; dismissing
  it no longer silently rejects the command.
- **Chat list performance:** removed the O(n²) `indexOf` per row and image
  base64 decoding inside composition.
- **Rotation state loss** for chat sheets and menus.

### Removed

- Dead build output from version control.
- Unused dependency `accompanist-drawablepainter` and `coil-video`.
- Inert `kapt.incremental.apt` configuration.
- The orphaned Device Tracker screen and its cyan palette.

### Known limitations

- Unit tests are declared but were not executed in this environment (no network
  to resolve JUnit/Mockito). CI runs them; some existing tests still need to be
  updated for the current `CommandExecutor` constructor.
- `compileSdk`/`targetSdk` remain 35 because Android SDK Platform 36 was not
  installable offline. The migration steps are in `docs/UPGRADING.md`.
- Visual verification on a physical Android device was not possible in this
  environment; the APK builds and the layouts follow the documented inset and
  accessibility rules.
- Forge/Payload saves are user-initiated and audited, but they do not go
  through the agent approval gate. The protected-path lock is enforced. Routing
  them through `CommandExecutor` is the next safety step.
- `WebFileServer` is hardened but has no UI entry point yet. Instantiate it
  from Files or Settings and surface `accessUrl` before advertising the
  feature.
