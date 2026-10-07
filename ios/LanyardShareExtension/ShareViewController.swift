// ShareViewController.swift — Phase 6 stub for the LanyardShareExtension target.
//
// WRITTEN, NOT COMPILED. The real extension (Phase 6) will read the share
// intent's items into LanyardCore's ShareModel and hand them to the peer server,
// mirroring the Android ShareReceiverActivity. For now this exists only so the
// XcodeGen target is real and embedded in the app.

import UIKit

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()

        let label = UILabel()
        label.text = "LANyard Share\nComing in Phase 6"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .secondaryLabel
        label.translatesAutoresizingMaskIntoConstraints = false

        view.backgroundColor = .systemBackground
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
    }
}
