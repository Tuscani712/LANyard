package uiserver

import (
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// fsEntry is one immediate child of a local directory, as shown by the native
// file explorer in the UI.
type fsEntry struct {
	Name     string    `json:"name"`
	Path     string    `json:"path"`
	IsDir    bool      `json:"is_dir"`
	Size     int64     `json:"size"`
	Modified time.Time `json:"modified"`
	Hidden   bool      `json:"hidden"`
}

type fsQuick struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Kind string `json:"kind"`
}

// firstExisting returns the first of the candidate paths that exists and is a
// directory. Windows often redirects Desktop/Documents into OneDrive, so both
// the profile and the OneDrive root are probed.
func firstExisting(cands ...string) string {
	for _, p := range cands {
		if p == "" {
			continue
		}
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			return p
		}
	}
	return ""
}

func quickFolders() []fsQuick {
	home, _ := os.UserHomeDir()
	one := os.Getenv("OneDrive")
	sub := func(name string) []string {
		var out []string
		if home != "" {
			out = append(out, filepath.Join(home, name))
		}
		if one != "" {
			out = append(out, filepath.Join(one, name))
		}
		return out
	}
	defs := []struct {
		name, kind string
		cands      []string
	}{
		{"Desktop", "desktop", sub("Desktop")},
		{"Documents", "documents", sub("Documents")},
		{"Downloads", "downloads", sub("Downloads")},
		{"Pictures", "pictures", sub("Pictures")},
		{"Music", "music", sub("Music")},
		{"Videos", "videos", sub("Videos")},
	}
	out := make([]fsQuick, 0, len(defs))
	for _, d := range defs {
		if p := firstExisting(d.cands...); p != "" {
			out = append(out, fsQuick{Name: d.name, Path: p, Kind: d.kind})
		}
	}
	return out
}

// handleFSRoots returns the home directory and the quick-access folders so the
// explorer's sidebar can be built without guessing OS layout.
func (s *Server) handleFSRoots(w http.ResponseWriter, r *http.Request) {
	home, _ := os.UserHomeDir()
	writeJSON(w, map[string]any{
		"home":  home,
		"quick": quickFolders(),
	})
}

// handleFSList lists the immediate children of a local directory. Empty path
// means the user's home directory. It is token-gated like every other API.
func (s *Server) handleFSList(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimSpace(r.URL.Query().Get("path"))
	if p == "" {
		p, _ = os.UserHomeDir()
	}
	if p == "" {
		http.Error(w, "no path", http.StatusBadRequest)
		return
	}
	p = filepath.Clean(p)
	st, err := os.Stat(p)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if !st.IsDir() {
		http.Error(w, "not a directory", http.StatusBadRequest)
		return
	}
	ents, err := os.ReadDir(p)
	if err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	const maxEntries = 5000
	entries := make([]fsEntry, 0, len(ents))
	for _, e := range ents {
		if len(entries) >= maxEntries {
			break
		}
		name := e.Name()
		full := filepath.Join(p, name)
		fe := fsEntry{Name: name, Path: full, IsDir: e.IsDir(), Hidden: strings.HasPrefix(name, ".")}
		if info, err := e.Info(); err == nil {
			fe.Size = info.Size()
			fe.Modified = info.ModTime()
			if name != "" && strings.HasPrefix(name, ".") {
				fe.Hidden = true
			}
			// Windows hidden attribute.
			if info.Mode()&os.ModeSymlink == 0 && isHiddenWindows(info) {
				fe.Hidden = true
			}
		}
		entries = append(entries, fe)
	}
	sort.SliceStable(entries, func(i, j int) bool {
		if entries[i].IsDir != entries[j].IsDir {
			return entries[i].IsDir
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	parent := filepath.Dir(p)
	if parent == p {
		parent = ""
	}
	writeJSON(w, map[string]any{
		"path":    p,
		"parent":  parent,
		"entries": entries,
	})
}
