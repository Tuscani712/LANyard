import Foundation
import Crypto
import SwiftASN1

/// Errors thrown while building or parsing a certificate.
public enum CertificateError: Error, Equatable {
    case invalidSerial
    case invalidTime(String)
    case malformedCertificate(String)
    case signatureFailed
}

/// A big-endian, unsigned ASN.1 INTEGER stored as its raw magnitude bytes.
///
/// `ASN1IntegerRepresentable` handles the DER rules for us: it re-adds a
/// leading `0x00` when the top bit of the magnitude is set, and strips it again
/// on the way in. That is exactly what RFC 5280 requires for a positive
/// certificate serial, including the high-bit case.
struct ASN1IntegerBytes: ASN1IntegerRepresentable, Hashable, Sendable {
    typealias IntegerBytes = [UInt8]

    /// Big-endian magnitude, minimal (leading zero bytes removed), non-zero.
    var bytes: [UInt8]

    static var isSigned: Bool { false }

    init(bytes: [UInt8]) throws {
        var trimmed = bytes
        while trimmed.count > 1 && trimmed.first == 0 {
            trimmed.removeFirst()
        }
        guard !trimmed.isEmpty, trimmed.contains(where: { $0 != 0 }) else {
            throw CertificateError.invalidSerial
        }
        self.bytes = trimmed
    }

    init(derIntegerBytes: ArraySlice<UInt8>) throws {
        var trimmed = Array(derIntegerBytes)
        while trimmed.count > 1 && trimmed.first == 0 {
            trimmed.removeFirst()
        }
        self.bytes = trimmed
    }

    init(berIntegerBytes: ArraySlice<UInt8>) throws {
        try self.init(derIntegerBytes: berIntegerBytes)
    }

    func withBigEndianIntegerBytes<ReturnType>(
        _ body: ([UInt8]) throws -> ReturnType
    ) rethrows -> ReturnType {
        try body(self.bytes)
    }
}

/// Builds a self-signed Ed25519 X.509 v3 certificate with SwiftASN1 and signs
/// its TBS with swift-crypto. This is the Swift equivalent of Go's
/// `x509.CreateCertificate` with the `internal/identity` template:
///
/// * subject CN = `"LANyard <deviceName>"`
/// * KeyUsage = digitalSignature (critical)
/// * ExtendedKeyUsage = serverAuth + clientAuth (non-critical)
/// * BasicConstraints CA=false (critical)
/// * signature algorithm Ed25519 (OID 1.3.101.112, absent parameters)
/// * v3, validity exactly as supplied
///
/// No Security framework anywhere: this compiles and runs on Linux.
public struct CertificateBuilder {
    public static let signatureAlgorithmOID: ASN1ObjectIdentifier = [1, 3, 101, 112]
    public static let commonNameOID: ASN1ObjectIdentifier = [2, 5, 4, 3]
    public static let keyUsageOID: ASN1ObjectIdentifier = [2, 5, 29, 15]
    public static let extendedKeyUsageOID: ASN1ObjectIdentifier = [2, 5, 29, 37]
    public static let basicConstraintsOID: ASN1ObjectIdentifier = [2, 5, 29, 19]
    public static let serverAuthOID: ASN1ObjectIdentifier = [1, 3, 6, 1, 5, 5, 7, 3, 1]
    public static let clientAuthOID: ASN1ObjectIdentifier = [1, 3, 6, 1, 5, 5, 7, 3, 2]

    /// Build a certificate from a raw 32-byte Ed25519 seed.
    public static func build(
        deviceName: String,
        seed: [UInt8],
        serial: [UInt8],
        notBefore: Date,
        notAfter: Date
    ) throws -> [UInt8] {
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        return try build(
            deviceName: deviceName,
            privateKey: key,
            serial: serial,
            notBefore: notBefore,
            notAfter: notAfter
        )
    }

    /// Build a certificate from an Ed25519 private key.
    public static func build(
        deviceName: String,
        privateKey: Curve25519.Signing.PrivateKey,
        serial: [UInt8],
        notBefore: Date,
        notAfter: Date
    ) throws -> [UInt8] {
        try build(
            deviceName: deviceName,
            rawPublicKey: Array(privateKey.publicKey.rawRepresentation),
            serial: serial,
            notBefore: notBefore,
            notAfter: notAfter,
            sign: { Array(try privateKey.signature(for: Data($0))) }
        )
    }

    /// The fixed Ed25519 SubjectPublicKeyInfo for a raw 32-byte public key: the
    /// 12-byte prefix `302a300506032b6570032100` followed by the key.
    public static func subjectPublicKeyInfo(publicKeyRaw: [UInt8]) -> [UInt8] {
        Fingerprint.ed25519SPKIPrefix + publicKeyRaw
    }

    /// Build and sign a certificate with an external signer. Used on Apple
    /// platforms, where the Ed25519 key lives in the Keychain as a `SecKey`
    /// (Security.framework) rather than as a swift-crypto private key; the
    /// `sign` closure receives the tbsCertificate DER and returns the signature.
    public static func selfSignedCertificate(
        publicKeyRaw: [UInt8],
        subject: String,
        serialNumber: [UInt8],
        notBefore: Date,
        notAfter: Date,
        sign: ([UInt8]) throws -> [UInt8]
    ) throws -> [UInt8] {
        try build(
            deviceName: subject,
            rawPublicKey: publicKeyRaw,
            serial: serialNumber,
            notBefore: notBefore,
            notAfter: notAfter,
            sign: sign
        )
    }

    /// Core builder: takes the raw public key and a signing closure.
    public static func build(
        deviceName: String,
        rawPublicKey: [UInt8],
        serial: [UInt8],
        notBefore: Date,
        notAfter: Date,
        sign: ([UInt8]) throws -> [UInt8]
    ) throws -> [UInt8] {
        let commonName = "LANyard " + deviceName
        let serialInteger = try ASN1IntegerBytes(bytes: serial)

        let spki = subjectPublicKeyInfo(publicKeyRaw: rawPublicKey)

        // --- tbsCertificate ---------------------------------------------------
        var tbs = DER.Serializer()
        try tbs.appendConstructedNode(identifier: .sequence) { coder in
            // version [0] EXPLICIT INTEGER 2  (v3)
            try coder.serialize(explicitlyTaggedWithTagNumber: 0, tagClass: .contextSpecific) { versionCoder in
                try versionCoder.serialize(2)
            }

            // serialNumber INTEGER
            try coder.serialize(serialInteger)

            // signature AlgorithmIdentifier (Ed25519, absent parameters)
            try Self.serializeAlgorithmIdentifier(into: &coder)

            // issuer Name == subject Name (self-signed)
            try Self.serializeName(commonName, into: &coder)

            // validity SEQUENCE { notBefore, notAfter }
            try coder.appendConstructedNode(identifier: .sequence) { validityCoder in
                try Self.serializeTime(notBefore, into: &validityCoder)
                try Self.serializeTime(notAfter, into: &validityCoder)
            }

            // subject Name
            try Self.serializeName(commonName, into: &coder)

            // subjectPublicKeyInfo
            coder.serializeRawBytes(spki)

            // extensions [3] EXPLICIT SEQUENCE OF Extension
            try coder.serialize(explicitlyTaggedWithTagNumber: 3, tagClass: .contextSpecific) { extWrapper in
                try extWrapper.appendConstructedNode(identifier: .sequence) { extensions in
                    try Self.serializeExtension(
                        oid: Self.keyUsageOID,
                        critical: true,
                        extnValue: Self.keyUsageValue(),
                        into: &extensions
                    )
                    try Self.serializeExtension(
                        oid: Self.extendedKeyUsageOID,
                        critical: false,
                        extnValue: try Self.extendedKeyUsageValue(),
                        into: &extensions
                    )
                    try Self.serializeExtension(
                        oid: Self.basicConstraintsOID,
                        critical: true,
                        extnValue: Self.basicConstraintsValue(ca: false),
                        into: &extensions
                    )
                }
            }
        }
        let tbsDER = tbs.serializedBytes

        // --- Certificate SEQUENCE --------------------------------------------
        let signature = try sign(tbsDER)

        var certificate = DER.Serializer()
        try certificate.appendConstructedNode(identifier: .sequence) { coder in
            coder.serializeRawBytes(tbsDER)
            try Self.serializeAlgorithmIdentifier(into: &coder)
            try coder.serialize(ASN1BitString(bytes: ArraySlice(signature), paddingBits: 0))
        }
        return certificate.serializedBytes
    }

    // MARK: - DER helpers

    static func serializeAlgorithmIdentifier(into coder: inout DER.Serializer) throws {
        try coder.appendConstructedNode(identifier: .sequence) { algorithm in
            try algorithm.serialize(signatureAlgorithmOID)
        }
    }

    static func serializeName(_ commonName: String, into coder: inout DER.Serializer) throws {
        try coder.appendConstructedNode(identifier: .sequence) { name in
            // One RDN: SET { SEQUENCE { OID 2.5.4.3, UTF8String CN } }
            try name.appendConstructedNode(identifier: .set) { rdn in
                try rdn.appendConstructedNode(identifier: .sequence) { attribute in
                    try attribute.serialize(commonNameOID)
                    try attribute.serialize(ASN1UTF8String(commonName))
                }
            }
        }
    }

    static func serializeTime(_ date: Date, into coder: inout DER.Serializer) throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        guard let year = parts.year, let month = parts.month, let day = parts.day,
              let hour = parts.hour, let minute = parts.minute, let second = parts.second
        else {
            throw CertificateError.invalidTime("could not decompose \(date)")
        }

        if (1950..<2050).contains(year) {
            try coder.serialize(
                try UTCTime(year: year, month: month, day: day, hours: hour, minutes: minute, seconds: second)
            )
        } else {
            try coder.serialize(
                try GeneralizedTime(
                    year: year, month: month, day: day, hours: hour, minutes: minute, seconds: second,
                    fractionalSeconds: 0
                )
            )
        }
    }

    static func serializeExtension(
        oid: ASN1ObjectIdentifier,
        critical: Bool,
        extnValue: [UInt8],
        into coder: inout DER.Serializer
    ) throws {
        try coder.appendConstructedNode(identifier: .sequence) { ext in
            try ext.serialize(oid)
            if critical {
                try ext.serialize(true)
            }
            try ext.serialize(ASN1OctetString(contentBytes: ArraySlice(extnValue)))
        }
    }

    /// DER for KeyUsage digitalSignature: `03 02 07 80`.
    static func keyUsageValue() -> [UInt8] {
        var value = DER.Serializer()
        do {
            try value.serialize(ASN1BitString(bytes: [0x80][...], paddingBits: 7))
        } catch {
            preconditionFailure("key usage encoding is static and cannot fail: \(error)")
        }
        return value.serializedBytes
    }

    /// DER for ExtendedKeyUsage: SEQUENCE { serverAuth, clientAuth }.
    static func extendedKeyUsageValue() throws -> [UInt8] {
        var value = DER.Serializer()
        try value.serializeSequenceOf([serverAuthOID, clientAuthOID])
        return value.serializedBytes
    }

    /// DER for BasicConstraints: `SEQUENCE { }` when CA is false.
    static func basicConstraintsValue(ca: Bool) -> [UInt8] {
        var value = DER.Serializer()
        do {
            try value.appendConstructedNode(identifier: .sequence) { sequence in
                if ca {
                    try sequence.serialize(true)
                }
            }
        } catch {
            preconditionFailure("basic constraints encoding is static and cannot fail: \(error)")
        }
        return value.serializedBytes
    }
}

/// The interesting pieces of a parsed X.509 certificate, sufficient for the
/// Linux tests to assert without hand-typed DER.
public struct ParsedCertificate: Equatable {
    public let der: [UInt8]
    public let tbsDER: [UInt8]
    public let serial: [UInt8]
    public let serialDER: [UInt8]
    public let signature: [UInt8]
    public let signatureAlgorithmOID: String
    public let spkiDER: [UInt8]
    public let publicKey: [UInt8]
    public let subjectCN: String
    public let notBefore: Date
    public let notAfter: Date
    public let extKeyUsage: [String]
    public let keyUsageDigitalSignature: Bool
    public let basicConstraintsCA: Bool
}

/// A minimal RFC 5280 parser built on SwiftASN1, covering exactly the fields
/// the builder emits (and the Go fixture uses).
public struct CertificateParser {
    public static func parse(_ der: [UInt8]) throws -> ParsedCertificate {
        let root = try DER.parse(der)
        let certificateFields = try children(of: root)
        guard certificateFields.count == 3 else {
            throw CertificateError.malformedCertificate("Certificate must have 3 fields, got \(certificateFields.count)")
        }
        let tbsNode = certificateFields[0]
        let signatureAlgorithmNode = certificateFields[1]
        let signatureNode = certificateFields[2]

        let tbsDER = reserialize(tbsNode)
        let signature = Array(try ASN1BitString(derEncoded: signatureNode).bytes)
        let signatureAlgorithmOID = try firstOID(in: signatureAlgorithmNode)

        var tbsFields = try children(of: tbsNode)

        // version [0] EXPLICIT INTEGER (optional)
        if let first = tbsFields.first, first.identifier.tagClass == .contextSpecific,
           first.identifier.tagNumber == 0 {
            tbsFields.removeFirst()
        }

        guard tbsFields.count >= 7 else {
            throw CertificateError.malformedCertificate("TBSCertificate too short: \(tbsFields.count)")
        }

        let serialNode = tbsFields.removeFirst()
        let serialInteger = try ASN1IntegerBytes(derEncoded: serialNode)
        let serialDER = reserialize(serialNode)

        // signature AlgorithmIdentifier
        _ = tbsFields.removeFirst()
        // issuer Name
        _ = tbsFields.removeFirst()
        // validity
        let validityNode = tbsFields.removeFirst()
        // subject Name
        let subjectNode = tbsFields.removeFirst()
        // subjectPublicKeyInfo
        let spkiNode = tbsFields.removeFirst()

        let validityFields = try children(of: validityNode)
        guard validityFields.count == 2 else {
            throw CertificateError.malformedCertificate("Validity must have two times")
        }
        let notBefore = try date(from: validityFields[0])
        let notAfter = try date(from: validityFields[1])

        let subjectCN = try commonName(in: subjectNode)

        let spkiDER = reserialize(spkiNode)
        let publicKey: [UInt8]
        if spkiDER.count >= 32 {
            publicKey = Array(spkiDER.suffix(32))
        } else {
            throw CertificateError.malformedCertificate("SPKI too short")
        }

        // extensions [3] EXPLICIT
        var extKeyUsage: [String] = []
        var keyUsageDigitalSignature = false
        var basicConstraintsCA = false
        if let extensionsNode = tbsFields.first,
           extensionsNode.identifier.tagClass == .contextSpecific,
           extensionsNode.identifier.tagNumber == 3 {
            let wrapper = try children(of: extensionsNode)
            guard let extensionsSequence = wrapper.first else {
                throw CertificateError.malformedCertificate("empty extensions wrapper")
            }
            for extensionNode in try children(of: extensionsSequence) {
                let fields = try children(of: extensionNode)
                guard fields.count >= 2 else { continue }
                let oid = try ASN1ObjectIdentifier(derEncoded: fields[0])
                // The OCTET STRING is the last field (critical BOOLEAN is optional).
                guard let valueNode = fields.last else { continue }
                let value = Array(try ASN1OctetString(derEncoded: valueNode).bytes)

                if oid == CertificateBuilder.extendedKeyUsageOID {
                    let sequence = try DER.parse(value)
                    for oidNode in try children(of: sequence) {
                        extKeyUsage.append(try ASN1ObjectIdentifier(derEncoded: oidNode).description)
                    }
                } else if oid == CertificateBuilder.keyUsageOID {
                    let bitString = try ASN1BitString(derEncoded: DER.parse(value))
                    if let firstByte = bitString.bytes.first {
                        keyUsageDigitalSignature = (firstByte & 0x80) != 0
                    }
                } else if oid == CertificateBuilder.basicConstraintsOID {
                    let sequence = try DER.parse(value)
                    let sequenceFields = try children(of: sequence)
                    if let first = sequenceFields.first {
                        basicConstraintsCA = try Bool(derEncoded: first)
                    } else {
                        basicConstraintsCA = false
                    }
                }
            }
        }

        return ParsedCertificate(
            der: der,
            tbsDER: tbsDER,
            serial: serialInteger.bytes,
            serialDER: serialDER,
            signature: signature,
            signatureAlgorithmOID: signatureAlgorithmOID,
            spkiDER: spkiDER,
            publicKey: publicKey,
            subjectCN: subjectCN,
            notBefore: notBefore,
            notAfter: notAfter,
            extKeyUsage: extKeyUsage,
            keyUsageDigitalSignature: keyUsageDigitalSignature,
            basicConstraintsCA: basicConstraintsCA
        )
    }

    // MARK: - helpers

    static func children(of node: ASN1Node) throws -> [ASN1Node] {
        guard case .constructed(let nodes) = node.content else {
            throw CertificateError.malformedCertificate("expected constructed node")
        }
        return Array(nodes)
    }

    static func reserialize(_ node: ASN1Node) -> [UInt8] {
        var serializer = DER.Serializer()
        serializer.serialize(node)
        return serializer.serializedBytes
    }

    static func firstOID(in node: ASN1Node) throws -> String {
        let fields = try children(of: node)
        guard let oidNode = fields.first else {
            throw CertificateError.malformedCertificate("expected OID")
        }
        return try ASN1ObjectIdentifier(derEncoded: oidNode).description
    }

    static func date(from node: ASN1Node) throws -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        var components = DateComponents()

        if node.identifier == .utcTime {
            let time = try UTCTime(derEncoded: node)
            components.year = time.year
            components.month = time.month
            components.day = time.day
            components.hour = time.hours
            components.minute = time.minutes
            components.second = time.seconds
        } else if node.identifier == .generalizedTime {
            let time = try GeneralizedTime(derEncoded: node)
            components.year = time.year
            components.month = time.month
            components.day = time.day
            components.hour = time.hours
            components.minute = time.minutes
            components.second = time.seconds
        } else {
            throw CertificateError.invalidTime("unexpected time tag \(node.identifier)")
        }

        guard let date = calendar.date(from: components) else {
            throw CertificateError.invalidTime("invalid components")
        }
        return date
    }

    static func commonName(in nameNode: ASN1Node) throws -> String {
        for rdn in try children(of: nameNode) {
            for attribute in try children(of: rdn) {
                let fields = try children(of: attribute)
                guard fields.count == 2 else { continue }
                let oid = try ASN1ObjectIdentifier(derEncoded: fields[0])
                guard oid == CertificateBuilder.commonNameOID else { continue }
                guard case .primitive(let bytes) = fields[1].content else { continue }
                return String(decoding: bytes, as: UTF8.self)
            }
        }
        throw CertificateError.malformedCertificate("no common name in subject")
    }
}
