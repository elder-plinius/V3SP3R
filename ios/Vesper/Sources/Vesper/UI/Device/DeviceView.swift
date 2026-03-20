import SwiftUI

struct DeviceView: View {
    @Bindable var viewModel: DeviceViewModel

    var body: some View {
        List {
            connectionSection
            if viewModel.connectedDevice != nil {
                deviceInfoSection
                storageInfoSection
            }
            if viewModel.isScanning || !viewModel.discoveredDevices.isEmpty {
                discoveredDevicesSection
            }
        }
        .navigationTitle("Device")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if viewModel.connectedDevice != nil {
                    Button("Disconnect", role: .destructive) {
                        viewModel.disconnect()
                    }
                }
            }
        }
        .onAppear {
            if viewModel.connectedDevice != nil {
                viewModel.loadDeviceInfo()
            }
        }
    }

    private var connectionSection: some View {
        Section("Connection") {
            HStack {
                connectionStatusIcon
                VStack(alignment: .leading) {
                    Text(connectionStatusText)
                        .font(.headline)
                    if let device = viewModel.connectedDevice {
                        Text(device.name)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if viewModel.connectedDevice == nil {
                    Button(viewModel.isScanning ? "Stop" : "Scan") {
                        if viewModel.isScanning {
                            viewModel.stopScanning()
                        } else {
                            viewModel.startScanning()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
            }

            if let error = viewModel.error {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder
    private var deviceInfoSection: some View {
        if let info = viewModel.deviceInfo {
            Section("Device Info") {
                LabeledContent("Name", value: info.name)
                LabeledContent("Firmware", value: info.firmwareVersion)
                LabeledContent("Hardware", value: info.hardwareVersion)
                HStack {
                    Text("Battery")
                    Spacer()
                    batteryView(level: info.batteryLevel, charging: info.isCharging)
                }
            }
        } else if viewModel.isLoadingInfo {
            Section("Device Info") {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading...")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var storageInfoSection: some View {
        if let info = viewModel.storageInfo {
            Section("Storage") {
                storageBar(label: "Internal", used: info.internalTotal - info.internalFree, total: info.internalTotal)
                if info.hasSdCard, let extTotal = info.externalTotal, let extFree = info.externalFree {
                    storageBar(label: "SD Card", used: extTotal - extFree, total: extTotal)
                }
            }
        }
    }

    private var discoveredDevicesSection: some View {
        Section("Discovered Devices") {
            if viewModel.isScanning {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Scanning...")
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(viewModel.discoveredDevices) { device in
                Button {
                    viewModel.connect(to: device)
                } label: {
                    HStack {
                        Image(systemName: "flipphone")
                            .foregroundStyle(.purple)
                        VStack(alignment: .leading) {
                            Text(device.name)
                                .font(.body)
                            Text("RSSI: \(device.rssi) dBm")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(.secondary)
                    }
                }
                .tint(.primary)
            }
        }
    }

    private var connectionStatusIcon: some View {
        Group {
            switch viewModel.connectionState {
            case .connected:
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            case .connecting:
                ProgressView()
                    .controlSize(.small)
            case .scanning:
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(.blue)
                    .symbolEffect(.variableColor)
            case .disconnected:
                Image(systemName: "circle")
                    .foregroundStyle(.secondary)
            case .error:
                Image(systemName: "exclamationmark.circle.fill")
                    .foregroundStyle(.red)
            }
        }
        .font(.title2)
    }

    private var connectionStatusText: String {
        switch viewModel.connectionState {
        case .connected: "Connected"
        case .connecting: "Connecting..."
        case .scanning: "Scanning..."
        case .disconnected: "Disconnected"
        case .error(let msg): "Error: \(msg)"
        }
    }

    private func batteryView(level: Int, charging: Bool) -> some View {
        HStack(spacing: 4) {
            Text("\(level)%")
                .font(.body.monospacedDigit())
            Image(systemName: charging ? "battery.100.bolt" : batteryIcon(level: level))
                .foregroundStyle(level > 20 ? .green : .red)
        }
    }

    private func batteryIcon(level: Int) -> String {
        switch level {
        case 0..<25: "battery.25"
        case 25..<50: "battery.50"
        case 50..<75: "battery.75"
        default: "battery.100"
        }
    }

    private func storageBar(label: String, used: Int64, total: Int64) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                Text("\(formatBytes(used)) / \(formatBytes(total))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: Double(used), total: Double(total))
                .tint(Double(used) / Double(total) > 0.9 ? .red : .accentColor)
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
