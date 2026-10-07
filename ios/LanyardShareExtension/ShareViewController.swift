// ShareViewController.swift — the Phase 6 share extension.
//
// WRITTEN, NOT COMPILED. This target is produced by XcodeGen (project.yml) and
// is not part of `swift build`, so it has never been type-checked by an Apple
// toolchain. It binds the REAL `LanyardCore.ShareValidation` rules for item
// caps and file names.
//
// What it does:
//   1. Reads the share sheet's `NSExtensionItem` attachments: files, URLs and
//      text (images/movies arrive as file URLs).
//   2. **Spools every item into the App Group container before finishing.**
//      This is mandatory: the security-scoped grant a host app hands the
//      extension dies with the extension process, so a picked URL is not
//      readable by LANyard later. Only durable copies in the shared container
//      survive.
//   3. Writes a `manifest.json` describing the spooled items and hands off to
//      the app with a `lanyard://share?manifest=…` link. The app reads the
//      spool from the App Group and shows its send device picker.
//
// ── WHAT CANNOT WORK WITHOUT AN APPLE TEAM ID / PROVISIONING PROFILE ─────────
// * **App Groups** (`com.apple.security.application-groups`): the extension and
//   the app can only see `containerURL(forSecurityApplicationGroupIdentifier:)`
//   when both are signed with the same Team ID and the group is registered on
//   the developer portal. Without a team, `containerURL` returns nil and the
//   spool falls back to a private temp directory the app can never read.
// * **iCloud / any capability entitlement** likewise needs a team.
// * **Keychain sharing** (`keychain-access-groups`) needs a team; not added.
// * **`lanyard://` handoff**: the scheme must be registered in the app's
//   Info.plist (it is), but a share extension is not allowed to call
//   `UIApplication.shared.open`; see `openViaResponderChain` below.
// ─────────────────────────────────────────────────────────────────────────────

import UIKit
import UniformTypeIdentifiers
import LanyardCore

/// The App Group shared by the app and this extension. MUST equal the
/// entitlement in `project.yml` for both targets.
enum LanyardAppGroup {
    static let identifier = "group.io.github.tuscani712.lanyard"
}

/// One spooled share item, as written to `manifest.json`. The app decodes this
/// to find the durable copies. `path` is relative to the spool directory.
struct StagedItem: Codable {
    enum Kind: String, Codable { case file, url, text }
    let name: String
    let kind: Kind
    /// Relative path inside the spool directory (nil for an inline snippet).
    let path: String?
    /// Inline text for a short text share (never also written to `path`).
    let text: String?
    let size: Int64
}

/// Copies share-sheet items into the App Group container and writes the
/// manifest the app reads. Nothing here survives the extension unless it is in
/// `root`.
final class ShareSpool {
    let root: URL

    init(fileManager: FileManager = .default) {
        if let container = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: LanyardAppGroup.identifier
        ) {
            root = container.appendingPathComponent("ShareSpool", isDirectory: true)
        } else {
            // MAC-SPIKE: App Groups need an Apple team ID / provisioning
            // profile. No App Group (no team ID / entitlement not provisioned):
            // fall back to a private temp dir so the UI still works, but the app
            // cannot read it. See the team-ID note at the top of this file.
            root = fileManager.temporaryDirectory.appendingPathComponent("ShareSpool", isDirectory: true)
        }
        try? fileManager.createDirectory(
            at: root.appendingPathComponent("items", isDirectory: true),
            withIntermediateDirectories: true
        )
    }

    private var itemsDirectory: URL { root.appendingPathComponent("items", isDirectory: true) }

    /// Copies a file/URL's bytes into the spool under a safe name.
    func stageFile(at url: URL, preferredName: String?) throws -> StagedItem {
        let rawName = preferredName ?? url.lastPathComponent
        let name = ShareValidation.safeShareName(rawName)
        let unique = UUID().uuidString.prefix(8) + "-" + name
        let destination = itemsDirectory.appendingPathComponent(unique, isDirectory: false)
        try FileManager.default.copyItem(at: url, to: destination)
        let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        return StagedItem(name: name, kind: .file, path: "items/" + unique, text: nil, size: size)
    }

    /// Writes a short piece of text inline, or as a `.txt` file when it exceeds
    /// the core's snippet cap.
    func stageText(_ text: String) throws -> StagedItem {
        if ShareValidation.textExceedsSnippet(text) {
            let unique = UUID().uuidString.prefix(8) + "-shared.txt"
            let destination = itemsDirectory.appendingPathComponent(unique, isDirectory: false)
            let data = Data(text.utf8)
            try data.write(to: destination, options: .atomic)
            return StagedItem(name: "shared.txt", kind: .text, path: "items/" + unique, text: nil, size: Int64(data.count))
        }
        return StagedItem(name: "shared-text.txt", kind: .text, path: nil, text: text, size: Int64(text.utf8.count))
    }

    /// Writes a non-file URL as a small `.url` file so the app can share it.
    func stageURL(_ url: URL) throws -> StagedItem {
        let unique = UUID().uuidString.prefix(8) + "-shared.url"
        let destination = itemsDirectory.appendingPathComponent(unique, isDirectory: false)
        let data = Data((url.absoluteString + "\n").utf8)
        try data.write(to: destination, options: .atomic)
        return StagedItem(name: "shared.url", kind: .url, path: "items/" + unique, text: nil, size: Int64(data.count))
    }

    /// Writes `manifest.json` and returns its URL.
    func writeManifest(_ items: [StagedItem]) throws -> URL {
        let manifest = root.appendingPathComponent("manifest.json", isDirectory: false)
        let data = try JSONEncoder().encode(items)
        try data.write(to: manifest, options: .atomic)
        return manifest
    }
}

final class ShareViewController: UIViewController {
    private let titleLabel = UILabel()
    private let statusLabel = UILabel()
    private let continueButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)

    private let spool = ShareSpool()
    private var staged: [StagedItem] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        configureUI()
        loadAttachments()
    }

    private func configureUI() {
        titleLabel.text = "Send to LANyard"
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        statusLabel.numberOfLines = 0
        statusLabel.textColor = .secondaryLabel
        statusLabel.text = "Preparing items…"

        continueButton.setTitle("Continue", for: .normal)
        continueButton.addTarget(self, action: #selector(continueTapped), for: .touchUpInside)
        continueButton.isEnabled = false

        let stack = UIStackView(arrangedSubviews: [titleLabel, statusLabel, spinner, continueButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        spinner.startAnimating()
    }

    // MARK: - Loading

    private func loadAttachments() {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            cancel()
            return
        }
        var providers: [NSItemProvider] = []
        for item in items { providers.append(contentsOf: item.attachments ?? []) }
        let capped = Array(providers.prefix(ShareValidation.MAX_ITEMS))
        guard !capped.isEmpty else {
            statusLabel.text = "Nothing to share."
            spinner.stopAnimating()
            cancel()
            return
        }
        load(providers: capped, index: 0)
    }

    private func load(providers: [NSItemProvider], index: Int) {
        guard index < providers.count else { return finishStaging() }
        let provider = providers[index]
        let advance: () -> Void = { [weak self] in
            guard let self else { return }
            self.load(providers: providers, index: index + 1)
        }
        // A file URL (includes images/movies) is preferred; then a web URL;
        // then plain text. `loadItem` may deliver `URL`, `Data` or `String`.
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                let url = Self.asURL(item)
                DispatchQueue.main.async {
                    if let url, let staged = try? self.spool.stageFile(at: url, preferredName: url.lastPathComponent) {
                        self.staged.append(staged)
                    }
                    advance()
                }
            }
        } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                let url = Self.asURL(item)
                DispatchQueue.main.async {
                    if let url {
                        if url.isFileURL, let staged = try? self.spool.stageFile(at: url, preferredName: url.lastPathComponent) {
                            self.staged.append(staged)
                        } else if let staged = try? self.spool.stageURL(url) {
                            self.staged.append(staged)
                        }
                    }
                    advance()
                }
            }
        } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                let text = (item as? String) ?? (item as? Data).flatMap { String(data: $0, encoding: .utf8) }
                DispatchQueue.main.async {
                    if let text, let staged = try? self.spool.stageText(text) {
                        self.staged.append(staged)
                    }
                    advance()
                }
            }
        } else {
            advance()
        }
    }

    private static func asURL(_ item: NSSecureCoding?) -> URL? {
        if let url = item as? URL { return url }
        if let data = item as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        if let string = item as? String { return URL(string: string) }
        return nil
    }

    private func finishStaging() {
        spinner.stopAnimating()
        guard !staged.isEmpty else {
            statusLabel.text = "LANyard could not read these items."
            cancel()
            return
        }
        statusLabel.text = "\(staged.count) item(s) ready. Continue to choose a device."
        continueButton.isEnabled = true
    }

    // MARK: - Handoff

    @objc private func continueTapped() {
        continueButton.isEnabled = false
        spinner.startAnimating()
        do {
            let manifest = try spool.writeManifest(staged)
            handOff(manifest: manifest)
        } catch {
            statusLabel.text = "Could not prepare the share."
            spinner.stopAnimating()
            cancel()
        }
    }

    /// Hands the spooled manifest to the app and finishes. The bytes are
    /// already durable in the App Group, so the app only needs to be told.
    private func handOff(manifest: URL) {
        // MAC-SPIKE: the app-side intake is not implemented yet. The intended
        // route is `LanyardApp.onOpenURL` decoding the manifest from the App
        // Group container and feeding the items into `SendModel`'s picker.
        // "Offering the device picker" therefore means bringing the app to its
        // Send tab, where the paired-device list lives.
        if let url = URL(string: "lanyard://share?manifest=\(manifest.lastPathComponent)") {
            openViaResponderChain(url)
        }
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    /// A share extension cannot call `UIApplication.shared.open`. The responder
    /// chain trick below is best-effort and undocumented; if it does nothing,
    /// the app still finds the spool on its next foreground. See SPIKE.md.
    // MAC-SPIKE: share extensions cannot use UIApplication.shared.open; this
    // responder-chain handoff may be ignored by the system.
    private func openViaResponderChain(_ url: URL) {
        var responder: UIResponder? = self
        let selector = NSSelectorFromString("openURL:")
        while let current = responder {
            if current.responds(to: selector) {
                current.perform(selector, with: url)
                return
            }
            responder = current.next
        }
    }

    private func cancel() {
        extensionContext?.cancelRequest(withError: NSError(
            domain: "io.github.tuscani712.lanyard.share",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "No items were shared."]
        ))
    }
}
