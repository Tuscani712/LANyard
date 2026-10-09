package transfer

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"lanyard/internal/inbox"
)

// batchBody is the exact JSON a PushOffer would send for a batch.
func batchBody(t *testing.T, files []*FileJob) []byte {
	t.Helper()
	reqs := make([]inbox.FileReq, 0, len(files))
	for _, f := range files {
		reqs = append(reqs, inbox.FileReq{RelPath: f.Rel, Size: f.Size})
	}
	b, err := json.Marshal(map[string]any{"files": reqs})
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func bigFiles(n int) []*FileJob {
	rel := strings.Repeat("x", 100000)
	files := make([]*FileJob, 0, n)
	for i := 0; i < n; i++ {
		files = append(files, &FileJob{Rel: fmt.Sprintf("%03d/%s", i, rel), Size: 1})
	}
	return files
}

// A list whose single offer would fit under the advertised byte cap is still
// split so each batch stays under cap minus the ~10% headroom.
func TestOfferBatchesRespectsHeadroom(t *testing.T) {
	maxBytes := int64(8 << 20)
	files := bigFiles(80)

	whole := batchBody(t, files)
	if int64(len(whole)) >= maxBytes {
		t.Fatalf("test setup: whole body %d does not fit under cap %d", len(whole), maxBytes)
	}

	batches := offerBatches(files, maxBytes, 0)
	if len(batches) < 2 {
		t.Fatalf("batches = %d, want >= 2 (a body under the cap but over cap-headroom must split)", len(batches))
	}
	limit := maxBytes - maxBytes/10
	seen := 0
	for i, b := range batches {
		seen += len(b)
		body := batchBody(t, b)
		if int64(len(body)) > limit {
			t.Errorf("batch %d body = %d bytes, want <= %d (cap-headroom)", i, len(body), limit)
		}
	}
	if seen != len(files) {
		t.Errorf("batches cover %d files, want %d", seen, len(files))
	}
}

// The file cap is honoured exactly, and a single file alone still gets a batch.
func TestOfferBatchesRespectsFileCap(t *testing.T) {
	files := make([]*FileJob, 7)
	for i := range files {
		files[i] = &FileJob{Rel: fmt.Sprintf("f%d", i), Size: 1}
	}
	batches := offerBatches(files, 0, 3) // maxBytes 0 -> fallback floor
	var sizes []int
	for _, b := range batches {
		sizes = append(sizes, len(b))
	}
	want := []int{3, 3, 1}
	if fmt.Sprint(sizes) != fmt.Sprint(want) {
		t.Errorf("batch sizes = %v, want %v", sizes, want)
	}
}

// A single oversized file cannot be split, so it rides alone rather than being
// dropped or merged over the limit.
func TestOfferBatchesSingleOversizedFile(t *testing.T) {
	files := []*FileJob{
		{Rel: strings.Repeat("y", 200000), Size: 1},
		{Rel: "z", Size: 1},
	}
	batches := offerBatches(files, 100<<10, 0)
	if len(batches) != 2 {
		t.Fatalf("batches = %d, want 2", len(batches))
	}
	if len(batches[0]) != 1 || batches[0][0].Rel[0] != 'y' {
		t.Errorf("oversized file not alone in its batch: %+v", batches[0])
	}
}
