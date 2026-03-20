// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Vesper",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "Vesper",
            targets: ["Vesper"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.25.0"),
    ],
    targets: [
        .target(
            name: "Vesper",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            path: "Sources/Vesper"
        ),
        .testTarget(
            name: "VesperTests",
            dependencies: ["Vesper"],
            path: "Tests"
        )
    ]
)
