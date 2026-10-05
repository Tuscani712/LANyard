package peerapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

// StatusError is returned when a peer answers with an unexpected HTTP status.
type StatusError struct {
	Code   int
	Status string
	Msg    string
}

func (e *StatusError) Error() string { return fmt.Sprintf("%s: %s", e.Status, e.Msg) }

func (c *Client) base(host string, port int) string {
	return "https://" + net.JoinHostPort(host, strconv.Itoa(port)) + "/api/v1"
}

// do performs a request and, when expectedFP is set, pins the presented server
// certificate to that fingerprint (spec §3.3). TLS accepts any self-signed
// cert, so this is what stops a swapped peer.
func (c *Client) do(ctx context.Context, method, u string, body io.Reader, expectedFP string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	if expectedFP != "" {
		if resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 {
			resp.Body.Close()
			return nil, errors.New("peer presented no certificate")
		}
		got := identity.FingerprintOf(resp.TLS.PeerCertificates[0])
		if got != expectedFP {
			resp.Body.Close()
			return nil, fmt.Errorf("peer certificate %s does not match expected %s", identity.ShortID(got), identity.ShortID(expectedFP))
		}
	}
	return resp, nil
}

func (c *Client) doJSON(ctx context.Context, method, u string, body io.Reader, expectedFP string, out any) error {
	resp, err := c.do(ctx, method, u, body, expectedFP)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(http.MaxBytesReader(nil, resp.Body, 4096))
		return &StatusError{Code: resp.StatusCode, Status: resp.Status, Msg: strings.TrimSpace(string(msg))}
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(http.MaxBytesReader(nil, resp.Body, 64<<20)).Decode(out)
}

// ListShares fetches the peer's visible shares.
func (c *Client) ListShares(ctx context.Context, host string, port int, expectedFP string) ([]shares.Summary, error) {
	var out []shares.Summary
	err := c.doJSON(ctx, http.MethodGet, c.base(host, port)+"/shares", nil, expectedFP, &out)
	return out, err
}

// Tree lists one directory level of a share.
func (c *Client) Tree(ctx context.Context, host string, port int, expectedFP, shareID, rel string) ([]shares.Entry, error) {
	u := c.base(host, port) + "/shares/" + url.PathEscape(shareID) + "/tree?path=" + url.QueryEscape(rel)
	var out []shares.Entry
	err := c.doJSON(ctx, http.MethodGet, u, nil, expectedFP, &out)
	return out, err
}

// Manifest is the recursive file list for a share path.
type Manifest struct {
	Files      []shares.ManifestEntry `json:"files"`
	TotalBytes int64                  `json:"total_bytes"`
	Count      int                    `json:"count"`
	Lifetime   string                 `json:"lifetime"`
}

// GetManifest fetches the recursive listing under rel.
func (c *Client) GetManifest(ctx context.Context, host string, port int, expectedFP, shareID, rel string) (*Manifest, error) {
	u := c.base(host, port) + "/shares/" + url.PathEscape(shareID) + "/manifest?path=" + url.QueryEscape(rel)
	var m Manifest
	err := c.doJSON(ctx, http.MethodGet, u, nil, expectedFP, &m)
	return &m, err
}

// OpenFile starts a file GET. offset>0 sends a Range; etag (if set) sends
// If-Range so a changed source restarts from zero. The caller must close the
// response body.
func (c *Client) OpenFile(ctx context.Context, host string, port int, expectedFP, shareID, rel string, offset int64, etag string) (*http.Response, error) {
	u := c.base(host, port) + "/shares/" + url.PathEscape(shareID) + "/file?path=" + url.QueryEscape(rel)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	if offset > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", offset))
		if etag != "" {
			req.Header.Set("If-Range", QuoteETag(etag))
		}
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	if expectedFP != "" {
		if resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 ||
			identity.FingerprintOf(resp.TLS.PeerCertificates[0]) != expectedFP {
			resp.Body.Close()
			return nil, errors.New("peer certificate mismatch")
		}
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		msg, _ := io.ReadAll(http.MaxBytesReader(nil, resp.Body, 4096))
		resp.Body.Close()
		return nil, &StatusError{Code: resp.StatusCode, Status: resp.Status, Msg: strings.TrimSpace(string(msg))}
	}
	return resp, nil
}

// QuoteETag returns the validator in HTTP entity-tag form ("..."), the form
// the server emits in its ETag header and compares against If-Range.
func QuoteETag(etag string) string {
	if strings.HasPrefix(etag, `"`) {
		return etag
	}
	return `"` + etag + `"`
}

// FileHash is the server-computed whole-file digest used to verify resumed
// downloads, together with the validator it was computed for.
type FileHash struct {
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
	ETag   string `json:"etag"`
}

// FileHash asks the peer for the SHA-256 of a whole file (cached server side).
func (c *Client) FileHash(ctx context.Context, host string, port int, expectedFP, shareID, rel string) (*FileHash, error) {
	u := c.base(host, port) + "/shares/" + url.PathEscape(shareID) + "/hash?path=" + url.QueryEscape(rel)
	var out FileHash
	if err := c.doJSON(ctx, http.MethodGet, u, nil, expectedFP, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// --- session client ---

// SessionRequestPayload is what an initiator sends to start Connect/Pair.
type SessionRequestPayload struct {
	Mode      string            `json:"mode"`
	Name      string            `json:"name"`
	DeviceID  string            `json:"device_id"`
	Nonce     string            `json:"nonce"`
	Requested trust.Permissions `json:"requested_permissions"`
}

// SessionResponse is what the responder returns.
type SessionResponse struct {
	SessionID string `json:"session_id"`
	Nonce     string `json:"nonce"`
	Status    string `json:"status"`
}

// SessionStatus is the polled state of a session from the requester's view.
type SessionStatus struct {
	Status  string            `json:"status"`
	Mode    string            `json:"mode"`
	Nonce   string            `json:"nonce"`
	Granted trust.Permissions `json:"granted"`
	Error   string            `json:"error,omitempty"`
}

// StartSession posts a Connect/Pair request to a peer.
func (c *Client) StartSession(ctx context.Context, host string, port int, expectedFP string, p SessionRequestPayload) (*SessionResponse, error) {
	b, _ := json.Marshal(p)
	var out SessionResponse
	if err := c.doJSON(ctx, http.MethodPost, c.base(host, port)+"/session/request", strings.NewReader(string(b)), expectedFP, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SessionStatus polls a peer session by its remote id.
func (c *Client) SessionStatus(ctx context.Context, host string, port int, expectedFP, id string) (*SessionStatus, error) {
	u := c.base(host, port) + "/session/" + url.PathEscape(id)
	var out SessionStatus
	if err := c.doJSON(ctx, http.MethodGet, u, nil, expectedFP, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ConfirmSession tells the responder that the initiator accepted the SAS.
func (c *Client) ConfirmSession(ctx context.Context, host string, port int, expectedFP, id string) error {
	u := c.base(host, port) + "/session/" + url.PathEscape(id) + "/confirm"
	return c.doJSON(ctx, http.MethodPost, u, nil, expectedFP, nil)
}

// CloseSession ends a session on the peer.
// EndSession tells the peer that our transfer in a Connect session is done, so
// it closes its end too (unless it chose "Keep connected").
func (c *Client) EndSession(ctx context.Context, host string, port int, expectedFP, id string) error {
	u := c.base(host, port) + "/session/" + url.PathEscape(id) + "/close?reason=transfer"
	return c.doJSON(ctx, http.MethodPost, u, nil, expectedFP, nil)
}

// EndConnectAfterTransfer applies the Connect rule "one session, one transfer":
// once a transfer with peerFP has finished, end our side of the live Connect
// session (unless "Keep connected" is set) and tell the peer to do the same.
// It reports whether our side was ended.
func (c *Client) EndConnectAfterTransfer(ctx context.Context, tr *trust.Store, peerFP, host string, port int) bool {
	remoteID, ended := tr.EndAfterTransfer(peerFP)
	if !ended {
		return false
	}
	if remoteID != "" {
		_ = c.EndSession(ctx, host, port, peerFP, remoteID) // best effort: the peer's own timeout also ends it
	}
	return true
}

func (c *Client) CloseSession(ctx context.Context, host string, port int, expectedFP, id string) error {
	u := c.base(host, port) + "/session/" + url.PathEscape(id) + "/close"
	return c.doJSON(ctx, http.MethodPost, u, nil, expectedFP, nil)
}

// RevokePairing asks the peer to drop us from its trust store, so an unpair on
// one device removes the pairing on both.
func (c *Client) RevokePairing(ctx context.Context, host string, port int, expectedFP string) error {
	u := c.base(host, port) + "/trust/revoke"
	return c.doJSON(ctx, http.MethodPost, u, nil, expectedFP, nil)
}

// --- push client ---

// PushOfferResult is the responder's answer to a push offer.
type PushOfferResult struct {
	PushID   string `json:"push_id"`
	Accepted bool   `json:"accepted"`
	MaxBytes int64  `json:"max_bytes"`
	Files    []struct {
		RelPath string `json:"rel_path"`
		Offset  int64  `json:"offset"`
	} `json:"files"`
}

// PushOffer proposes files for the peer's Inbox.
func (c *Client) PushOffer(ctx context.Context, host string, port int, expectedFP string, files []inbox.FileReq) (*PushOfferResult, error) {
	b, _ := json.Marshal(map[string]any{"files": files})
	var out PushOfferResult
	if err := c.doJSON(ctx, http.MethodPost, c.base(host, port)+"/push/offer", strings.NewReader(string(b)), expectedFP, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// PushFile uploads the remaining bytes of one file at offset. total is the full
// file size (for Content-Range).
func (c *Client) PushFile(ctx context.Context, host string, port int, expectedFP, pushID, rel string, offset, total int64, body io.Reader) (int64, error) {
	u := c.base(host, port) + "/push/" + url.PathEscape(pushID) + "/file?path=" + url.QueryEscape(rel)
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, u, body)
	if err != nil {
		return 0, err
	}
	end := total - 1
	if end < offset {
		end = offset
	}
	req.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, end, total))
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if expectedFP != "" {
		if resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 ||
			identity.FingerprintOf(resp.TLS.PeerCertificates[0]) != expectedFP {
			return 0, errors.New("peer certificate mismatch")
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(http.MaxBytesReader(nil, resp.Body, 4096))
		return 0, &StatusError{Code: resp.StatusCode, Status: resp.Status, Msg: strings.TrimSpace(string(msg))}
	}
	var out struct {
		Written int64 `json:"written"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(nil, resp.Body, 4096)).Decode(&out)
	return out.Written, nil
}

// PushFileSHA sends a whole file in one request together with its SHA-256; the
// receiver verifies and finalizes it in the same call (no PushComplete needed).
func (c *Client) PushFileSHA(ctx context.Context, host string, port int, expectedFP, pushID, rel string, body io.Reader, size int64, sha string) error {
	u := c.base(host, port) + "/push/" + url.PathEscape(pushID) + "/file?path=" + url.QueryEscape(rel)
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, u, body)
	if err != nil {
		return err
	}
	end := size - 1
	if end < 0 {
		end = 0
	}
	req.Header.Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", end, size))
	req.Header.Set("X-Lanyard-SHA256", sha)
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if expectedFP != "" {
		if resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 ||
			identity.FingerprintOf(resp.TLS.PeerCertificates[0]) != expectedFP {
			return errors.New("peer certificate mismatch")
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(http.MaxBytesReader(nil, resp.Body, 4096))
		return &StatusError{Code: resp.StatusCode, Status: resp.Status, Msg: strings.TrimSpace(string(msg))}
	}
	return nil
}

// PushComplete verifies and finalizes one file, or the whole push when all is
// true.
func (c *Client) PushComplete(ctx context.Context, host string, port int, expectedFP, pushID, rel, sha string, all bool) error {
	b, _ := json.Marshal(map[string]any{"rel_path": rel, "sha256": sha, "all": all})
	u := c.base(host, port) + "/push/" + url.PathEscape(pushID) + "/complete"
	return c.doJSON(ctx, http.MethodPost, u, strings.NewReader(string(b)), expectedFP, nil)
}

// VerifiedFile is one file the receiver has downloaded and hash-verified.
type VerifiedFile struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
}

// CompleteShare reports a finished, verified download so the sender can retire
// a one-time share. consumed is false for shares that are not one-time.
func (c *Client) CompleteShare(ctx context.Context, host string, port int, expectedFP, shareID string, files []VerifiedFile) (consumed bool, err error) {
	body, err := json.Marshal(map[string]any{"files": files})
	if err != nil {
		return false, err
	}
	u := c.base(host, port) + "/shares/" + url.PathEscape(shareID) + "/complete"
	var out struct {
		Consumed bool `json:"consumed"`
	}
	if err := c.doJSON(ctx, http.MethodPost, u, bytes.NewReader(body), expectedFP, &out); err != nil {
		return false, err
	}
	return out.Consumed, nil
}
