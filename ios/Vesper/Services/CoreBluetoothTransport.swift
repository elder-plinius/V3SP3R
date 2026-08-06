import CoreBluetooth
import Foundation
import VesperCore

@MainActor
final class CoreBluetoothTransport: NSObject, @preconcurrency FlipperTransport {
    private static let serviceUUIDs = [
        CBUUID(string: "3082"), CBUUID(string: "3081"), CBUUID(string: "3083"),
        CBUUID(string: "8FE5B3D5-2E7F-4A98-2A48-7ACC60FE0000")
    ]
    private static let serialService = CBUUID(string: "8FE5B3D5-2E7F-4A98-2A48-7ACC60FE0000")
    private static let txUUID = CBUUID(string: "19ED82AE-ED21-4C9D-4145-228E62FE0000")
    private static let rxUUID = CBUUID(string: "19ED82AE-ED21-4C9D-4145-228E61FE0000")
    private static let overflowUUID = CBUUID(string: "19ED82AE-ED21-4C9D-4145-228E63FE0000")
    private static let resetUUID = CBUUID(string: "19ED82AE-ED21-4C9D-4145-228E64FE0000")

    private lazy var central = CBCentralManager(delegate: self, queue: .main)
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var signalStrength: [UUID: Int] = [:]
    private var peripheral: CBPeripheral?
    private var tx: CBCharacteristic?
    private var rx: CBCharacteristic?
    private var overflow: CBCharacteristic?
    private var reset: CBCharacteristic?
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var writeContinuation: CheckedContinuation<Void, Error>?

    private var stateContinuation: AsyncStream<TransportConnectionState>.Continuation?
    private var devicesContinuation: AsyncStream<[DiscoveredFlipper]>.Continuation?
    private var bytesContinuation: AsyncStream<Data>.Continuation?
    private lazy var stateStream = AsyncStream<TransportConnectionState> { stateContinuation = $0 }
    private lazy var deviceStream = AsyncStream<[DiscoveredFlipper]> { devicesContinuation = $0 }
    private lazy var byteStream = AsyncStream<Data> { bytesContinuation = $0 }

    override init() {
        super.init()
        _ = central
        stateContinuation?.yield(.idle)
    }

    func connectionStates() async -> AsyncStream<TransportConnectionState> { stateStream }
    func discoveredDevices() async -> AsyncStream<[DiscoveredFlipper]> { deviceStream }
    func receivedBytes() async -> AsyncStream<Data> { byteStream }

    func scan() async throws {
        guard central.state == .poweredOn else { throw BLEError.bluetoothUnavailable }
        peripherals.removeAll()
        signalStrength.removeAll()
        stateContinuation?.yield(.scanning)
        central.scanForPeripherals(withServices: Self.serviceUUIDs, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() async { central.stopScan() }

    func connect(to id: UUID) async throws {
        guard let candidate = peripherals[id] else { throw BLEError.deviceNotFound }
        central.stopScan()
        peripheral = candidate
        candidate.delegate = self
        stateContinuation?.yield(.connecting(name: candidate.name ?? "Flipper Zero"))
        try await withCheckedThrowingContinuation { continuation in
            connectContinuation = continuation
            central.connect(candidate, options: nil)
        }
    }

    func disconnect() async {
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
        resetConnection(reason: nil)
    }

    func send(_ data: Data) async throws {
        guard let peripheral, let tx else { throw VesperCoreError.disconnected }
        let type: CBCharacteristicWriteType = tx.properties.contains(.write) ? .withResponse : .withoutResponse
        let maximum = max(20, peripheral.maximumWriteValueLength(for: type))
        var offset = 0
        while offset < data.count {
            let end = min(data.count, offset + maximum)
            let chunk = data.subdata(in: offset..<end)
            if type == .withResponse {
                try await withCheckedThrowingContinuation { continuation in
                    writeContinuation = continuation
                    peripheral.writeValue(chunk, for: tx, type: type)
                }
            } else {
                while !peripheral.canSendWriteWithoutResponse { await Task.yield() }
                peripheral.writeValue(chunk, for: tx, type: type)
            }
            offset = end
        }
    }

    private func publishDevices() {
        let values = peripherals.values.map {
            DiscoveredFlipper(id: $0.identifier, name: $0.name ?? "Flipper Zero", signalStrength: signalStrength[$0.identifier] ?? -127)
        }.sorted { $0.signalStrength > $1.signalStrength }
        devicesContinuation?.yield(values)
    }

    private func resetConnection(reason: String?) {
        peripheral = nil; tx = nil; rx = nil; overflow = nil; reset = nil
        stateContinuation?.yield(.disconnected(reason: reason))
    }
}

extension CoreBluetoothTransport: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn { stateContinuation?.yield(.failed(message: BLEError.bluetoothUnavailable.localizedDescription)) }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        peripherals[peripheral.identifier] = peripheral
        signalStrength[peripheral.identifier] = RSSI.intValue
        publishDevices()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices(Self.serviceUUIDs)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectContinuation?.resume(throwing: error ?? BLEError.connectionFailed)
        connectContinuation = nil
        resetConnection(reason: error?.localizedDescription)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        resetConnection(reason: error?.localizedDescription)
    }
}

extension CoreBluetoothTransport: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else { connectContinuation?.resume(throwing: error!); connectContinuation = nil; return }
        for service in peripheral.services ?? [] { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { connectContinuation?.resume(throwing: error!); connectContinuation = nil; return }
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case Self.txUUID: tx = characteristic
            case Self.rxUUID: rx = characteristic; peripheral.setNotifyValue(true, for: characteristic)
            case Self.overflowUUID: overflow = characteristic; peripheral.setNotifyValue(true, for: characteristic)
            case Self.resetUUID: reset = characteristic
            default: break
            }
        }
        if tx != nil, rx != nil, let continuation = connectContinuation {
            connectContinuation = nil
            stateContinuation?.yield(.connected(name: peripheral.name ?? "Flipper Zero"))
            continuation.resume()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        if characteristic.uuid == Self.rxUUID { bytesContinuation?.yield(value) }
        if characteristic.uuid == Self.overflowUUID, value.contains(where: { $0 != 0 }), let reset {
            peripheral.writeValue(Data([1]), for: reset, type: .withResponse)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.txUUID else { return }
        guard let continuation = writeContinuation else { return }
        writeContinuation = nil
        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
    }
}

enum BLEError: LocalizedError {
    case bluetoothUnavailable, deviceNotFound, connectionFailed, missingSerialCharacteristics
    var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable: "Bluetooth is unavailable or powered off."
        case .deviceNotFound: "The selected Flipper is no longer available."
        case .connectionFailed: "Could not connect to the Flipper."
        case .missingSerialCharacteristics: "The Flipper serial service is incomplete."
        }
    }
}
