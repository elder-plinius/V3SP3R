import SwiftUI
import VesperCore

struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @State private var showFiles = false

    var body: some View {
        NavigationStack {
            List {
                Section("Connection") {
                    LabeledContent("Status", value: statusText)
                    if case .connected = model.connectionState {
                        Button("Disconnect", role: .destructive) { Task { await model.disconnect() } }
                    } else {
                        Button { Task { await model.scan() } } label: { Label(model.isScanning ? "Scanning…" : "Scan for Flipper", systemImage: "dot.radiowaves.left.and.right") }
                    }
                }
                if !model.discoveredDevices.isEmpty, !isConnected {
                    Section("Nearby") {
                        ForEach(model.discoveredDevices) { device in
                            Button { Task { await model.connect(device) } } label: {
                                HStack { VStack(alignment: .leading) { Text(device.name); Text(device.id.uuidString).font(.caption2).foregroundStyle(.secondary) }; Spacer(); Text("\(device.signalStrength) dBm").font(.caption).foregroundStyle(.secondary) }
                            }.foregroundStyle(.primary)
                        }
                    }
                }
                if let device = model.deviceInfo {
                    Section("Flipper Zero") {
                        LabeledContent("Name", value: device.name)
                        LabeledContent("Firmware", value: device.firmwareVersion)
                        LabeledContent("Hardware", value: device.hardwareVersion)
                        LabeledContent("Battery", value: "\(device.batteryLevel)%\(device.isCharging ? " · charging" : "")")
                    }
                }
                if let storage = model.storageInfo {
                    Section("Storage") {
                        LabeledContent("Internal free", value: ByteCountFormatter.string(fromByteCount: Int64(storage.internalFree), countStyle: .file))
                        if let free = storage.externalFree { LabeledContent("SD card free", value: ByteCountFormatter.string(fromByteCount: Int64(free), countStyle: .file)) }
                        Button { showFiles = true } label: { Label("Browse Files", systemImage: "folder") }
                    }
                }
                Section("Pipeline") { Text(model.diagnostics).font(.footnote).foregroundStyle(.secondary); Button("Refresh diagnostics") { Task { await model.refreshDevice() } }.disabled(!isConnected) }
            }
            .navigationTitle("Device")
            .navigationDestination(isPresented: $showFiles) { FileBrowserView().task { await model.loadDirectory("/ext") } }
            .overlay { if model.isBusy { ProgressView().controlSize(.large) } }
            .alert("Vesper", isPresented: Binding(get: { model.alertMessage != nil }, set: { if !$0 { model.alertMessage = nil } })) { Button("OK") { model.alertMessage = nil } } message: { Text(model.alertMessage ?? "") }
        }
    }

    private var isConnected: Bool { if case .connected = model.connectionState { true } else { false } }
    private var statusText: String {
        switch model.connectionState {
        case .idle: "Idle"; case .scanning: "Scanning"; case .connecting(let name): "Connecting to \(name)"; case .connected(let name): "Connected to \(name)"; case .disconnected(let reason): reason.map { "Disconnected: \($0)" } ?? "Disconnected"; case .failed(let message): message
        }
    }
}
