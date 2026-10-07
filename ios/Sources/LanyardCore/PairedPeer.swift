import Foundation

/// A peer this device has paired with, keyed by certificate fingerprint.
///
/// Mirrors the Kotlin `PairedPeer`. The two receive-permission fields default
/// to `0` ("no limit / no ask") so a `peers.json` written before they existed
/// still decodes, matching the Kotlin `codec` behaviour.
struct PairedPeer: Codable, Equatable {
    var fingerprint: String
    var name: String
    var host: String
    var port: Int
    var browse: Bool
    var push: Bool
    var pairedAt: Int64
    var pushMaxBytes: Int64
    var askOver: Int64

    init(
        fingerprint: String,
        name: String,
        host: String,
        port: Int,
        browse: Bool,
        push: Bool,
        pairedAt: Int64,
        pushMaxBytes: Int64 = 0,
        askOver: Int64 = 0
    ) {
        self.fingerprint = fingerprint
        self.name = name
        self.host = host
        self.port = port
        self.browse = browse
        self.push = push
        self.pairedAt = pairedAt
        self.pushMaxBytes = pushMaxBytes
        self.askOver = askOver
    }

    private enum CodingKeys: String, CodingKey {
        case fingerprint, name, host, port, browse, push, pairedAt, pushMaxBytes, askOver
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fingerprint = try c.decode(String.self, forKey: .fingerprint)
        name = try c.decode(String.self, forKey: .name)
        host = try c.decode(String.self, forKey: .host)
        port = try c.decode(Int.self, forKey: .port)
        browse = try c.decode(Bool.self, forKey: .browse)
        push = try c.decode(Bool.self, forKey: .push)
        pairedAt = try c.decode(Int64.self, forKey: .pairedAt)
        pushMaxBytes = try c.decodeIfPresent(Int64.self, forKey: .pushMaxBytes) ?? 0
        askOver = try c.decodeIfPresent(Int64.self, forKey: .askOver) ?? 0
    }
}
