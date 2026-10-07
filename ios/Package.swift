// swift-tools-version: 6.0
import PackageDescription

// LanyardCore is the platform-independent half of the iPhone app: the protocol
// logic that has no Apple-framework dependency and therefore builds and tests on
// Linux (this repository's CI box) as well as on a Mac.
//
// LanyardNet is the Apple-only half: Network.framework, Security.framework and
// the Keychain. Every file there is wrapped in `#if canImport(Network)` /
// `#if canImport(Security)`, so on Linux it compiles to nothing and the Linux
// test run stays green; it is compiled for real only on a Mac. It is "written,
// not compiled" until then.
let package = Package(
    name: "Lanyard",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "LanyardCore", targets: ["LanyardCore"]),
        .library(name: "LanyardNet", targets: ["LanyardNet"]),
        .executable(name: "certgen", targets: ["certgen"]),
    ],
    dependencies: [
        // swift-crypto exposes the CryptoKit API on Linux. Ed25519 and SHA-256
        // are the only primitives the protocol core needs.
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
        // swift-asn1 builds and parses the DER for the self-signed X.509
        // certificate, since Security.framework has no certificate builder.
        .package(url: "https://github.com/apple/swift-asn1.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "LanyardCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto"),
                .product(name: "SwiftASN1", package: "swift-asn1"),
            ]
        ),
        .target(
            name: "LanyardNet",
            dependencies: ["LanyardCore"]
        ),
        // Small helper so the cross-check script can have Swift emit a
        // certificate DER for the Go verifier to parse.
        .executableTarget(
            name: "certgen",
            dependencies: ["LanyardCore"]
        ),
        .testTarget(
            name: "LanyardCoreTests",
            dependencies: ["LanyardCore"]
        ),
    ]
)
