# Vesper - AI-Driven Flipper Zero Control for Android

Vesper is an Android app that lets you control your Flipper Zero with AI. Just tell it what you want in plain English, and it does it.

---

## Complete Beginner's Guide

**Never used a Flipper Zero or built an Android app before?** This guide is for you.

### What You Need

| Item | Notes |
|------|-------|
| **Flipper Zero** | The device itself ([shop.flipperzero.one](https://shop.flipperzero.one)) |
| **Android Phone** | Android 8.0 or newer, with Bluetooth |
| **Computer** | Windows, Mac, or Linux - for building the app |
| **OpenRouter Account** | Free to sign up, pay-per-use AI API |

### Step 1: Get Your Flipper Ready

1. **Unbox and charge** your Flipper Zero (USB-C cable included)
2. **Update firmware** (optional but recommended):
   - Go to [flipperzero.one/update](https://flipperzero.one/update)
   - Download qFlipper for your computer
   - Connect Flipper via USB, click Update
3. **Enable Bluetooth** on Flipper:
   - On Flipper: Settings → Bluetooth → ON
   - Note the name shown (like "Flipper ABCD")

### Step 2: Get an OpenRouter API Key

Vesper uses AI through OpenRouter (works with Claude, GPT-4, etc.)

1. Go to [openrouter.ai](https://openrouter.ai)
2. Click **Sign Up** (use Google/GitHub for quick signup)
3. Once logged in, go to **Keys** in the menu
4. Click **Create Key**
5. Copy the key that starts with `sk-or-...` — you'll need this later

**Cost**: Most conversations cost $0.01-0.05. Add $5-10 to start.

### Step 3: Install Android Studio

This is the tool to build the app.

**Windows:**
1. Download from [developer.android.com/studio](https://developer.android.com/studio)
2. Run the installer, click Next through everything
3. Let it download (takes 10-20 minutes)

**Mac:**
1. Download the .dmg from the same link
2. Drag Android Studio to Applications
3. Open it, let it set up

**Linux:**
```bash
sudo snap install android-studio --classic
```

### Step 4: Download Vesper

Open a terminal (or Git Bash on Windows):

```bash
git clone https://github.com/LYS10S/PLIPPER.git
cd PLIPPER
```

**Don't have Git?**
- Windows: Download from [git-scm.com](https://git-scm.com), install, use "Git Bash"
- Mac: It'll prompt you to install when you type `git`
- Linux: `sudo apt install git`

### Step 5: Build the App

1. **Open Android Studio**
2. Click **Open** and select the `PLIPPER` folder you downloaded
3. Wait for it to sync (progress bar at bottom, takes 2-5 minutes)
4. If it asks about Gradle, click **OK** or **Use default**

**Build the APK:**
- Click the green hammer icon (Build) OR
- Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- Wait 1-3 minutes
- A popup says "APK(s) generated" — click **locate**

The APK is at: `app/build/outputs/apk/debug/app-debug.apk`

### Step 6: Install on Your Phone

**Option A: USB Cable (Easiest)**
1. On your phone: Settings → About Phone → tap "Build Number" 7 times
2. Go back, find "Developer Options" → enable "USB Debugging"
3. Connect phone to computer via USB
4. In Android Studio: Click the green Play button (▶️)
5. Select your phone from the list

**Option B: Transfer the APK**
1. Copy `app-debug.apk` to your phone (email, Google Drive, USB)
2. On phone: Open the file
3. If blocked: Settings → Security → "Install unknown apps" → allow
4. Tap Install

### Step 7: First Launch

1. **Open Vesper** on your phone
2. **Grant permissions** when asked:
   - Bluetooth: YES (needed to talk to Flipper)
   - Location: YES (Android requires this for Bluetooth scanning)
   - Notifications: YES (optional, for connection alerts)

3. **Add your API Key:**
   - Tap the **Device** tab (Bluetooth icon)
   - Tap **Settings** (gear icon)
   - Find "OpenRouter API Key"
   - Paste your `sk-or-...` key

4. **Connect to Flipper:**
   - Make sure Flipper is on with Bluetooth enabled
   - In Vesper, go to **Device** tab
   - Tap **Scan**
   - Tap your Flipper when it appears
   - Wait for "Connected" status

5. **Start using it:**
   - Go to **Chat** tab
   - Type: "What files are on my SD card?"
   - Watch the magic happen

---

## Features Overview

### Chat (AI Control)
Talk to your Flipper in plain English:
- "Show me my SubGHz captures"
- "What's my battery level?"
- "Create a backup of all my IR remotes"

### Ops Center
Operational control surface for connection reliability:
- **Pipeline Health**: BLE/RPC/CLI readiness and diagnostics summary
- **Runbooks**: One-tap recovery and smoke-test sequences
- **Macro Recorder**: Record/replay remote button workflows with timing
- **Live Status**: See transport and command pipeline behavior in one place

### Alchemy (Signal Lab)
Build custom RF signals from scratch:
- Visual waveform editor
- Layer multiple signal patterns
- Export directly to Flipper

### Device
Manage your Flipper connection:
- Battery status
- Storage info
- Connection controls

### Risk & Permissions
All AI actions are classified by risk before execution:
- **Low**: Read-only operations execute automatically
- **Medium**: File writes show a diff for review before applying
- **High**: Destructive ops (delete, move, overwrite) require double-tap confirmation
- **Blocked**: System/firmware paths require explicit unlock in settings

You can configure **auto-approve** per tier in Settings → Permissions to skip confirmation dialogs for medium and/or high risk actions.

---

## Troubleshooting for Beginners

### "Flipper not found when scanning"
1. On Flipper: Settings → Bluetooth → make sure it's ON
2. On phone: Turn Bluetooth off and on
3. Make sure Flipper isn't connected to another device
4. Try moving closer (within 3 feet)

### "Build failed" in Android Studio
1. File → Sync Project with Gradle Files
2. Build → Clean Project, then Build → Rebuild Project
3. Close Android Studio, delete the `.gradle` folder, reopen

### "App crashes on launch"
1. Make sure you granted all permissions
2. Try uninstalling and reinstalling
3. Check that your phone is Android 8.0+

### "AI not responding"
1. Check your OpenRouter API key is correct
2. Make sure you have credits in your OpenRouter account
3. Check your internet connection

### "Permission denied" errors
- Some Flipper paths are protected (system files, firmware areas)
- Go to Settings → Permissions to unlock specific protected paths
- You can enable **auto-approve** per risk tier in Settings → Permissions:
  - **Medium risk**: Auto-approve file writes within project scope (skips diff review)
  - **High risk**: Auto-approve destructive actions like deletes and moves (skips confirmation)
- Blocked paths (system/firmware) always require manual unlock regardless of auto-approve settings

---

## Example Commands to Try

Once connected, try these in the Chat:

```
"What's on my SD card?"
"Show me my SubGHz folder"
"What's my battery at?"
"Read my garage.sub file"
"List all my IR remotes"
"How much storage do I have left?"
```

More advanced:
```
"Create a new folder called 'backups'"
"Copy all .sub files to the backups folder"
"Change the frequency in garage.sub to 315MHz"
"Generate a BadUSB script that opens notepad and types hello"
```

---

## Safety & Legal

- This is a tool for learning and legitimate security research
- Only use on devices you own or have permission to test
- BLE spam attacks may be illegal in public spaces
- The AI will refuse clearly malicious requests
- All actions are logged for your review

---

## Architecture

```
┌─────────────────────────────────────────┐
│              Vesper App                  │
├─────────────────────────────────────────┤
│  UI Layer (Jetpack Compose)             │
│  ├── Chat Screen                        │
│  ├── Ops Center                         │
│  ├── Alchemy Lab                        │
│  ├── File Browser                       │
│  └── Device Screen                      │
├─────────────────────────────────────────┤
│  Domain Layer                           │
│  ├── VesperAgent (AI Orchestration)     │
│  ├── CommandExecutor (Risk Enforcement) │
│  ├── Signal Processing                  │
│  └── Device Automation + Auditing       │
├─────────────────────────────────────────┤
│  Data Layer                             │
│  ├── OpenRouter Client                  │
│  ├── Flipper BLE Service                │
│  └── Encrypted Storage                  │
└─────────────────────────────────────────┘
```

---

## Project Structure

```
app/src/main/java/com/vesper/flipper/
├── ai/                    # AI integration
│   └── OpenRouterClient.kt
├── ble/                   # Bluetooth
│   ├── FlipperBleService.kt
│   └── FlipperFileSystem.kt
├── domain/model/          # Data models
│   ├── Alchemy.kt         # Signal synthesis
│   ├── Chimera.kt         # Signal fusion
│   └── Signal.kt          # Capture formats
├── security/              # Security utilities
│   └── SecurityUtils.kt
├── ui/
│   ├── screen/            # UI screens
│   │   ├── ChatScreen.kt
│   │   ├── OpsCenterScreen.kt
│   │   └── AlchemyLabScreen.kt
│   └── viewmodel/         # State management
└── MainActivity.kt
```

---

## OpenRouter Models

Vesper supports these AI models:

| Model | Speed | Quality | Cost |
|-------|-------|---------|------|
| claude-3-5-sonnet | Fast | Excellent | $$ |
| claude-3-opus | Slow | Best | $$$$ |
| claude-3-haiku | Fastest | Good | $ |
| gpt-4-turbo | Medium | Excellent | $$$ |
| gpt-4o | Fast | Excellent | $$ |

Default is Claude 3.5 Sonnet (best balance).

---

## Contributing

Pull requests welcome! Areas that need work:
- iOS version
- More signal format parsers
- Additional attack presets
- UI improvements

---

## License

MIT License - see LICENSE file.

---

## Disclaimer

Vesper is for educational purposes and legitimate security research. Users are responsible for complying with local laws. The authors assume no liability for misuse.

---

**Vesper** - AI-powered hardware hacking, in your pocket.
