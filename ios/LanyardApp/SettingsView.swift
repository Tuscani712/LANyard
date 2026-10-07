// SettingsView.swift — the Settings tab.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: every control
// forwards to `SettingsModelVM`, which persists through the core
// `SettingsModel`. There are no typed paths anywhere — the inbox folder is
// either the default (`Documents/LANyard`) or a folder chosen in the system
// `UIDocumentPicker`, whose security-scoped bookmark is stored by the app's
// `InboxDestinationAdapter`.
//
// The About row reads the version from the bundle and the license/repo URL from
// `LanyardCore.AboutApp`.

import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications
import LanyardCore
import LanyardNet

struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsModelVM

    @State private var showFolderPicker = false
    @State private var errorMessage: String?
    @State private var notificationNote: String?

    private let themes: [ThemeMode] = [.system, .light, .dark]
    private let speedUnits: [SpeedUnit] = [.MBps, .Mbps]

    var body: some View {
        NavigationStack {
            Form {
                appearanceSection
                transfersSection
                inboxSection
                aboutSection
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showFolderPicker) {
                FolderPicker { url in
                    showFolderPicker = false
                    do {
                        try settings.chooseFolder(url)
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
            .alert(
                "Could not use that folder",
                isPresented: Binding(
                    get: { errorMessage != nil },
                    set: { if !$0 { errorMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .alert(
                "Notifications",
                isPresented: Binding(
                    get: { notificationNote != nil },
                    set: { if !$0 { notificationNote = nil } }
                )
            ) {
                Button("OK", role: .cancel) { notificationNote = nil }
            } message: {
                Text(notificationNote ?? "")
            }
        }
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Theme", selection: Binding(
                get: { settings.settings.theme },
                set: { settings.setTheme($0) }
            )) {
                ForEach(themes, id: \.self) { mode in
                    Text(themeLabel(mode)).tag(mode)
                }
            }

            Picker("Speed units", selection: Binding(
                get: { settings.settings.speedUnit },
                set: { settings.setSpeedUnit($0) }
            )) {
                ForEach(speedUnits, id: \.self) { unit in
                    Text(unit.rawValue).tag(unit)
                }
            }
        }
    }

    private var transfersSection: some View {
        Section {
            Toggle("Notifications", isOn: Binding(
                get: { settings.settings.notifications },
                set: { on in
                    settings.setNotifications(on)
                    if on { requestNotificationPermission() }
                }
            ))

            Toggle("Play sound when a transfer finishes", isOn: Binding(
                get: { settings.settings.soundOnComplete },
                set: { settings.setSoundOnComplete($0) }
            ))

            Toggle("Wi-Fi only", isOn: Binding(
                get: { settings.settings.wifiOnly },
                set: { settings.setWifiOnly($0) }
            ))
        } header: {
            Text("Transfers")
        } footer: {
            Text("Wi-Fi only refuses transfers on mobile data, so a large send can never surprise you with a data bill.")
        }
    }

    private var bandwidthBinding: Binding<Int> {
        Binding(
            get: { settings.settings.bandwidthLimitMBps },
            set: { settings.setBandwidthLimitMBps($0) }
        )
    }

    private var inboxSection: some View {
        Section {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Download folder")
                    Text(settings.downloadFolderDisplay)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 8) {
                    Button("Choose folder…") { showFolderPicker = true }
                    if settings.isUsingOverrideFolder {
                        Button("Use default") { settings.useDefaultFolder() }
                    }
                }
            }

            Stepper(value: bandwidthBinding, in: 0...1000) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Bandwidth limit")
                    Text(settings.settings.bandwidthLimitMBps == 0
                         ? "Unlimited"
                         : "\(settings.settings.bandwidthLimitMBps) MB/s")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Inbox")
        } footer: {
            Text("Received files land in the download folder. A bandwidth limit of Unlimited (0) sends as fast as the network allows.")
        }
    }

    private var aboutSection: some View {
        Section("About") {
            LabeledContent("Version", value: settings.about.version)
            LabeledContent("License", value: settings.about.license)
            if let url = URL(string: settings.about.repoURL) {
                Link("Source code", destination: url)
            }
        }
    }

    // MARK: - Helpers

    private func themeLabel(_ mode: ThemeMode) -> String {
        switch mode {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }

    /// Asks iOS for notification permission when the toggle is switched on. The
    /// grant is the person's (and only Settings can undo it), so a denial is
    /// reported, never fought.
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            Task { @MainActor in
                if let error {
                    notificationNote = error.localizedDescription
                } else if !granted {
                    notificationNote = "Notifications are blocked. Allow them for LANyard in Settings → Notifications."
                }
            }
        }
    }
}

// MARK: - Folder picker

/// A `UIDocumentPickerViewController` restricted to folders, wrapped for
/// SwiftUI. The URL handed back is security-scoped; `SettingsModelVM` forwards
/// it to the app bridge, which starts access and persists a bookmark through
/// `InboxDestinationAdapter`.
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [UTType.folder],
            asCopy: false
        )
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}
