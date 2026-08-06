// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "VesperCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "VesperCore", targets: ["VesperCore"])
    ],
    targets: [
        .target(name: "VesperCore"),
        .testTarget(name: "VesperCoreTests", dependencies: ["VesperCore"])
    ],
    swiftLanguageModes: [.v6]
)
