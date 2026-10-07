import Foundation

/// Permissions one side allows the other; mirrors `trust.Permissions` (Go) and
/// the Kotlin `Permissions`. The receive byte limits default to `0` ("no limit /
/// no ask"), which is the safe, non-surprising default for an old entry.
public struct Permissions: Equatable {
    public var browse: Bool
    public var push: Bool
    public var pushMaxBytes: Int64
    public var askOver: Int64

    public init(
        browse: Bool = false,
        push: Bool = false,
        pushMaxBytes: Int64 = 0,
        askOver: Int64 = 0
    ) {
        self.browse = browse
        self.push = push
        self.pushMaxBytes = pushMaxBytes
        self.askOver = askOver
    }

    /// The wire shape for a `requested_permissions`/`granted` object. The two
    /// byte limits are only emitted when positive, exactly as the Kotlin
    /// `toJson` does.
    public func toJSONObject() -> [String: Any] {
        var out: [String: Any] = ["browse": browse, "push": push]
        if pushMaxBytes > 0 { out["push_max_bytes"] = pushMaxBytes }
        if askOver > 0 { out["ask_over"] = askOver }
        return out
    }
}
