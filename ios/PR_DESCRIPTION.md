# iOS SwiftUI Port of V3SP3R

## What was built

Complete iOS port of V3SP3R (Vesper), an AI-powered Flipper Zero controller, built entirely in SwiftUI targeting iOS 17+.

### Module breakdown:
- **Data Layer** (5 files) — Models, SecureStorage (Keychain), SettingsStore (UserDefaults), ChatStore (SwiftData), AuditStore (SwiftData)
- **BLE Layer** (3 files) — FlipperBLEManager (CoreBluetooth), FlipperProtocol (manual protobuf encoding + CLI fallback), FlipperFileSystem (high-level file ops)
- **Domain Layer** (5 files) — RiskAssessor (exact risk mapping from Android), CommandExecutor (all 32 actions), AuditService, InputValidator, DiffService
- **AI Layer** (4 files) — OpenRouterClient (URLSession), VesperAgent (conversation loop + tool dispatch), VesperPrompts (full system prompt), PayloadEngine
- **Voice Layer** (2 files) — SpeechRecognizer (SFSpeechRecognizer + AVAudioEngine), TTSService (AVSpeechSynthesizer)
- **Glasses Layer** (1 file) — GlassesBridgeClient (URLSessionWebSocketTask)
- **UI Layer** (16 files) — Chat, Device, FileBrowser, OpsCenter, AlchemyLab, PayloadLab, FapHub, ResourceBrowser, AuditLog, Settings, Components (DiffViewer, ApprovalDialog)
- **App** (1 file) — VesperApp entry point with ServiceLocator DI + tab navigation
- **Widget** (1 file) — WidgetKit extension for connection status
- **Tests** (4 files) — RiskAssessor, InputValidator, FlipperProtocol, model serialization

**Total: 37 Swift source files + Package.swift**

## Architecture decisions

| Decision | Rationale |
|----------|-----------|
| SwiftUI + @Observable | Modern iOS approach matching Compose + ViewModel pattern from Android |
| Manual DI (ServiceLocator) | Avoids Swinject/Needle complexity; matches Hilt simplicity for this project size |
| Manual protobuf encoding | protoc/swift-protobuf plugin not available on build server; manual encoding is wire-compatible |
| Keychain for API key | Security requirement — never stored in UserDefaults or disk |
| SwiftData for persistence | Native iOS 17+ persistence; maps to Room on Android |
| InMemoryAuditStore default | SwiftData audit store available but InMemory used for simplicity; easily swappable |
| URLSession (not Alamofire) | Minimal dependencies; URLSession handles all HTTP needs |
| URLSessionWebSocketTask | Native WebSocket support; no third-party dependency needed |

## How to build

```bash
cd ios/Vesper
swift build
```

For Xcode:
1. Open `ios/Vesper/Package.swift` in Xcode
2. Select iOS Simulator target
3. Build (Cmd+B)

## How to run tests

```bash
cd ios/Vesper
swift test
```

## All 32 CommandActions implemented

list_directory, read_file, write_file, create_directory, delete, move, rename, copy, get_device_info, get_storage_info, execute_cli, push_artifact, forge_payload, subghz_transmit, ir_transmit, nfc_emulate, rfid_emulate, ibutton_emulate, badusb_execute, ble_spam, launch_app, led_control, vibro_control, search_faphub, install_faphub_app, browse_repo, download_resource, github_search, search_resources, list_vault, run_runbook, request_photo

## Risk classification

| Level | Gate | Actions |
|-------|------|---------|
| LOW | Auto-execute | list_directory, read_file, get_device_info, get_storage_info, search_faphub, browse_repo, github_search, search_resources, list_vault, request_photo, led_control, vibro_control |
| MEDIUM | Single confirm | write_file (in scope), create_directory, copy, push_artifact, forge_payload, download_resource, run_runbook, launch_app, subghz_transmit, ir_transmit, nfc_emulate, rfid_emulate, ibutton_emulate, ble_spam |
| HIGH | Double confirm | delete, move, rename, badusb_execute, install_faphub_app, write_file (out of scope) |
| BLOCKED | Settings unlock | Protected system/firmware paths |

## Known limitations

1. **No protoc-generated code** — Uses manual protobuf encoding. Wire-compatible but less type-safe than generated code.
2. **Hardware-dependent features** — BLE scanning, speech recognition, camera input require a physical iOS device (not simulator).
3. **No USB transport** — Android version supports USB serial; iOS CoreBluetooth only supports BLE.
4. **Widget** — Requires a separate WidgetKit extension target in an Xcode project for full functionality.
5. **SwiftData** — Requires iOS 17+.

## Screenshot placeholders

See `ios/SCREENSHOTS.md` for the full list of screens.
