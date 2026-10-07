// swift-tools-version: 6.0
import PackageDescription

// LanyardCore is the platform-independent half of the iPhone app: the protocol
// logic that has no Apple-framework dependency and therefore builds and tests on
// Linux (this repository's CI box) as well as on a Mac. The Apple-only half
// (Network.framework, Security.framework, Keychain) lives behind LanyardNet in
// the app target and is deliberately not part of this package.
let package = Package(
    name: "Lanyard",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "LanyardCore", targets: ["LanyardCore"]),
    ],
    dependencies: [
        // swift-crypto exposes the CryptoKit API on Linux. Ed25519 and SHA-256
        // are the only primitives the protocol core needs.
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
    ],
    targets: [
        .target(
            name: "LanyardCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto"),
            ]
        ),
        .testTarget(
            name: "LanyardCoreTests",
            dependencies: ["LanyardCore"]
        ),
    ]
)
