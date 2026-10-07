// SettingsView.swift — Settings tab, Phase 3 slice: the inbox folder row.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: it shows the
// current inbox folder and forwards choices to `InboxModel`. There are no typed
// paths anywhere — the only alternatives are the default (Documents/LANyard)
// and a folder the person picks in the system `UIDocumentPicker`, whose
// security-scoped bookmark is stored by `InboxDestinationAdapter`.

import SwiftUI
import UIKit
import UniformTypeIdentifiers
import LanyardCore
import LanyardNet

struct SettingsView: View {
    @EnvironmentObject private var inbox: InboxModel
    @State private var showFolderPicker = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Inbox") {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Download folder")
                            Text(inbox.displayPath)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            if !inbox.isWritable {
                                Text(inbox.refusalMessage)
                                    .font(.footnote)
                                    .foregroundStyle(.red)
                            }
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 8) {
                            Button("Choose folder…") { showFolderPicker = true }
                            if inbox.isUsingOverride {
                                Button("Use default") { inbox.useDefault() }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showFolderPicker) {
                FolderPicker { url in
                    showFolderPicker = false
                    do {
                        try inbox.choose(url)
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
        }
    }
}

/// A `UIDocumentPickerViewController` restricted to folders, wrapped for
/// SwiftUI. The URL handed back is security-scoped; the adapter starts access
/// and persists a bookmark.
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
