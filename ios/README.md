# Vesper for iOS

Vesper's iOS alpha is a native SwiftUI iPhone app targeting iOS 17 and newer. It shares the repository and command schema with Android, but does not use Kotlin Multiplatform.

## Architecture

- `VesperCore/` contains the platform-neutral command contract, parser, risk engine, diffing, approvals, audit contracts, and bounded agent loop.
- `Vesper/Services/` contains the CoreBluetooth transport, Flipper protobuf RPC client, OpenRouter client, Keychain storage, and audit stream.
- `Vesper/Features/` contains the native Chat, Device, Files, Audit, Settings, and onboarding flows.
- `Protos/` and `Vesper/Generated/` contain pinned Flipper RPC schemas and generated SwiftProtobuf sources.

The app deliberately executes commands only while it is active. Moving it out of the foreground cancels pending approvals and pauses the agent loop. It does not advertise continuous background operation.

## Build and test

Requirements: Xcode 16 or newer, Swift 6, an iOS 17+ deployment target, and internet access for the exact SwiftProtobuf 1.32.0 package dependency.

```sh
open ios/Vesper.xcodeproj
swift test --package-path ios/VesperCore
xcodebuild test \
  -project ios/Vesper.xcodeproj \
  -scheme Vesper \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

Select a development team before installing on an iPhone. The bundle identifier is `com.vesper.flipper.ios`. OpenRouter keys are supplied by each user and stored with the Keychain `WhenUnlockedThisDeviceOnly` accessibility class.

## Required hardware gate

Do not call the BLE/RPC layer production-ready until this exact sequence passes on current official stable Flipper firmware:

1. Scan for and connect to a Flipper Zero.
2. Read device, power, and storage information.
3. List `/ext`.
4. Read one existing file.
5. Write a disposable file under `/ext/apps_data/vesper`, read it back, and remove it.
6. Repeat after disconnecting during a read, a write, and an approval wait.

Run the full action matrix on at least two iPhones running iOS 17 or later. Radio transmission, credential emulation, BadUSB, and BLE-spam tests must use owned equipment and an authorized environment.

## Internal TestFlight checklist

- Select the App Store signing team and review the converted Vesper brand icon on physical devices.
- Archive a Release build and confirm the privacy manifest, Bluetooth/camera/photo usage descriptions, GPL notice, and export-compliance metadata.
- Inspect the archive and logs for secrets or prompt/image content.
- Complete the hardware gate and UI smoke suite.
- Upload to App Store Connect and assign the build to an internal-testing group.

The repository does not contain App Store Connect credentials and CI does not upload builds automatically.
