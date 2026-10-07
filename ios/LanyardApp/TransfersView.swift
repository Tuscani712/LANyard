// TransfersView.swift — the Transfers tab.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Strictly presentational: it
// reads `TransfersModel.records` and forwards taps. A finished row offers
// Dismiss; "Clear history" calls `clearHistory()`. The core has no `cancel`, so
// active rows have no button.
//
// A failed row renders `TransferRecord.message` verbatim, so a receive the core
// marked `Failed` with `"Interrupted"` shows exactly that.

import SwiftUI
import LanyardCore
import LanyardNet

struct TransfersView: View {
    @EnvironmentObject private var transfers: TransfersModel

    var body: some View {
        NavigationStack {
            Group {
                if transfers.records.isEmpty {
                    VStack {
                        Text("No transfers.")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(transfers.records) { record in
                            TransferRow(
                                record: record,
                                onDismiss: { transfers.dismiss(record.id) }
                            )
                        }
                    }
                }
            }
            .navigationTitle("Transfers")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear history") { transfers.clearHistory() }
                        .disabled(!transfers.hasFinished)
                }
            }
        }
    }
}

private struct TransferRow: View {
    let record: TransferRecord
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: record.direction == "send" ? "arrow.up.circle" : "arrow.down.circle")
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.label)
                        .font(.subheadline.weight(.semibold))
                    Text(record.peerName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(Self.stateLabel(record))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if record.total > 0 {
                ProgressView(value: progress)
                Text("\(humanSize(record.done)) / \(humanSize(record.total))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !record.state.isActive {
                HStack {
                    Spacer()
                    // Finished (Done/Failed/Cancelled) rows are dismissable.
                    Button("Dismiss", action: onDismiss)
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var progress: Double {
        guard record.total > 0 else { return 0 }
        return min(1, max(0, Double(record.done) / Double(record.total)))
    }

    /// A failed row shows its message; the core sets it to "Interrupted" when a
    /// receive was cut off, which is therefore shown verbatim.
    static func stateLabel(_ record: TransferRecord) -> String {
        switch record.state {
        case .queued:
            return "Waiting"
        case .running:
            return record.direction == "send" ? "Sending" : "Receiving"
        case .done:
            return "Done"
        case .cancelled:
            return "Cancelled"
        case .failed:
            let message = record.message ?? ""
            return message.isEmpty ? "Failed" : message
        }
    }
}

private func humanSize(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    let units = ["KB", "MB", "GB", "TB"]
    var value = Double(bytes) / 1024
    var unit = 0
    while value >= 1024 && unit < units.count - 1 {
        value /= 1024
        unit += 1
    }
    return String(format: "%.1f %@", value, units[unit])
}
