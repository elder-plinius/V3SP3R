// FlipperBLEManager.swift
// Vesper - AI-powered Flipper Zero controller
// CoreBluetooth implementation for Flipper Zero BLE communication

import CoreBluetooth
import Combine
import os.log

private let logger = Logger(subsystem: "com.vesper.flipper", category: "BLE")

@Observable
class FlipperBLEManager: NSObject {

    // MARK: - Public State

    var connectionState: ConnectionState = .disconnected
    var discoveredDevices: [FlipperDevice] = []
    var connectedDevice: FlipperDevice? = nil

    // MARK: - Private State

    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var serialTxCharacteristic: CBCharacteristic?
    private var serialRxCharacteristic: CBCharacteristic?
    private var negotiatedMTU: Int = 20

    private var pendingOperations: [String: CheckedContinuation<Data, Error>] = [:]
    private var pendingWriteAcks: [String: CheckedContinuation<Void, Error>] = [:]
    private var dataBuffer = Data()
    private var expectedFrameLength: Int? = nil

    private var reconnectTask: Task<Void, Never>?
    private var keepaliveTask: Task<Void, Never>?
    private var scanTimeoutTask: Task<Void, Never>?
    private var lastConnectedDeviceId: String?
    private var reconnectAttemptCount: Int = 0
    private var isReconnecting: Bool = false
    private var notificationsReady: Bool = false

    /// Callback invoked when raw data arrives from the Flipper's serial RX characteristic.
    var onDataReceived: ((Data) -> Void)?

    // MARK: - GATT UUIDs

    static let flipperServiceUUID = CBUUID(string: "00003082-0000-1000-8000-00805f9b34fb")
    static let flipperServiceBlackUUID = CBUUID(string: "00003081-0000-1000-8000-00805f9b34fb")
    static let flipperServiceTransparentUUID = CBUUID(string: "00003083-0000-1000-8000-00805f9b34fb")
    static let serialServiceUUID = CBUUID(string: "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    static let serialTxUUID = CBUUID(string: "19ed82ae-ed21-4c9d-4145-228e62fe0000")
    static let serialRxUUID = CBUUID(string: "19ed82ae-ed21-4c9d-4145-228e61fe0000")

    static let scanServiceUUIDs: [CBUUID] = [
        flipperServiceUUID,
        flipperServiceBlackUUID,
        flipperServiceTransparentUUID,
        serialServiceUUID
    ]

    // MARK: - Constants

    private static let defaultATTMTU: Int = 20
    private static let maxReconnectAttempts: Int = 5
    private static let reconnectBaseDelaySeconds: TimeInterval = 2.0
    private static let scanTimeoutSeconds: TimeInterval = 30.0
    private static let keepaliveIntervalSeconds: TimeInterval = 15.0
    private static let writeInterChunkDelay: UInt64 = 15_000_000 // 15ms in nanoseconds
    private static let commandTimeoutSeconds: TimeInterval = 5.0

    // MARK: - Init

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Public API

    func startScanning() {
        guard centralManager.state == .poweredOn else {
            logger.warning("Cannot scan: Bluetooth not powered on (state: \(self.centralManager.state.rawValue))")
            if centralManager.state == .unauthorized {
                connectionState = .error("Bluetooth permission denied")
            } else if centralManager.state == .poweredOff {
                connectionState = .error("Bluetooth is turned off")
            }
            return
        }

        discoveredDevices.removeAll()
        connectionState = .scanning

        centralManager.scanForPeripherals(
            withServices: Self.scanServiceUUIDs,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        logger.info("Started BLE scan for Flipper devices")

        scanTimeoutTask?.cancel()
        scanTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.scanTimeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, self.connectionState == .scanning else { return }
                self.stopScanning()
                if self.discoveredDevices.isEmpty {
                    self.connectionState = .error("No Flipper devices found")
                }
            }
        }
    }

    func stopScanning() {
        scanTimeoutTask?.cancel()
        scanTimeoutTask = nil

        guard centralManager.isScanning else { return }
        centralManager.stopScan()

        if connectionState == .scanning {
            connectionState = .disconnected
        }
        logger.info("Stopped BLE scan")
    }

    func connect(device: FlipperDevice) {
        stopScanning()
        reconnectAttemptCount = 0
        isReconnecting = false
        lastConnectedDeviceId = device.id

        guard let peripheral = findPeripheral(for: device) else {
            connectionState = .error("Device not found: \(device.name)")
            return
        }

        connectionState = .connecting
        connectedPeripheral = peripheral
        peripheral.delegate = self

        centralManager.connect(peripheral, options: [
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
        ])

        logger.info("Connecting to \(device.name) [\(device.id)]")
    }

    func disconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        keepaliveTask?.cancel()
        keepaliveTask = nil
        isReconnecting = false
        reconnectAttemptCount = 0
        lastConnectedDeviceId = nil

        if let peripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }

        cleanupConnection()
        connectionState = .disconnected
        logger.info("Disconnected from Flipper")
    }

    func sendData(_ data: Data) async throws {
        guard let peripheral = connectedPeripheral,
              let txCharacteristic = serialTxCharacteristic else {
            throw FlipperBLEError.notConnected
        }

        guard peripheral.state == .connected else {
            throw FlipperBLEError.notConnected
        }

        // Split data into MTU-sized chunks
        let chunkSize = max(negotiatedMTU - 3, 20) // 3 bytes for ATT header
        var offset = 0

        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data[offset..<end]

            try await writeChunk(Data(chunk), to: peripheral, characteristic: txCharacteristic)

            offset = end

            // Small delay between chunks to avoid overwhelming the BLE link
            if offset < data.count {
                try await Task.sleep(nanoseconds: Self.writeInterChunkDelay)
            }
        }
    }

    /// Send a framed command and wait for a complete response frame.
    func sendFramedData(_ data: Data) async throws -> Data {
        let operationId = UUID().uuidString

        return try await withCheckedThrowingContinuation { continuation in
            pendingOperations[operationId] = continuation

            Task {
                do {
                    try await sendData(data)
                } catch {
                    if let cont = pendingOperations.removeValue(forKey: operationId) {
                        cont.resume(throwing: error)
                    }
                }

                // Set a timeout for the response
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(Self.commandTimeoutSeconds * 1_000_000_000))
                    if let cont = self.pendingOperations.removeValue(forKey: operationId) {
                        cont.resume(throwing: FlipperBLEError.timeout)
                    }
                }
            }
        }
    }

    // MARK: - Private Helpers

    private func findPeripheral(for device: FlipperDevice) -> CBPeripheral? {
        let connected = centralManager.retrieveConnectedPeripherals(withServices: Self.scanServiceUUIDs)
        if let match = connected.first(where: { $0.identifier.uuidString == device.id }) {
            return match
        }
        if let uuid = UUID(uuidString: device.id) {
            return centralManager.retrievePeripherals(withIdentifiers: [uuid]).first
        }
        return nil
    }

    private func writeChunk(_ chunk: Data, to peripheral: CBPeripheral, characteristic: CBCharacteristic) async throws {
        let writeType: CBCharacteristicWriteType =
            characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse

        if writeType == .withResponse {
            let writeId = "write_\(UUID().uuidString)"
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                pendingWriteAcks[writeId] = continuation
                peripheral.writeValue(chunk, for: characteristic, type: writeType)

                // Timeout for the write acknowledgment
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    if let cont = self?.pendingWriteAcks.removeValue(forKey: writeId) {
                        cont.resume(throwing: FlipperBLEError.writeTimeout)
                    }
                }
            }
        } else {
            peripheral.writeValue(chunk, for: characteristic, type: writeType)
        }
    }

    private func cleanupConnection() {
        serialTxCharacteristic = nil
        serialRxCharacteristic = nil
        connectedPeripheral = nil
        connectedDevice = nil
        notificationsReady = false
        negotiatedMTU = Self.defaultATTMTU
        dataBuffer.removeAll()
        expectedFrameLength = nil

        // Fail all pending operations
        let pending = pendingOperations
        pendingOperations.removeAll()
        for (_, continuation) in pending {
            continuation.resume(throwing: FlipperBLEError.disconnected)
        }

        let pendingWrites = pendingWriteAcks
        pendingWriteAcks.removeAll()
        for (_, continuation) in pendingWrites {
            continuation.resume(throwing: FlipperBLEError.disconnected)
        }
    }

    private func attemptReconnect() {
        guard !isReconnecting else { return }
        guard reconnectAttemptCount < Self.maxReconnectAttempts else {
            connectionState = .error("Reconnection failed after \(Self.maxReconnectAttempts) attempts")
            lastConnectedDeviceId = nil
            return
        }
        guard let deviceId = lastConnectedDeviceId else { return }

        isReconnecting = true
        reconnectAttemptCount += 1
        let attempt = reconnectAttemptCount
        let delay = Self.reconnectBaseDelaySeconds * pow(2.0, Double(attempt - 1))

        logger.info("Scheduling reconnect attempt \(attempt)/\(Self.maxReconnectAttempts) in \(delay)s")
        connectionState = .connecting

        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }

            await MainActor.run {
                guard let self, self.isReconnecting else { return }
                self.isReconnecting = false

                guard let uuid = UUID(uuidString: deviceId),
                      let peripheral = self.centralManager.retrievePeripherals(withIdentifiers: [uuid]).first else {
                    logger.warning("Cannot find peripheral for reconnect: \(deviceId)")
                    self.attemptReconnect()
                    return
                }

                self.connectedPeripheral = peripheral
                peripheral.delegate = self
                self.centralManager.connect(peripheral, options: [
                    CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
                ])
            }
        }
    }

    private func startKeepalive() {
        keepaliveTask?.cancel()
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.keepaliveIntervalSeconds * 1_000_000_000))
                guard !Task.isCancelled else { break }

                guard let self,
                      let peripheral = self.connectedPeripheral,
                      peripheral.state == .connected,
                      let rx = self.serialRxCharacteristic else {
                    continue
                }

                // Read RSSI as a non-intrusive keepalive probe
                peripheral.readRSSI()
                logger.debug("Keepalive ping sent")
            }
        }
    }

    /// Process incoming data: buffer it and attempt frame reassembly.
    private func processIncomingData(_ data: Data) {
        // Forward raw data to the protocol handler
        onDataReceived?(data)

        // Also attempt frame reassembly for pending framed operations
        dataBuffer.append(data)
        attemptFrameReassembly()
    }

    private func attemptFrameReassembly() {
        while dataBuffer.count >= 4 {
            if expectedFrameLength == nil {
                let length = dataBuffer.withUnsafeBytes { ptr in
                    ptr.loadUnaligned(as: UInt32.self)
                }
                let frameLen = Int(UInt32(littleEndian: length))

                guard frameLen > 0, frameLen <= 256 * 1024 else {
                    // Invalid frame length -- likely not a framed response
                    dataBuffer.removeAll()
                    return
                }
                expectedFrameLength = frameLen
            }

            guard let expected = expectedFrameLength else { return }

            // Check if we have the full frame (4 byte header + payload)
            guard dataBuffer.count >= 4 + expected else {
                return // Wait for more data
            }

            let frameData = dataBuffer[4..<(4 + expected)]
            dataBuffer.removeSubrange(0..<(4 + expected))
            expectedFrameLength = nil

            // Complete the oldest pending operation
            if let (id, continuation) = pendingOperations.first {
                pendingOperations.removeValue(forKey: id)
                continuation.resume(returning: Data(frameData))
            }
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension FlipperBLEManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("Bluetooth state changed: \(central.state.rawValue)")

        switch central.state {
        case .poweredOn:
            if connectionState == .error("Bluetooth is turned off") ||
               connectionState == .error("Bluetooth permission denied") {
                connectionState = .disconnected
            }
        case .poweredOff:
            cleanupConnection()
            connectionState = .error("Bluetooth is turned off")
        case .unauthorized:
            cleanupConnection()
            connectionState = .error("Bluetooth permission denied")
        case .unsupported:
            connectionState = .error("Bluetooth LE not supported")
        case .resetting:
            cleanupConnection()
            connectionState = .disconnected
        case .unknown:
            break
        @unknown default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let deviceId = peripheral.identifier.uuidString
        let deviceName = peripheral.name
            ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
            ?? "Flipper (\(deviceId.prefix(8)))"

        let device = FlipperDevice(
            id: deviceId,
            name: deviceName,
            rssi: RSSI.intValue,
            isConnected: false
        )

        if let existingIndex = discoveredDevices.firstIndex(where: { $0.id == deviceId }) {
            discoveredDevices[existingIndex] = device
        } else {
            discoveredDevices.append(device)
            logger.info("Discovered Flipper: \(deviceName) [\(deviceId)] RSSI=\(RSSI.intValue)")
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        logger.info("Connected to peripheral: \(peripheral.identifier.uuidString)")

        reconnectAttemptCount = 0
        isReconnecting = false
        reconnectTask?.cancel()

        // Request MTU negotiation -- CoreBluetooth handles this via maximumWriteValueLength
        let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse)
        negotiatedMTU = max(mtu, Self.defaultATTMTU)
        logger.info("Negotiated MTU: \(self.negotiatedMTU)")

        // Discover services
        peripheral.discoverServices([Self.serialServiceUUID] + Self.scanServiceUUIDs)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let msg = error?.localizedDescription ?? "Unknown error"
        logger.error("Failed to connect: \(msg)")

        if lastConnectedDeviceId != nil {
            attemptReconnect()
        } else {
            connectionState = .error("Connection failed: \(msg)")
            cleanupConnection()
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        logger.info("Disconnected from peripheral (error: \(error?.localizedDescription ?? "none"))")

        let wasConnected = connectedDevice != nil
        cleanupConnection()
        keepaliveTask?.cancel()

        if wasConnected, lastConnectedDeviceId != nil {
            attemptReconnect()
        } else {
            connectionState = .disconnected
        }
    }
}

// MARK: - CBPeripheralDelegate

extension FlipperBLEManager: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            logger.error("Service discovery failed: \(error.localizedDescription)")
            connectionState = .error("Service discovery failed")
            return
        }

        guard let services = peripheral.services else {
            logger.warning("No services found on peripheral")
            connectionState = .error("No compatible services found")
            return
        }

        logger.info("Discovered \(services.count) service(s)")

        for service in services {
            peripheral.discoverCharacteristics(
                [Self.serialTxUUID, Self.serialRxUUID],
                for: service
            )
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error {
            logger.error("Characteristic discovery failed: \(error.localizedDescription)")
            return
        }

        guard let characteristics = service.characteristics else { return }

        for characteristic in characteristics {
            switch characteristic.uuid {
            case Self.serialTxUUID:
                serialTxCharacteristic = characteristic
                logger.info("Found Serial TX characteristic")

            case Self.serialRxUUID:
                serialRxCharacteristic = characteristic
                logger.info("Found Serial RX characteristic")

                // Subscribe to notifications
                peripheral.setNotifyValue(true, for: characteristic)

            default:
                break
            }
        }

        // Check if we have both characteristics
        if serialTxCharacteristic != nil && serialRxCharacteristic != nil {
            finalizeConnection(peripheral: peripheral)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            logger.error("Notification state update failed: \(error.localizedDescription)")
            return
        }

        if characteristic.uuid == Self.serialRxUUID && characteristic.isNotifying {
            notificationsReady = true
            logger.info("Serial RX notifications enabled")
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            logger.error("Characteristic value update error: \(error.localizedDescription)")
            return
        }

        guard characteristic.uuid == Self.serialRxUUID,
              let data = characteristic.value,
              !data.isEmpty else {
            return
        }

        processIncomingData(data)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        // Find and complete the oldest pending write acknowledgment
        if let key = pendingWriteAcks.keys.sorted().first,
           let continuation = pendingWriteAcks.removeValue(forKey: key) {
            if let error {
                continuation.resume(throwing: FlipperBLEError.writeFailed(error.localizedDescription))
            } else {
                continuation.resume()
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        if let error {
            logger.debug("RSSI read error: \(error.localizedDescription)")
            return
        }

        // Update device RSSI
        if var device = connectedDevice {
            device.rssi = RSSI.intValue
            connectedDevice = device
        }
    }

    // MARK: - Connection Finalization

    private func finalizeConnection(peripheral: CBPeripheral) {
        let deviceName = peripheral.name ?? "Flipper Zero"

        connectedDevice = FlipperDevice(
            id: peripheral.identifier.uuidString,
            name: deviceName,
            rssi: 0,
            isConnected: true
        )
        connectionState = .connected

        startKeepalive()
        logger.info("Flipper connection fully established: \(deviceName)")
    }
}

// MARK: - BLE Errors

enum FlipperBLEError: Error, LocalizedError {
    case notConnected
    case disconnected
    case timeout
    case writeTimeout
    case writeFailed(String)
    case bluetoothOff
    case bluetoothUnauthorized
    case serviceNotFound
    case characteristicNotFound

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to Flipper device"
        case .disconnected:
            return "Disconnected from Flipper device"
        case .timeout:
            return "Command timed out"
        case .writeTimeout:
            return "Write acknowledgment timed out"
        case .writeFailed(let reason):
            return "Write failed: \(reason)"
        case .bluetoothOff:
            return "Bluetooth is turned off"
        case .bluetoothUnauthorized:
            return "Bluetooth permission denied"
        case .serviceNotFound:
            return "Flipper serial service not found"
        case .characteristicNotFound:
            return "Flipper serial characteristic not found"
        }
    }
}
