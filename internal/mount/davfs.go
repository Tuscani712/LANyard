package mount

import (
	"context"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"os"
	pathpkg "path"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/webdav"

	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
)

// cacheTTL bounds how stale a directory listing may be. File managers ask for
// the same listing many times in a row; this keeps that off the network.
const cacheTTL = 5 * time.Second

// api is the part of the peer client the file system needs.
type api interface {
	ListShares(ctx context.Context, host string, port int, expectedFP string) ([]shares.Summary, error)
	Tree(ctx context.Context, host string, port int, expectedFP, shareID, rel string) ([]shares.Entry, error)
	OpenFile(ctx context.Context, host string, port int, expectedFP, shareID, rel string, offset int64, etag string) (*http.Response, error)
}

// remoteFS exposes a paired peer's shares as a read-only WebDAV file system:
//
//	/                      one entry per share (folder shares are directories,
//	                       file shares appear as the file itself)
//	/<share>/<path...>     the share's files
//
// Everything goes through the peer's normal authenticated API, so the peer's
// permissions, share lifetimes and revocation apply exactly as for browsing.
type remoteFS struct {
	api      api
	fp       string
	resolve  func() (host string, port int, ok bool)
	mu       sync.Mutex
	shareAt  time.Time
	shareSet []shares.Summary
	treeAt   map[string]time.Time
	treeSet  map[string][]shares.Entry
}

func newRemoteFS(a api, fp string, resolve func() (string, int, bool)) *remoteFS {
	return &remoteFS{api: a, fp: fp, resolve: resolve, treeAt: map[string]time.Time{}, treeSet: map[string][]shares.Entry{}}
}

var _ webdav.FileSystem = (*remoteFS)(nil)

func (f *remoteFS) addr() (string, int, error) {
	h, p, ok := f.resolve()
	if !ok {
		return "", 0, errors.New("the device is not reachable right now")
	}
	return h, p, nil
}

// mapErr turns peer answers into the errors the WebDAV layer understands.
func mapErr(err error) error {
	var se *peerapi.StatusError
	if errors.As(err, &se) {
		switch se.Code {
		case http.StatusNotFound, http.StatusGone:
			return os.ErrNotExist
		case http.StatusForbidden, http.StatusUnauthorized:
			return os.ErrPermission
		}
	}
	return err
}

// --- name resolution ---

// displayName is how a share is shown in the root directory.
func displayName(s shares.Summary) string {
	n := s.Label
	if s.Kind == "file" && s.Name != "" {
		n = s.Name
	}
	n = strings.Map(func(r rune) rune {
		switch {
		case r < 0x20, r == '/', r == '\\', r == ':', r == '*', r == '?', r == '"', r == '<', r == '>', r == '|':
			return '_'
		}
		return r
	}, n)
	n = strings.TrimSpace(strings.TrimRight(n, ". "))
	if n == "" {
		n = "share"
	}
	return n
}

// shareList returns the peer's shares keyed by display name (stable, unique).
func (f *remoteFS) shareList(ctx context.Context) ([]shares.Summary, map[string]shares.Summary, error) {
	f.mu.Lock()
	fresh := time.Since(f.shareAt) < cacheTTL && f.shareSet != nil
	list := f.shareSet
	f.mu.Unlock()
	if !fresh {
		h, p, err := f.addr()
		if err != nil {
			return nil, nil, err
		}
		l, err := f.api.ListShares(ctx, h, p, f.fp)
		if err != nil {
			return nil, nil, mapErr(err)
		}
		list = l
		f.mu.Lock()
		f.shareSet, f.shareAt = l, time.Now()
		f.mu.Unlock()
	}
	byName := map[string]shares.Summary{}
	sorted := append([]shares.Summary(nil), list...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].ShareID < sorted[j].ShareID })
	for _, s := range sorted {
		n := displayName(s)
		if _, taken := byName[n]; taken {
			short := s.ShareID
			if len(short) > 6 {
				short = short[len(short)-6:]
			}
			n = n + " (" + short + ")"
		}
		byName[n] = s
	}
	names := make([]shares.Summary, 0, len(byName))
	for _, s := range byName {
		names = append(names, s)
	}
	return names, byName, nil
}

type target struct {
	root  bool // "/"
	share shares.Summary
	name  string // display name of the share
	rel   string // path inside the share ("" = the share root)
}

func (f *remoteFS) locate(ctx context.Context, name string) (target, error) {
	clean := pathpkg.Clean("/" + name)
	if clean == "/" {
		return target{root: true}, nil
	}
	segs := strings.Split(strings.TrimPrefix(clean, "/"), "/")
	_, byName, err := f.shareList(ctx)
	if err != nil {
		return target{}, err
	}
	s, ok := byName[segs[0]]
	if !ok {
		return target{}, os.ErrNotExist
	}
	t := target{share: s, name: segs[0], rel: strings.Join(segs[1:], "/")}
	if s.Kind == "file" && t.rel != "" {
		return target{}, os.ErrNotExist
	}
	return t, nil
}

func (f *remoteFS) tree(ctx context.Context, shareID, rel string) ([]shares.Entry, error) {
	key := shareID + "|" + rel
	f.mu.Lock()
	if at, ok := f.treeAt[key]; ok && time.Since(at) < cacheTTL {
		out := f.treeSet[key]
		f.mu.Unlock()
		return out, nil
	}
	f.mu.Unlock()
	h, p, err := f.addr()
	if err != nil {
		return nil, err
	}
	ents, err := f.api.Tree(ctx, h, p, f.fp, shareID, rel)
	if err != nil {
		return nil, mapErr(err)
	}
	f.mu.Lock()
	if len(f.treeAt) > 512 {
		f.treeAt, f.treeSet = map[string]time.Time{}, map[string][]shares.Entry{}
	}
	f.treeAt[key], f.treeSet[key] = time.Now(), ents
	f.mu.Unlock()
	return ents, nil
}

// --- webdav.FileSystem ---

func (f *remoteFS) Mkdir(ctx context.Context, name string, perm os.FileMode) error {
	return os.ErrPermission
}
func (f *remoteFS) RemoveAll(ctx context.Context, name string) error { return os.ErrPermission }
func (f *remoteFS) Rename(ctx context.Context, o, n string) error    { return os.ErrPermission }

func (f *remoteFS) Stat(ctx context.Context, name string) (os.FileInfo, error) {
	t, err := f.locate(ctx, name)
	if err != nil {
		return nil, err
	}
	switch {
	case t.root:
		return dirInfo{name: "/"}, nil
	case t.share.Kind == "file":
		return fileInfo{name: t.name, size: t.share.Size, mod: modTimeOf(t.share)}, nil
	case t.rel == "":
		return dirInfo{name: t.name, mod: modTimeOf(t.share)}, nil
	}
	parent, base := pathpkg.Split(t.rel)
	ents, err := f.tree(ctx, t.share.ShareID, strings.TrimSuffix(parent, "/"))
	if err != nil {
		return nil, err
	}
	for _, e := range ents {
		if e.Name == base {
			return entryInfo(e), nil
		}
	}
	return nil, os.ErrNotExist
}

func modTimeOf(s shares.Summary) time.Time { return time.Time{} }

func (f *remoteFS) OpenFile(ctx context.Context, name string, flag int, perm os.FileMode) (webdav.File, error) {
	if flag&(os.O_WRONLY|os.O_RDWR|os.O_CREATE|os.O_TRUNC|os.O_APPEND) != 0 {
		return nil, os.ErrPermission
	}
	info, err := f.Stat(ctx, name)
	if err != nil {
		return nil, err
	}
	t, _ := f.locate(ctx, name)
	if info.IsDir() {
		return &remoteDir{fs: f, t: t, info: info, ctx: ctx}, nil
	}
	etag := ""
	if t.rel != "" {
		parent, base := pathpkg.Split(t.rel)
		if ents, err := f.tree(ctx, t.share.ShareID, strings.TrimSuffix(parent, "/")); err == nil {
			for _, e := range ents {
				if e.Name == base {
					etag = e.ETag
				}
			}
		}
	}
	rctx, cancel := context.WithCancel(context.Background())
	rf := &remoteFile{fs: f, t: t, info: info, etag: etag, ctx: rctx, cancel: cancel}
	// Open the stream now so a refusal (unpaired, share stopped, device gone)
	// becomes a proper 403/404 instead of a download that dies halfway.
	if info.Size() > 0 {
		if err := rf.openBody(); err != nil {
			cancel()
			return nil, err
		}
	}
	return rf, nil
}

// --- info types ---

type dirInfo struct {
	name string
	mod  time.Time
}

func (d dirInfo) Name() string       { return d.name }
func (d dirInfo) Size() int64        { return 0 }
func (d dirInfo) Mode() fs.FileMode  { return fs.ModeDir | 0o555 }
func (d dirInfo) ModTime() time.Time { return d.mod }
func (d dirInfo) IsDir() bool        { return true }
func (d dirInfo) Sys() any           { return nil }

type fileInfo struct {
	name string
	size int64
	mod  time.Time
}

func (d fileInfo) Name() string       { return d.name }
func (d fileInfo) Size() int64        { return d.size }
func (d fileInfo) Mode() fs.FileMode  { return 0o444 }
func (d fileInfo) ModTime() time.Time { return d.mod }
func (d fileInfo) IsDir() bool        { return false }
func (d fileInfo) Sys() any           { return nil }

func entryInfo(e shares.Entry) os.FileInfo {
	if e.IsDir {
		return dirInfo{name: e.Name, mod: e.ModTime}
	}
	return fileInfo{name: e.Name, size: e.Size, mod: e.ModTime}
}

// --- directories ---

type remoteDir struct {
	fs   *remoteFS
	t    target
	info os.FileInfo
	ctx  context.Context
	list []os.FileInfo
	read bool
	next int
}

func (d *remoteDir) Close() error                   { return nil }
func (d *remoteDir) Read([]byte) (int, error)       { return 0, io.EOF }
func (d *remoteDir) Seek(int64, int) (int64, error) { return 0, nil }
func (d *remoteDir) Write([]byte) (int, error)      { return 0, os.ErrPermission }
func (d *remoteDir) Stat() (fs.FileInfo, error)     { return d.info, nil }

func (d *remoteDir) Readdir(count int) ([]fs.FileInfo, error) {
	if !d.read {
		if d.t.root {
			_, byName, err := d.fs.shareList(d.ctx)
			if err != nil {
				return nil, err
			}
			names := make([]string, 0, len(byName))
			for n := range byName {
				names = append(names, n)
			}
			sort.Strings(names)
			for _, n := range names {
				s := byName[n]
				if s.Kind == "file" {
					d.list = append(d.list, fileInfo{name: n, size: s.Size})
				} else {
					d.list = append(d.list, dirInfo{name: n})
				}
			}
		} else {
			ents, err := d.fs.tree(d.ctx, d.t.share.ShareID, d.t.rel)
			if err != nil {
				return nil, err
			}
			for _, e := range ents {
				d.list = append(d.list, entryInfo(e))
			}
		}
		d.read = true
	}
	rest := d.list[d.next:]
	if count <= 0 {
		d.next = len(d.list)
		return rest, nil
	}
	if len(rest) == 0 {
		return nil, io.EOF
	}
	if count > len(rest) {
		count = len(rest)
	}
	d.next += count
	return rest[:count], nil
}

// --- files ---

// remoteFile streams a file with HTTP range requests. Seeking only moves the
// position; the next Read opens a new range at that offset, so a media player
// jumping around does not download the whole file.
type remoteFile struct {
	fs     *remoteFS
	t      target
	info   os.FileInfo
	etag   string
	ctx    context.Context
	cancel context.CancelFunc

	pos     int64
	body    io.ReadCloser
	bodyPos int64
}

func (r *remoteFile) Write([]byte) (int, error)          { return 0, os.ErrPermission }
func (r *remoteFile) Readdir(int) ([]fs.FileInfo, error) { return nil, errors.New("not a directory") }
func (r *remoteFile) Stat() (fs.FileInfo, error)         { return r.info, nil }

func (r *remoteFile) Close() error {
	r.cancel()
	if r.body != nil {
		_ = r.body.Close()
		r.body = nil
	}
	return nil
}

func (r *remoteFile) Seek(offset int64, whence int) (int64, error) {
	var np int64
	switch whence {
	case io.SeekStart:
		np = offset
	case io.SeekCurrent:
		np = r.pos + offset
	case io.SeekEnd:
		np = r.info.Size() + offset
	default:
		return 0, errors.New("bad whence")
	}
	if np < 0 {
		return 0, errors.New("negative position")
	}
	r.pos = np
	return np, nil
}

// openBody (re)opens the stream at the current position with a range request.
func (r *remoteFile) openBody() error {
	if r.body != nil {
		_ = r.body.Close()
		r.body = nil
	}
	h, port, err := r.fs.addr()
	if err != nil {
		return err
	}
	etag := ""
	if r.pos > 0 {
		etag = r.etag // If-Range: a changed source answers 200, which we refuse below
	}
	resp, err := r.fs.api.OpenFile(r.ctx, h, port, r.fs.fp, r.t.share.ShareID, r.t.rel, r.pos, etag)
	if err != nil {
		return mapErr(err)
	}
	if r.pos > 0 && resp.StatusCode == http.StatusOK {
		resp.Body.Close()
		return errors.New("the file changed on the other device while it was being read")
	}
	r.body, r.bodyPos = resp.Body, r.pos
	return nil
}

func (r *remoteFile) Read(p []byte) (int, error) {
	size := r.info.Size()
	if r.pos >= size {
		return 0, io.EOF
	}
	if r.body == nil || r.bodyPos != r.pos {
		if err := r.openBody(); err != nil {
			return 0, err
		}
	}
	n, err := r.body.Read(p)
	r.pos += int64(n)
	r.bodyPos += int64(n)
	if err == io.EOF && r.pos < size {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}
