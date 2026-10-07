#!/usr/bin/env bash
#
# Cross-check: Swift builds a self-signed Ed25519 certificate DER, Go parses
# and validates it. Fails loudly on any mismatch.
#
#   bash ios/fixtures/crosscheck.sh
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
root="$here"
while [ ! -f "$root/Package.swift" ] && [ "$root" != "/" ]; do
    root="$(dirname "$root")"
done
if [ ! -f "$root/Package.swift" ]; then
    echo "crosscheck: could not locate Package.swift above $here" >&2
    exit 1
fi
export PATH="$HOME/swift/usr/bin:$PATH"

seed="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

cd "$root"

echo "==> swift run certgen $seed"
hex="$(swift run --scratch-path /tmp/.build-cert certgen "$seed" 2>/dev/null)" || {
    echo "crosscheck: swift run certgen failed" >&2
    exit 1
}

hex="$(printf '%s' "$hex" | tr -d '[:space:]')"
if ! printf '%s' "$hex" | grep -Eq '^[0-9a-fA-F]+$' || [ $(( ${#hex} % 2 )) -ne 0 ]; then
    echo "crosscheck: certgen did not emit clean hex (got ${#hex} chars)" >&2
    exit 1
fi
echo "    DER: ${#hex} hex chars"

echo "==> go run verify_cert.go - (stdin)"
printf '%s' "$hex" | xxd -r -p | go run "$here/verify_cert.go" -
