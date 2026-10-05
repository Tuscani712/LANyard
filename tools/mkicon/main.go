// mkicon turns icons/icon.png into the app's square icon set: a multi-size
// Windows .ico (used for the binary/desktop icon via a .syso) and PNGs for the
// web UI. Run from the repository root: go run ./tools/mkicon
package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "mkicon:", err)
		os.Exit(1)
	}
}

func run() error {
	src, err := loadPNG("icons/icon.png")
	if err != nil {
		return err
	}
	bg := color.RGBAModel.Convert(src.At(4, 4)).(color.RGBA)

	sizes := []int{16, 24, 32, 48, 64, 128, 256}
	var frames [][]byte
	for _, s := range sizes {
		sq := square(src, s, bg)
		var buf bytes.Buffer
		if err := png.Encode(&buf, sq); err != nil {
			return err
		}
		frames = append(frames, buf.Bytes())
	}
	if err := os.WriteFile("icons/icon.ico", encodeICO(sizes, frames), 0o644); err != nil {
		return err
	}
	// A copy beside the tray code, embedded into the binary so the tray does
	// not depend on the resource id rsrc chose.
	if err := os.WriteFile(filepath.FromSlash("cmd/lanyard/icon.ico"), encodeICO(sizes, frames), 0o644); err != nil {
		return err
	}

	// A 192 px PNG for the browser tab / header logo.
	web := square(src, 192, bg)
	var wb bytes.Buffer
	if err := png.Encode(&wb, web); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.FromSlash("internal/uiserver/web/icon.png"), wb.Bytes(), 0o644); err != nil {
		return err
	}
	fmt.Printf("wrote icons/icon.ico (%d sizes) and internal/uiserver/web/icon.png (192x192)\n", len(sizes))
	return nil
}

func loadPNG(path string) (image.Image, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	img, err := png.Decode(f)
	if err != nil {
		return nil, err
	}
	return img, nil
}

// square scales src to fit inside a size x size canvas and centres it on bg.
func square(src image.Image, size int, bg color.RGBA) *image.RGBA {
	dst := image.NewRGBA(image.Rect(0, 0, size, size))
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			dst.SetRGBA(x, y, bg)
		}
	}
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	scale := float64(size) / float64(sw)
	if hs := float64(size) / float64(sh); hs < scale {
		scale = hs
	}
	dw, dh := int(float64(sw)*scale+0.5), int(float64(sh)*scale+0.5)
	ox, oy := (size-dw)/2, (size-dh)/2
	drawScaled(dst, src, ox, oy, dw, dh)
	return dst
}

// drawScaled box-samples src into the dst rectangle (good enough for downscaling).
func drawScaled(dst *image.RGBA, src image.Image, ox, oy, dw, dh int) {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	for y := 0; y < dh; y++ {
		sy0 := b.Min.Y + y*sh/dh
		sy1 := b.Min.Y + (y+1)*sh/dh
		if sy1 <= sy0 {
			sy1 = sy0 + 1
		}
		for x := 0; x < dw; x++ {
			sx0 := b.Min.X + x*sw/dw
			sx1 := b.Min.X + (x+1)*sw/dw
			if sx1 <= sx0 {
				sx1 = sx0 + 1
			}
			var r, g, bb, a, n uint64
			for sy := sy0; sy < sy1; sy++ {
				for sx := sx0; sx < sx1; sx++ {
					c := color.RGBAModel.Convert(src.At(sx, sy)).(color.RGBA)
					// premultiply so transparent areas do not darken the result
					r += uint64(c.R) * uint64(c.A)
					g += uint64(c.G) * uint64(c.A)
					bb += uint64(c.B) * uint64(c.A)
					a += uint64(c.A)
					n++
				}
			}
			if n == 0 || a == 0 {
				continue
			}
			dst.SetRGBA(ox+x, oy+y, color.RGBA{
				R: uint8(r / a), G: uint8(g / a), B: uint8(bb / a),
				A: uint8(a / n),
			})
		}
	}
}

// encodeICO writes a PNG-compressed ICO (supported by Windows Vista and later).
func encodeICO(sizes []int, frames [][]byte) []byte {
	var buf bytes.Buffer
	binary.Write(&buf, binary.LittleEndian, uint16(0))          // reserved
	binary.Write(&buf, binary.LittleEndian, uint16(1))          // type: icon
	binary.Write(&buf, binary.LittleEndian, uint16(len(sizes))) // count
	offset := 6 + 16*len(sizes)
	for i, s := range sizes {
		w := byte(s)
		if s >= 256 {
			w = 0
		}
		buf.WriteByte(w)                                    // width (0 = 256)
		buf.WriteByte(w)                                    // height
		buf.WriteByte(0)                                    // palette
		buf.WriteByte(0)                                    // reserved
		binary.Write(&buf, binary.LittleEndian, uint16(1))  // planes
		binary.Write(&buf, binary.LittleEndian, uint16(32)) // bpp
		binary.Write(&buf, binary.LittleEndian, uint32(len(frames[i])))
		binary.Write(&buf, binary.LittleEndian, uint32(offset))
		offset += len(frames[i])
	}
	for _, f := range frames {
		buf.Write(f)
	}
	return buf.Bytes()
}
