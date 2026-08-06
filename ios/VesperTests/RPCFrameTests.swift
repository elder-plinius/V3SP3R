import SwiftProtobuf
import XCTest
@testable import Vesper

final class RPCFrameTests: XCTestCase {
    func testVarintBoundaries() {
        for value in [UInt64(0), 1, 127, 128, 16_384, 1_000_000] {
            let encoded = Varint.encode(value)
            XCTAssertEqual(Varint.decode(encoded)?.value, value)
            XCTAssertEqual(Varint.decode(encoded)?.length, encoded.count)
        }
    }

    func testFragmentedAndMultipleFrames() throws {
        var first = PB_Main(); first.commandID = 7; first.systemPingRequest = PBSystem_PingRequest()
        var second = PB_Main(); second.commandID = 8; second.systemDeviceInfoRequest = PBSystem_DeviceInfoRequest()
        let firstPayload = try first.serializedData()
        let secondPayload = try second.serializedData()
        let wire = Varint.encode(UInt64(firstPayload.count)) + firstPayload + Varint.encode(UInt64(secondPayload.count)) + secondPayload
        var decoder = RPCFrameDecoder()
        XCTAssertTrue(try decoder.append(wire.prefix(2)).isEmpty)
        let decoded = try decoder.append(wire.dropFirst(2))
        XCTAssertEqual(decoded.map(\.commandID), [7, 8])
    }

    func testMalformedOversizedFrameFails() {
        var decoder = RPCFrameDecoder()
        XCTAssertThrowsError(try decoder.append(Varint.encode(11 * 1024 * 1024)))
    }

    func testUnterminatedVarintFailsAfterTenBytes() {
        var decoder = RPCFrameDecoder()
        XCTAssertThrowsError(try decoder.append(Data(repeating: 0x80, count: 10)))
    }

    func testZeroLengthFrameFails() {
        var decoder = RPCFrameDecoder()
        XCTAssertThrowsError(try decoder.append(Data([0])))
    }
}
