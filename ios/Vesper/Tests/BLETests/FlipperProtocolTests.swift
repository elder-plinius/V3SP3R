// FlipperProtocolTests.swift
// Vesper - Unit tests for FlipperProtocol wire format encoding
//
// Since FlipperProtocol requires a FlipperBLEManager (CoreBluetooth) to initialize,
// we test the protobuf wire format encoding logic independently by reimplementing
// the pure encoding functions and verifying byte-level correctness.

import XCTest
@testable import Vesper

final class FlipperProtocolTests: XCTestCase {

    // MARK: - Protobuf Encoding Helpers (mirrors FlipperProtocol's private methods)

    /// Encode an unsigned varint (LEB128).
    private func encodeVarint(_ value: UInt64) -> Data {
        var data = Data()
        var v = value
        while v > 0x7F {
            data.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        data.append(UInt8(v & 0x7F))
        if data.isEmpty { data.append(0) }
        return data
    }

    /// Encode a protobuf field tag: (field_number << 3) | wire_type
    private func encodeTag(fieldNumber: UInt32, wireType: UInt8) -> Data {
        return encodeVarint(UInt64(fieldNumber) << 3 | UInt64(wireType))
    }

    /// Encode a varint field (wire type 0).
    private func encodeVarintField(fieldNumber: UInt32, value: UInt64) -> Data {
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 0))
        data.append(encodeVarint(value))
        return data
    }

    /// Encode a string field (wire type 2: length-delimited).
    private func encodeStringField(fieldNumber: UInt32, value: String) -> Data {
        let stringBytes = value.data(using: .utf8) ?? Data()
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 2))
        data.append(encodeVarint(UInt64(stringBytes.count)))
        data.append(stringBytes)
        return data
    }

    /// Encode a length-delimited sub-message field (wire type 2).
    private func encodeLengthDelimitedField(fieldNumber: UInt32, value: Data) -> Data {
        var data = Data()
        data.append(encodeTag(fieldNumber: fieldNumber, wireType: 2))
        data.append(encodeVarint(UInt64(value.count)))
        data.append(value)
        return data
    }

    /// Wrap data with a 4-byte little-endian length prefix.
    private func wrapWithLengthPrefix(_ data: Data) -> Data {
        var frame = Data()
        frame.append(contentsOf: withUnsafeBytes(of: UInt32(data.count).littleEndian) { Data($0) })
        frame.append(data)
        return frame
    }

    /// Build a Main message with command_id = 1 and content field.
    /// Uses command_id = 1 to match the first request ID.
    private func buildMainMessage(commandId: UInt32, contentFieldNumber: UInt32, contentMessage: Data) -> Data {
        var message = Data()
        // Field 1: command_id (varint)
        message.append(encodeVarintField(fieldNumber: 1, value: UInt64(commandId)))
        // Content field (length-delimited sub-message)
        if !contentMessage.isEmpty {
            message.append(encodeLengthDelimitedField(fieldNumber: contentFieldNumber, value: contentMessage))
        } else {
            message.append(encodeTag(fieldNumber: contentFieldNumber, wireType: 2))
            message.append(encodeVarint(0))
        }
        return message
    }

    // MARK: - Frame Length Encoding Tests

    func testLengthPrefixLittleEndian() {
        let payload = Data([0x01, 0x02, 0x03])
        let frame = wrapWithLengthPrefix(payload)

        // First 4 bytes should be length 3 in little-endian
        XCTAssertEqual(frame.count, 4 + 3)
        XCTAssertEqual(frame[0], 0x03) // least significant byte
        XCTAssertEqual(frame[1], 0x00)
        XCTAssertEqual(frame[2], 0x00)
        XCTAssertEqual(frame[3], 0x00)
        // Payload follows
        XCTAssertEqual(frame[4], 0x01)
        XCTAssertEqual(frame[5], 0x02)
        XCTAssertEqual(frame[6], 0x03)
    }

    func testLengthPrefixLargerPayload() {
        let payload = Data(repeating: 0xAA, count: 300)
        let frame = wrapWithLengthPrefix(payload)

        XCTAssertEqual(frame.count, 4 + 300)
        // 300 = 0x012C in little-endian: 0x2C, 0x01, 0x00, 0x00
        XCTAssertEqual(frame[0], 0x2C)
        XCTAssertEqual(frame[1], 0x01)
        XCTAssertEqual(frame[2], 0x00)
        XCTAssertEqual(frame[3], 0x00)
    }

    func testLengthPrefixEmptyPayload() {
        let payload = Data()
        let frame = wrapWithLengthPrefix(payload)

        XCTAssertEqual(frame.count, 4)
        XCTAssertEqual(frame[0], 0x00)
        XCTAssertEqual(frame[1], 0x00)
        XCTAssertEqual(frame[2], 0x00)
        XCTAssertEqual(frame[3], 0x00)
    }

    // MARK: - Varint Encoding Tests

    func testVarintZero() {
        let encoded = encodeVarint(0)
        XCTAssertEqual(encoded, Data([0x00]))
    }

    func testVarintSmallValue() {
        let encoded = encodeVarint(1)
        XCTAssertEqual(encoded, Data([0x01]))
    }

    func testVarintMaxSingleByte() {
        // 127 fits in one byte
        let encoded = encodeVarint(127)
        XCTAssertEqual(encoded, Data([0x7F]))
    }

    func testVarintTwoBytes() {
        // 128 requires two bytes: 0x80 0x01
        let encoded = encodeVarint(128)
        XCTAssertEqual(encoded, Data([0x80, 0x01]))
    }

    func testVarint300() {
        // 300 = 0b100101100
        // First 7 bits: 0101100 = 0x2C, with continuation bit = 0xAC
        // Next 7 bits: 0000010 = 0x02
        let encoded = encodeVarint(300)
        XCTAssertEqual(encoded, Data([0xAC, 0x02]))
    }

    func testVarintLargeValue() {
        // 16384 = 0x4000
        // 7 bits each: 0000000, 0000000, 0000001
        let encoded = encodeVarint(16384)
        XCTAssertEqual(encoded, Data([0x80, 0x80, 0x01]))
    }

    // MARK: - String Field Encoding Tests

    func testStringFieldEncoding() {
        // Field 1, wire type 2 -> tag = (1 << 3) | 2 = 0x0A
        let encoded = encodeStringField(fieldNumber: 1, value: "abc")
        // Expected: tag(0x0A) + length(0x03) + "abc"
        XCTAssertEqual(encoded[0], 0x0A) // tag
        XCTAssertEqual(encoded[1], 0x03) // length
        XCTAssertEqual(String(data: encoded[2...], encoding: .utf8), "abc")
    }

    func testStringFieldEmptyString() {
        let encoded = encodeStringField(fieldNumber: 1, value: "")
        // tag(0x0A) + length(0x00)
        XCTAssertEqual(encoded.count, 2)
        XCTAssertEqual(encoded[0], 0x0A)
        XCTAssertEqual(encoded[1], 0x00)
    }

    func testStringFieldHigherFieldNumber() {
        // Field 20, wire type 2 -> tag = (20 << 3) | 2 = 162 = 0xA2
        // 162 > 127, so varint = 0xA2 0x01
        let encoded = encodeStringField(fieldNumber: 20, value: "x")
        XCTAssertEqual(encoded[0], 0xA2)
        XCTAssertEqual(encoded[1], 0x01)
        XCTAssertEqual(encoded[2], 0x01) // length
        XCTAssertEqual(encoded[3], 0x78) // 'x' = 0x78
    }

    func testStringFieldWithPath() {
        let path = "/ext/subghz"
        let encoded = encodeStringField(fieldNumber: 1, value: path)
        let pathBytes = path.data(using: .utf8)!
        // tag + length varint + path bytes
        XCTAssertEqual(encoded[0], 0x0A) // tag for field 1, wire type 2
        XCTAssertEqual(Int(encoded[1]), pathBytes.count)
        XCTAssertEqual(encoded.suffix(pathBytes.count), pathBytes)
    }

    // MARK: - StorageListRequest Frame Tests

    func testBuildStorageListRequestStructure() {
        // A StorageListRequest for "/ext" should produce:
        // Main { command_id = 1, StorageListRequest(field 20) { path(field 1) = "/ext" } }
        let path = "/ext"
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        let message = buildMainMessage(commandId: 1, contentFieldNumber: 20, contentMessage: innerMessage)

        // Verify the message is non-empty and starts with the command_id field
        XCTAssertGreaterThan(message.count, 0)

        // First field should be command_id = 1 (field 1, wire type 0)
        // Tag for field 1 varint = 0x08, value = 0x01
        XCTAssertEqual(message[0], 0x08) // tag: (1 << 3) | 0
        XCTAssertEqual(message[1], 0x01) // varint value 1

        // Next should be field 20 (StorageListRequest), wire type 2
        // Tag = (20 << 3) | 2 = 162 -> varint 0xA2 0x01
        XCTAssertEqual(message[2], 0xA2)
        XCTAssertEqual(message[3], 0x01)
    }

    func testBuildStorageListRequestContainsPath() {
        let path = "/ext/nfc"
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        let message = buildMainMessage(commandId: 1, contentFieldNumber: 20, contentMessage: innerMessage)

        // The path bytes should be present in the message
        let pathData = path.data(using: .utf8)!
        XCTAssertTrue(message.range(of: pathData) != nil, "Message should contain path bytes")
    }

    // MARK: - StorageReadRequest Frame Tests

    func testBuildStorageReadRequestStructure() {
        // StorageReadRequest is field 21 in Main
        let path = "/ext/test.txt"
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        let message = buildMainMessage(commandId: 1, contentFieldNumber: 21, contentMessage: innerMessage)

        XCTAssertGreaterThan(message.count, 0)
        // command_id tag
        XCTAssertEqual(message[0], 0x08)
        XCTAssertEqual(message[1], 0x01)

        // Field 21 tag: (21 << 3) | 2 = 170 -> varint 0xAA 0x01
        XCTAssertEqual(message[2], 0xAA)
        XCTAssertEqual(message[3], 0x01)

        // Path should be embedded
        let pathData = path.data(using: .utf8)!
        XCTAssertTrue(message.range(of: pathData) != nil)
    }

    // MARK: - SystemInfoRequest Frame Tests

    func testBuildSystemInfoRequestStructure() {
        // SystemDeviceInfoRequest is field 32 in Main, with empty content
        let message = buildMainMessage(commandId: 1, contentFieldNumber: 32, contentMessage: Data())

        XCTAssertGreaterThan(message.count, 0)
        // command_id
        XCTAssertEqual(message[0], 0x08)
        XCTAssertEqual(message[1], 0x01)

        // Field 32 tag: (32 << 3) | 2 = 258
        // 258 as varint: 258 & 0x7F = 0x02, with continuation: 0x82, 0x02
        XCTAssertEqual(message[2], 0x82)
        XCTAssertEqual(message[3], 0x02)

        // Length should be 0 (empty content message)
        XCTAssertEqual(message[4], 0x00)
    }

    // MARK: - Varint Field Encoding Tests

    func testVarintFieldEncoding() {
        // Field 1, wire type 0, value 42
        let encoded = encodeVarintField(fieldNumber: 1, value: 42)
        XCTAssertEqual(encoded[0], 0x08) // tag: (1 << 3) | 0
        XCTAssertEqual(encoded[1], 42)   // value
    }

    func testVarintFieldWithLargerFieldNumber() {
        // Field 3, wire type 0, value 1
        let encoded = encodeVarintField(fieldNumber: 3, value: 1)
        XCTAssertEqual(encoded[0], 0x18) // tag: (3 << 3) | 0 = 24
        XCTAssertEqual(encoded[1], 0x01)
    }

    // MARK: - Length-Delimited Field Tests

    func testLengthDelimitedFieldEncoding() {
        let innerData = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let encoded = encodeLengthDelimitedField(fieldNumber: 2, value: innerData)
        // Tag: (2 << 3) | 2 = 18 = 0x12
        XCTAssertEqual(encoded[0], 0x12)
        // Length: 4
        XCTAssertEqual(encoded[1], 0x04)
        // Payload
        XCTAssertEqual(encoded[2], 0xDE)
        XCTAssertEqual(encoded[3], 0xAD)
        XCTAssertEqual(encoded[4], 0xBE)
        XCTAssertEqual(encoded[5], 0xEF)
    }

    // MARK: - Full Frame Round-Trip Tests

    func testFullFrameWithLengthPrefix() {
        let path = "/ext"
        let innerMessage = encodeStringField(fieldNumber: 1, value: path)
        let mainMessage = buildMainMessage(commandId: 1, contentFieldNumber: 20, contentMessage: innerMessage)
        let frame = wrapWithLengthPrefix(mainMessage)

        // Extract length from first 4 bytes
        let lengthBytes = frame.prefix(4)
        let length = lengthBytes.withUnsafeBytes { $0.load(as: UInt32.self) }
        let expectedLength = UInt32(mainMessage.count).littleEndian

        XCTAssertEqual(length, expectedLength)
        XCTAssertEqual(frame.count, Int(length) + 4)

        // Remaining bytes should be the main message
        let payload = frame.suffix(from: 4)
        XCTAssertEqual(Data(payload), mainMessage)
    }

    func testMultipleFieldsInMessage() {
        // Simulate a move/rename request: field 1 = old_path, field 2 = new_path
        var innerMessage = Data()
        innerMessage.append(encodeStringField(fieldNumber: 1, value: "/ext/old.txt"))
        innerMessage.append(encodeStringField(fieldNumber: 2, value: "/ext/new.txt"))

        // Field 26 = StorageRenameRequest
        let message = buildMainMessage(commandId: 1, contentFieldNumber: 26, contentMessage: innerMessage)

        // Verify both paths are present
        let oldPathData = "/ext/old.txt".data(using: .utf8)!
        let newPathData = "/ext/new.txt".data(using: .utf8)!
        XCTAssertTrue(message.range(of: oldPathData) != nil, "Should contain old path")
        XCTAssertTrue(message.range(of: newPathData) != nil, "Should contain new path")
    }
}
