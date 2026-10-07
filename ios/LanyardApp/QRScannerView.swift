// QRScannerView.swift — the system QR scanner (AVFoundation) wrapped for SwiftUI.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). It captures QR metadata with
// `AVCaptureMetadataOutput` and hands the raw string back. Parsing is delegated
// to `PairFlowModel.pasteLink(_:)`, which calls the REAL
// `PairFlow.parseManualLink(_:)` (itself the public wrapper around the core
// `PairLink.parse`). This view never interprets the payload and never accepts a
// typed address.
//
// Info.plist must declare `NSCameraUsageDescription` or the capture session
// fails to start. That key is owned by the app target's Info.plist and is NOT
// added here (Phase 4 was scoped to the views; see the return notes).

import SwiftUI
import AVFoundation

/// A full-screen scanner presented as a sheet. `onCode` receives the first QR
/// string and the sheet dismisses itself.
struct QRScannerSheet: View {
    let onCode: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QRScannerView { code in
                onCode(code)
                dismiss()
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Scan invite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

/// SwiftUI wrapper around `QRScannerController`.
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode)
    }

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {}

    final class Coordinator: NSObject, QRScannerControllerDelegate {
        private let onCode: (String) -> Void
        private var delivered = false

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func scanner(_ controller: QRScannerController, didFind code: String) {
            guard !delivered else { return }
            delivered = true
            onCode(code)
        }
    }
}

protocol QRScannerControllerDelegate: AnyObject {
    func scanner(_ controller: QRScannerController, didFind code: String)
}

/// The AVFoundation controller. Configures a capture session with the back
/// camera and a QR `AVCaptureMetadataOutput`.
final class QRScannerController: UIViewController {
    weak var delegate: QRScannerControllerDelegate?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue(label: "io.github.tuscani712.lanyard.qr")
    private var configured = false
    private let statusLabel = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureStatusLabel()
        requestCameraAccessThenConfigure()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if configured { startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopRunning()
    }

    // MARK: - Setup

    private func configureStatusLabel() {
        statusLabel.text = "Point at a LANyard invite QR code"
        statusLabel.textColor = .white
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
        ])
    }

    private func requestCameraAccessThenConfigure() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted else { return }
                self?.configureSession()
            }
        default:
            updateStatus("Camera access is off. Enable it in Settings to scan a QR code.")
        }
    }

    private func configureSession() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device),
                  self.session.canAddInput(input) else {
                self.updateStatus("No usable camera on this device.")
                return
            }
            self.session.beginConfiguration()
            self.session.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard self.session.canAddOutput(output) else {
                self.session.commitConfiguration()
                self.updateStatus("Could not start the QR scanner.")
                return
            }
            self.session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = [.qr]
            self.session.commitConfiguration()

            let preview = AVCaptureVideoPreviewLayer(session: self.session)
            preview.videoGravity = .resizeAspectFill
            DispatchQueue.main.async {
                preview.frame = self.view.bounds
                self.view.layer.insertSublayer(preview, at: 0)
                self.previewLayer = preview
            }

            self.configured = true
            self.startRunning()
        }
    }

    private func startRunning() {
        sessionQueue.async { [weak self] in
            guard let self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    private func stopRunning() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    private func updateStatus(_ text: String) {
        DispatchQueue.main.async { self.statusLabel.text = text }
    }
}

extension QRScannerController: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue,
                  !value.isEmpty else { continue }
            delegate?.scanner(self, didFind: value)
            return
        }
    }
}
