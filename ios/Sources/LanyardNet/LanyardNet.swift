// LanyardNet — the Apple-only half of the LANyard iPhone app.
//
// Everything in this target is wrapped in `#if canImport(Network)` /
// `#if canImport(Security)`, so on Linux (where neither framework exists) the
// target compiles to nothing and `swift test` stays green. On a Mac it is
// compiled for real. Until then it is "written, not compiled".
#if canImport(Network) && canImport(Security)
import Foundation
import LanyardCore

// Concrete implementations now live in sibling files (all behind the same
// guard): KeychainIdentityStore, SecIdentityFactory, TLS, PeerListener,
// PeerConnector, BonjourBrowser, PeerClientAdapter and PeerServerAdapter.
// See SPIKE.md for the first-Mac checklist.
#endif
