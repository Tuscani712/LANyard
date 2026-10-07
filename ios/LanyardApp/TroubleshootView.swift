// TroubleshootView.swift — the Troubleshoot tab.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: it renders the
// rows `TroubleshootModel` produces from the REAL `LanyardCore.Troubleshoot`
// checks, the live iOS diagnostics (Local Network permission + listener state),
// and the in-memory `ServerDiagnostics` event log (at most
// `Troubleshoot.maxEvents`, 200). The Copy report button places the redacted
// `Troubleshoot.report` text on the system pasteboard.
//
// Nothing here inspects a typed path or a secret: the report is redacted by
// `Redaction` in core before it is ever shown or copied.

import SwiftUI
import UIKit
import LanyardCore
import LanyardNet

struct TroubleshootView: View {
    @EnvironmentObject private var troubleshoot: TroubleshootModel
    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                checksSection
                iosSection
                eventsSection
            }
            .navigationTitle("Troubleshoot")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        troubleshoot.runChecks()
                        copied = false
                    } label: {
                        Label("Run again", systemImage: "arrow.clockwise")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        copyReport()
                    } label: {
                        Label(copied ? "Copied" : "Copy report",
                              systemImage: copied ? "checkmark" : "doc.on.doc")
                    }
                }
            }
            .onAppear {
                if troubleshoot.checks.isEmpty { troubleshoot.runChecks() }
                else { troubleshoot.refreshEvents() }
            }
        }
    }

    // MARK: - Sections

    private var platformChecks: [CheckResult] {
        troubleshoot.checks.filter { !$0.id.hasPrefix("ios-") }
    }

    private var iosChecks: [CheckResult] {
        troubleshoot.checks.filter { $0.id.hasPrefix("ios-") }
    }

    private var checksSection: some View {
        Section("Checks") {
            if platformChecks.isEmpty {
                Text("No checks yet. Tap Run again.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(platformChecks, id: \.id) { check in
                    CheckRow(check: check)
                }
            }
        }
    }

    private var iosSection: some View {
        Section {
            if iosChecks.isEmpty {
                Text("Run the checks to read the iOS state.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(iosChecks, id: \.id) { check in
                    CheckRow(check: check)
                }
            }
        } header: {
            Text("iPhone")
        } footer: {
            Text("Local Network access and the receive listener are controlled by iOS and the app's lifecycle. Keep LANyard open to receive transfers.")
        }
    }

    private var eventsSection: some View {
        Section {
            if troubleshoot.events.isEmpty {
                Text("No server events yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(troubleshoot.events.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
        } header: {
            HStack {
                Text("Server events")
                Spacer()
                Text("\(troubleshoot.events.count)/\(Troubleshoot.maxEvents)")
                    .foregroundStyle(.secondary)
            }
        } footer: {
            Text("The most recent events from this run. Clear the list to hide what has been collected; the report is redacted before it is copied.")
        }
    }

    // MARK: - Actions

    private func copyReport() {
        UIPasteboard.general.string = troubleshoot.reportText()
        copied = true
    }
}

// MARK: - Row

/// One check row: a status glyph, the title, the detail and the fix. Pass/warn/
/// fail map to the core's `CheckStatus`.
private struct CheckRow: View {
    let check: CheckResult

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .font(.body.weight(.semibold))
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(check.title).font(.body.weight(.medium))
                Text(check.detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if !check.fix.isEmpty {
                    Text(check.fix)
                        .font(.footnote)
                        .foregroundStyle(color)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private var icon: String {
        switch check.status {
        case .ok: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .failed: return "xmark.octagon.fill"
        case .skipped: return "minus.circle.fill"
        }
    }

    private var color: Color {
        switch check.status {
        case .ok: return .green
        case .warning: return .orange
        case .failed: return .red
        case .skipped: return .secondary
        }
    }
}
