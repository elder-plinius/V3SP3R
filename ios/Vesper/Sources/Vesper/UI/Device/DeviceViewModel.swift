import SwiftUI

@Observable
class DeviceViewModel {
    private let bleManager: FlipperBLEManager
    private let fileSystem: FlipperFileSystem

    var isScanning: Bool {
        bleManager.connectionState == .scanning
    }

    var connectionState: ConnectionState {
        bleManager.connectionState
    }

    var discoveredDevices: [FlipperDevice] {
        bleManager.discoveredDevices
    }

    var connectedDevice: FlipperDevice? {
        bleManager.connectedDevice
    }

    var deviceInfo: DeviceInfo?
    var storageInfo: StorageInfo?
    var isLoadingInfo: Bool = false
    var error: String?

    init(bleManager: FlipperBLEManager, fileSystem: FlipperFileSystem) {
        self.bleManager = bleManager
        self.fileSystem = fileSystem
    }

    func startScanning() {
        bleManager.startScanning()
    }

    func stopScanning() {
        bleManager.stopScanning()
    }

    func connect(to device: FlipperDevice) {
        bleManager.connect(device: device)
    }

    func disconnect() {
        bleManager.disconnect()
        deviceInfo = nil
        storageInfo = nil
    }

    func loadDeviceInfo() {
        guard connectionState == .connected else { return }
        isLoadingInfo = true
        error = nil

        Task {
            do {
                deviceInfo = try await fileSystem.getDeviceInfo()
                storageInfo = try await fileSystem.getStorageInfo()
            } catch {
                self.error = error.localizedDescription
            }
            isLoadingInfo = false
        }
    }
}
