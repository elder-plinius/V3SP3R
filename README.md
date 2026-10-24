```
 ██╗   ██╗ ██████╗ ███████╗██████╗ ███████╗██████╗
 ██║   ██║╚════██╗██╔════╝██╔══██╗██╔════╝██╔══██╗
 ██║   ██║ █████╔╝███████╗██████╔╝█████╗  ██████╔╝
 ╚██╗ ██╔╝ ╚═══██╗╚════██║██╔═══╝ ██╔══╝  ██╔══██╗
  ╚████╔╝ ██████╔╝███████║██║     ███████╗██║  ██║
   ╚═══╝  ╚═════╝ ╚══════╝╚═╝     ╚══════╝╚═╝  ╚═╝
```

# V3SP3R — the AI command center for your Flipper Zero

Vesper turns a Flipper Zero into something you can talk to. Connect over
Bluetooth, describe what you want, and the app reads the device state, proposes
a command, classifies the risk, and shows you exactly what will change before
it runs.

No menus to memorize. No firmware mods. Your phone, your Flipper, one
conversation.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-purple.svg)](https://kotlinlang.org)

---

## What's new in 1.1

**A complete redesign, not a reskin.**

- **One navigation model.** Five primary tabs (Chat, Device, Signals, Forge,
  More) and every tool is reachable. The Spectral Oracle, Signal Arsenal,
  Payload Lab, Ops Center and Audit Log used to be unreachable screens; they
  are now first-class destinations in the More tab.
- **First-run onboarding.** Three screens that explain Bluetooth access and the
  API key before asking for them. A denied permission no longer disables the
  whole app with no explanation: an in-app banner offers to grant it again.
- **Edge-to-edge done properly.** Status bar, gesture bar and keyboard are all
  handled once, at the shell. On Android 15 and 16 the chat input now sits
  above the keyboard instead of behind it, and no screen has double insets.
- **A real design system.** Semantic color tokens, a seven-step type scale with
  no sizes below 12sp, one spacing scale, one radius scale, and motion tokens.
  Contrast is computed, not guessed: wine is a fill, rose is wine-colored text,
  gold pairs with ink. Light mode is fully specified instead of a broken
  fallback.
- **Accessibility.** The approval control is a real 56dp button with semantics
  and a visible countdown, operable by TalkBack and Switch Access. Icon buttons
  are 48dp. Lists have stable keys and proper empty, loading and error states.
- **Connection reliability.** The foreground service only runs while it is
  needed, the status notification tracks connection state, scanning stops
  reliably, BLE notifications are parsed serially instead of racing, and a
  watchdog reconnects with capped exponential backoff.
- **Security.** The OpenRouter key is encrypted with an Android Keystore
  AES-GCM key. Protected Flipper paths can no longer be bypassed with
  `/int` (no trailing slash) or a mixed-case extension. Auto-approve is
  scoped to in-project file writes instead of every MEDIUM action. The local
  file server requires a per-session token and times out stalled sockets.
- **Quality.** Dead build output is no longer tracked in git, the Gradle wrapper
  now works on Windows, unit-test dependencies are declared, and a GitHub
  Actions workflow builds, tests and lints every push.

---

## Screens

| Screen | What it does |
|---|---|
| **Chat** | Natural-language control. Tool calls render as cards with the action, target, risk level and expandable payload. Approvals show a diff and require a deliberate hold for HIGH risk. A Stop button cancels a running turn. |
| **Device** | Connection card with battery, firmware and storage, a real remote control with haptics, scan results sorted by signal, and a Diagnostics sheet with the raw transport telemetry. |
| **Signals** | Your SubGHz and IR captures, searchable and filterable, with a waveform preview and one-tap replay. |
| **Forge** | Describe a payload, get a validated blueprint, edit it, and deploy it to the Flipper. |
| **More** | App Library, Files, Audit Log, Spectral Oracle, Payload Lab, Ops Center, Settings and the Field Manual. |

---

## Features

### Talk to the device
- Voice or text input, with on-device speech recognition.
- Photo and video analysis: point the camera at a remote, a label or a screen.
- Tool results are rendered as structured cards instead of a wall of text.
- Streaming progress, cancellation, and retry that keeps the last message.

### Control the hardware
SubGHz, Infrared, NFC, RFID, iButton, BadUSB, GPIO, LED, vibro, and app
launching. Every operation is a command in a single, risk-classified pipe.

### Risk and permissions
Every action is classified before execution:

| Risk | Behavior |
|---|---|
| **LOW** | Runs immediately (reads, listings, info). |
| **MEDIUM** | Shows a diff for review before applying. |
| **HIGH** | Requires a deliberate hold-to-confirm. |
| **BLOCKED** | System and firmware paths stay locked until unlocked in Settings. |

Auto-approve is intentionally narrow: it can skip confirmation for file writes
inside your project folder, and nothing else.

### Audit everything
Every action, its arguments, its result and the approval method are logged and
exported. The log is grouped by day, filterable by risk, and searchable.

### Works with your setup
- Flipper Zero over BLE or USB serial.
- Optional Mentra smart-glasses bridge for hands-free use.
- OpenRouter models, from fast/cheap to deep reasoning.

---

## Recommended models

Vesper works with any model on [OpenRouter](https://openrouter.ai). Tool use is
the deciding factor.

| Model | Why use it | Speed |
|---|---|---|
| **`nousresearch/hermes-4`** | Strong tool use, purpose-built for agentic work. | Fast |
| **`anthropic/claude-sonnet-4`** | Best balance of reasoning, speed and cost. | Fast |
| **`anthropic/claude-opus-4.6`** | Deep reasoning for complex multi-step operations. | Medium |
| **`openai/gpt-4o`** | Solid general-purpose alternative. | Fast |

Start with Hermes 4 or Claude Sonnet 4. Switch to Opus when a task needs it.

---

## Quick start

### Requirements

| Item | Notes |
|---|---|
| **Flipper Zero** | [shop.flipperzero.one](https://shop.flipperzero.one) |
| **Android** | 8.0+ (API 26). Bluetooth required. |
| **OpenRouter account** | [openrouter.ai](https://openrouter.ai) — pay per use |

### 1. Prepare the Flipper
1. Charge it.
2. Update firmware with qFlipper.
3. Settings > Bluetooth > ON.

### 2. Get a key
Create a key at **openrouter.ai > Keys**, copy it, and add a few dollars of
credit.

### 3. Build

```bash
git clone https://github.com/elder-plinius/V3SP3R.git
cd V3SP3R
```

Open the project in Android Studio and press Run, or from a terminal:

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17+ and the Android SDK (platform 35). See
[docs/UPGRADING.md](docs/UPGRADING.md) for the toolchain roadmap.

### 4. First launch
The onboarding walks through three steps:

1. **Welcome** — what Vesper does.
2. **Access** — grant Bluetooth. Nothing else is requested up front.
3. **Brain** — add your OpenRouter key in Settings (or explore first).

Then open **Device**, scan, and tap your Flipper.

---

## Smart glasses (optional)

Vesper supports hands-free use through the Mentra bridge.

```bash
cd mentra-bridge
npm install && npm run build && npm start
```

In Settings, enable Smart Glasses and enter the bridge URL. Voice transcripts
flow into the chat, and the AI can request a photo from the glasses camera.

The bridge must be reachable over **wss://** (TLS). Cleartext HTTP is disabled
app-wide, with exceptions only for `localhost` and the Android emulator host, so
a plain `ws://192.168.x.x` URL will be refused. For a local-only bridge, put a
TLS terminator in front of it or use `adb reverse` and `ws://localhost`.

---

## Architecture

```
┌──────────────────────────────────────────┐
│  UI — Compose + Material 3               │
│  navigation/  AppShell, destinations     │
│  onboarding/  first run, permissions     │
│  components/  VesperScaffold, states,    │
│               controls, approval sheet   │
│  theme/       color, type, shape, motion │
├──────────────────────────────────────────┤
│  ViewModels — one UI state per screen    │
├──────────────────────────────────────────┤
│  Domain                                  │
│  VesperAgent  command loop + approval    │
│  CommandExecutor  risk gate + diff       │
│  RiskAssessor  protected paths, policy   │
│  AuditService  history                   │
├──────────────────────────────────────────┤
│  Data                                    │
│  FlipperBleService  BLE/USB transport    │
│  FlipperProtocol    RPC + CLI framing    │
│  OpenRouterClient   model calls          │
│  Room + DataStore + Keystore            │
└──────────────────────────────────────────┘
```

## Project structure

```
app/src/main/java/com/vesper/flipper/
├── MainActivity.kt            # single activity, edge-to-edge
├── VesperApplication.kt
├── ai/                        # agent loop, HTTP, prompts, JSON repair
├── ble/                       # BLE/USB transport + Flipper protocol
├── data/                      # settings, catalog, Room
├── domain/
│   ├── executor/              # command executor, risk, forge
│   ├── model/                 # shared models
│   ├── protocol/              # SubGHz, Pwnagotchi
│   └── service/               # audit, diff, permissions
├── glasses/                   # Mentra bridge client
├── security/                  # keystore, redaction, validation
├── ui/
│   ├── navigation/            # AppShell, destination model
│   ├── onboarding/            # first-run flow
│   ├── components/            # design system components
│   ├── theme/                 # tokens
│   ├── screen/                # Chat, Device, Signals, Forge, More...
│   └── viewmodel/
├── voice/                     # speech recognition, TTS
├── web/                       # token-protected local file server
└── widget/                    # home-screen widget
app/src/test/                  # unit tests
.github/workflows/             # CI: build, test, lint
docs/                          # architecture, schemas, upgrade plan
```

---

## Supported actions

| Action | Description | Risk |
|---|---|---|
| `list_directory` | List files and folders | Low |
| `read_file` | Read file contents | Low |
| `write_file` | Create or modify files | Medium |
| `create_directory` | Create folders | Low |
| `delete` | Delete files or folders | High |
| `move` / `rename` / `copy` | File operations | Medium–High |
| `get_device_info` | Battery, firmware, hardware | Low |
| `get_storage_info` | Storage statistics | Low |
| `execute_cli` | Run Flipper CLI commands | Medium |
| `push_artifact` | Push generated files | Medium |
| `forge_payload` | Generate SubGHz/IR/BadUSB/NFC payloads | Medium |
| `subghz_transmit` | Transmit SubGHz | High |
| `ir_transmit` | Send infrared | Medium |
| `nfc_emulate` / `rfid_emulate` / `ibutton_emulate` | Emulate credentials | Medium |
| `badusb_execute` | Run a HID script | High |
| `launch_app` | Start a Flipper app | Medium |
| `led_control` / `vibro_control` | Peripherals | Low |
| `search_faphub` / `install_faphub_app` | App catalog | Medium |
| `browse_repo` / `download_resource` / `github_search` | Community resources | Low |
| `request_photo` | Capture via glasses | Low |

---

## Troubleshooting

<details>
<summary><strong>Bluetooth is off or access was denied</strong></summary>

The app shows a banner at the top: **Bluetooth access is off**. Tap Grant to
request it again. If Android has stopped asking (permanent denial), open
**Settings > Apps > Vesper > Permissions > Nearby devices** and enable it. You
do not need location access on Android 12+.
</details>

<details>
<summary><strong>The Flipper is not found when scanning</strong></summary>

1. Flipper: Settings > Bluetooth > ON.
2. Make sure it is not connected to qFlipper or another phone.
3. Stay within a meter.
4. Toggle the phone's Bluetooth off and on.
5. If another app holds the connection, disconnect it first.
</details>

<details>
<summary><strong>The keyboard covers the chat input</strong></summary>

This is fixed in 1.1: the shell applies the IME inset and hides the bottom bar
while typing. If you still see it, make sure you are on 1.1 and your Android
system update is current.
</details>

<details>
<summary><strong>AI is not responding</strong></summary>

1. Check the key in Settings; use **Test connection**.
2. Check your OpenRouter credit balance.
3. Check the network, then try a different model.
4. If a request hangs, tap Stop and retry.
</details>

<details>
<summary><strong>Tool arguments could not be parsed</strong></summary>

Some models produce malformed JSON. Vesper repairs display output, but it will
not execute a repaired tool call. Retry, switch to a recommended model, or
simplify the request.
</details>

<details>
<summary><strong>Permission denied for a path</strong></summary>

System and firmware paths are BLOCKED by default. Unlock them explicitly in
Settings > Locked paths. Prefer working inside your project folder.
</details>

<details>
<summary><strong>Build fails</strong></summary>

1. JDK 17 or newer.
2. Android SDK platform 35 installed.
3. File > Sync Project with Gradle Files.
4. Delete `.gradle` and reopen the project.
5. Windows: use `gradlew.bat`, not `./gradlew`.
</details>

---

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR. Areas that need
help:

- Instrumented tests and a screenshot test harness.
- Additional signal format parsers.
- New payload templates.
- Translations.
- Toolchain modernization (see [docs/UPGRADING.md](docs/UPGRADING.md)).

---

## Security

Found a vulnerability? Report it responsibly — see [SECURITY.md](SECURITY.md).

## Safety and legal

- Vesper is for education and authorized security research.
- Only use it on hardware you own or have explicit permission to test.
- Every AI action is logged; HIGH-risk actions require a deliberate hold.
- You are responsible for complying with the laws of your jurisdiction.

## License

GPL-3.0 — see [LICENSE](LICENSE).

---

**V3SP3R** — your Flipper Zero just got a brain.
