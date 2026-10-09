//go:build !linux

package peerapi

// portHolder cannot identify the process on this platform, so the
// temporary-port message simply omits the holder.
func portHolder(int) string { return "" }
