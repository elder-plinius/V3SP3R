# V3SP3R iOS Port — Implementation Plan

## Overview
Full SwiftUI port of V3SP3R (Vesper), an AI-powered Flipper Zero controller.
Maps Android Jetpack Compose + ViewModel architecture to SwiftUI + @Observable pattern.

## Module Breakdown & Dependencies

### 1. Data Layer (no dependencies)
- **Models**: Swift equivalents of all Kotlin domain models (Command, RiskLevel, ChatMessage, etc.)
- **SecureStorage**: Keychain wrapper for API key storage (replaces EncryptedSharedPreferences)
- **SettingsStore**: UserDefaults for non-sensitive settings (model selection, auto-approve tiers)
- **ChatStore**: SwiftData for chat history persistence
- **AuditStore**: SwiftData for audit log persistence

### 2. BLE Layer (depends on: Data)
- **FlipperBLEManager**: CoreBluetooth CBCentralManager/CBPeripheral wrapper
  - Scan with service UUID filters matching Android UUIDs
  - Connect/disconnect lifecycle
  - MTU negotiation
  - Characteristic discovery and notification subscription
- **FlipperProtocol**: Protobuf message framing
  - Length-prefixed frames: [4-byte LE length][protobuf data]
  - CLI fallback for firmware without RPC support
  - Request/response correlation via command IDs
- **FlipperFileSystem**: High-level file operations (list, read, write, delete, etc.)
  - Path validation and security checks
  - CLI fallback pattern matching Android implementation

### 3. Domain Layer (depends on: Data, BLE)
- **RiskAssessor**: Risk classification (LOW/MEDIUM/HIGH/BLOCKED)
  - Exact risk mapping from Android RiskAssessor.kt
  - Protected path detection
  - CLI command risk analysis
- **CommandExecutor**: Central command dispatch
  - Risk gate → approval flow → execution → audit logging
  - All 30 CommandActions implemented
  - Pending approval state management
- **AuditService**: Action logging to SwiftData
- **InputValidator**: LLM output sanitization before execution
- **DiffService**: Compute file diffs for write previews

### 4. AI Layer (depends on: Domain, Data)
- **OpenRouterClient**: URLSession-based HTTP client
  - Tool-calling chat completion API
  - Rate limiting (30 req/min)
  - Retry with exponential backoff
  - Response validation
- **VesperAgent**: Conversation orchestrator
  - Message loop with tool dispatch
  - Session management
  - Chat persistence
- **VesperPrompts**: System prompts (ported from Android)
- **PayloadEngine**: AI payload generation + validation

### 5. Voice Layer (depends on: Data)
- **SpeechRecognizer**: SFSpeechRecognizer + AVAudioEngine wrapper
- **TTSService**: AVSpeechSynthesizer wrapper

### 6. Glasses Layer (depends on: Data)
- **GlassesBridgeClient**: URLSessionWebSocketTask to mentra-bridge

### 7. UI Layer (depends on: all above)
- Chat, Device, OpsCenter, AlchemyLab, PayloadLab, FapHub, ResourceBrowser, AuditLog, Settings
- Components: DiffViewer, ApprovalDialog, MessageBubble, InputBar

### 8. Widget (depends on: Data, BLE)
- WidgetKit extension for quick status

## BLE Protocol Implementation

### GATT UUIDs (from FlipperBleService.kt):
```
Service:     00003082-0000-1000-8000-00805f9b34fb (white)
             00003081-0000-1000-8000-00805f9b34fb (black)
             00003083-0000-1000-8000-00805f9b34fb (transparent)
Serial:      8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000
Serial TX:   19ed82ae-ed21-4c9d-4145-228e62fe0000
Serial RX:   19ed82ae-ed21-4c9d-4145-228e61fe0000
```

### Frame Format:
- 4-byte little-endian length prefix + protobuf payload
- CLI fallback: raw text commands when RPC unavailable

### Protobuf Approach:
Since protoc/swift-protobuf plugin may not be available, we'll use a lightweight
manual protobuf encoding approach for the subset of messages we need:
- Storage operations (list, read, write, delete, mkdir)
- System info queries
- App management
This avoids the protoc dependency while maintaining protocol compatibility.

## SPM Dependencies
```swift
// Package.swift
dependencies: [
    // SwiftProtobuf for protobuf encoding (used if available, otherwise manual encoding)
    .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.25.0"),
]
```

## Implementation Order
1. Data layer (models, storage) — foundation
2. BLE layer — device communication
3. Domain layer — business logic
4. AI layer — LLM integration
5. Voice + Glasses — peripheral features
6. UI layer — all screens
7. App entry point — navigation, DI
8. Tests — unit tests for critical paths
9. Documentation — PR prep

## Risk Classification (exact mapping from Android)
| Action | Risk | Gate |
|--------|------|------|
| list_directory, read_file, get_device_info, get_storage_info, search_faphub, browse_repo, github_search, search_resources, list_vault, request_photo, led_control, vibro_control | LOW | Auto-execute |
| write_file (in scope), create_directory, copy, push_artifact, forge_payload, download_resource, run_runbook, launch_app, subghz_transmit, ir_transmit, nfc_emulate, rfid_emulate, ibutton_emulate, ble_spam | MEDIUM | Diff/preview, single confirm |
| write_file (out of scope), delete, move, rename, badusb_execute, install_faphub_app | HIGH | Warning + double confirm |
| Protected system/firmware paths | BLOCKED | Settings unlock required |

## All 30 CommandActions
list_directory, read_file, write_file, create_directory, delete, move, rename, copy,
get_device_info, get_storage_info, execute_cli, push_artifact, forge_payload,
subghz_transmit, ir_transmit, nfc_emulate, rfid_emulate, ibutton_emulate,
badusb_execute, ble_spam, launch_app, led_control, vibro_control, search_faphub,
install_faphub_app, browse_repo, download_resource, github_search, search_resources,
list_vault, run_runbook, request_photo
