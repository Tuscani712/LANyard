import Foundation

/// A destination folder for received files: returns a writable directory URL, or
/// `nil` when the device cannot save there.
///
/// This is the seam the platform supplies. iOS has two implementations:
/// `DefaultReceiveDestination` (the app's Documents directory) and
/// `BookmarkReceiveDestination` (a user-picked folder, resolved from an opaque
/// security-scoped bookmark). Mirrors the Android `PushDestination`'s "where do
/// downloads go" concept, but a Swift implementation only has to say *where*.
public protocol ReceiveDestination {
    func writableRoot() -> URL?
}

/// The default receive destination: `<Documents>/LANyard`, created on first use.
///
/// Adapted from the Android default (`Downloads/LANyard`) to the iOS sandbox,
/// where the app's Documents directory is the Files-app-visible location.
public struct DefaultReceiveDestination: ReceiveDestination {
    /// The folder shown under Documents.
    public static let folderName = "LANyard"

    private let documents: () -> URL?

    public init(
        documents: @escaping () -> URL? = {
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        }
    ) {
        self.documents = documents
    }

    public func writableRoot() -> URL? {
        guard let docs = documents() else { return nil }
        let root = docs.appendingPathComponent(Self.folderName, isDirectory: true)
        if !InboxDestination.isWritableDirectory(root) {
            try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        }
        return InboxDestination.isWritableDirectory(root) ? root : nil
    }
}

/// A user-picked override, held as an **opaque bookmark token**. The core never
/// stores a path: the app resolves the token (for example with
/// `URL(resolvingBookmarkData:...)`) and answers with a URL. A token that does
/// not resolve, or resolves somewhere unwritable, behaves as "no override".
public struct BookmarkReceiveDestination: ReceiveDestination {
    public let token: Data
    private let resolve: (Data) -> URL?

    public init(token: Data, resolve: @escaping (Data) -> URL?) {
        self.token = token
        self.resolve = resolve
    }

    public func writableRoot() -> URL? {
        guard !token.isEmpty, let url = resolve(token) else { return nil }
        return InboxDestination.isWritableDirectory(url) ? url : nil
    }
}

/// A readable failure from the destination layer.
public struct ReceiveDestinationError: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Places a verified, spooled file into the resolved destination, recreating any
/// relative sub-folders and renaming a collision to `name (1).ext`.
///
/// Ported from the Android `SafInboxDestination` / `defaultInboxDestination`
/// logic. The message a person sees when no folder is writable is fixed here.
public struct InboxDestination: PushDestination {
    /// Shown when the resolved root is missing or unwritable.
    public static let refusalMessage =
        "This device cannot save received files. Choose a writable download folder in Settings."

    private let destination: ReceiveDestination
    private let fileManager: FileManager

    public init(destination: ReceiveDestination = DefaultReceiveDestination(), fileManager: FileManager = .default) {
        self.destination = destination
        self.fileManager = fileManager
    }

    /// The resolved folder, or `nil` when none is writable.
    public func writableRoot() -> URL? {
        destination.writableRoot()
    }

    /// Whether `url` exists, is a directory, and can be written to.
    public static func isWritableDirectory(_ url: URL?) -> Bool {
        guard let url else { return false }
        var isDir: ObjCBool = false
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else { return false }
        return fm.isWritableFile(atPath: url.path)
    }

    /// `name (1).ext` before the extension, or `name` when free. Mirrors the
    /// Kotlin `SafInboxDestination.uniqueName`: a leading dot does not count as
    /// an extension separator.
    public static func uniqueName(_ name: String, exists: (String) -> Bool) -> String {
        if !exists(name) { return name }
        let dot = name.lastIndex(of: ".")
        let base: String
        let ext: String
        if let dot, dot != name.startIndex {
            base = String(name[..<dot])
            ext = String(name[dot...])
        } else {
            base = name
            ext = ""
        }
        var n = 1
        while exists("\(base) (\(n))\(ext)") { n += 1 }
        return "\(base) (\(n))\(ext)"
    }

    // MARK: - PushDestination

    @discardableResult
    public func place(relPath: String, spool: URL, size: Int64) throws -> String {
        guard let root = destination.writableRoot() else {
            throw ReceiveDestinationError(Self.refusalMessage)
        }
        let name = InboxDestination.lastSegment(relPath)
        let sub = InboxDestination.parentPath(relPath)
        let directory = try ensureDirectories(root, sub)
        let safeName = name.isEmpty ? "received-file" : name
        let target = InboxDestination.uniqueTarget(in: directory, name: safeName, fileManager: fileManager)
        if fileManager.fileExists(atPath: target.path) {
            try fileManager.removeItem(at: target)
        }
        try fileManager.copyItem(at: spool, to: target)
        return target.lastPathComponent
    }

    // MARK: - Internals

    private func ensureDirectories(_ root: URL, _ rel: String) throws -> URL {
        var current = root
        for segment in rel.split(separator: "/") where !segment.isEmpty {
            current = current.appendingPathComponent(String(segment), isDirectory: true)
            var isDir: ObjCBool = false
            if !fileManager.fileExists(atPath: current.path, isDirectory: &isDir) {
                try fileManager.createDirectory(at: current, withIntermediateDirectories: true)
            }
            guard fileManager.fileExists(atPath: current.path, isDirectory: &isDir), isDir.boolValue else {
                throw ReceiveDestinationError("Could not create the destination folder on this device.")
            }
        }
        return current
    }

    private static func uniqueTarget(in directory: URL, name: String, fileManager: FileManager) -> URL {
        let chosen = uniqueName(name) { candidate in
            fileManager.fileExists(atPath: directory.appendingPathComponent(candidate).path)
        }
        return directory.appendingPathComponent(chosen)
    }

    private static func lastSegment(_ relPath: String) -> String {
        guard let slash = relPath.lastIndex(of: "/") else { return relPath }
        return String(relPath[relPath.index(after: slash)...])
    }

    private static func parentPath(_ relPath: String) -> String {
        guard let slash = relPath.lastIndex(of: "/") else { return "" }
        return String(relPath[..<slash])
    }
}
