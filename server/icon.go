package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"log"
	"math"
	"sync"
)

// The tray icon, drawn here rather than shipped as a file.
//
// It is the same mark the launcher icon uses in the headset — a shell prompt: a
// chevron and a cursor — and drawing it from the same geometry is the only way
// the two stay the same mark. A PNG beside the code is a copy that drifts the
// first time one of them is adjusted.
//
// image/png is in the standard library, so this costs the binary nothing but
// itself, and cgo stays off.

// 22 px is what GNOME's panel renders a tray icon at. Drawn at exactly that size
// rather than scaled down from something larger, which smears a two-stroke shape.
const iconSize = 22

// Supersampled and then averaged down: it is anti-aliasing for the price of a
// loop, and without it a diagonal stroke this thin is a staircase.
const iconScale = 4

var (
	iconOnce  sync.Once
	iconBytes []byte
)

func trayIconPNG() []byte {
	iconOnce.Do(func() {
		iconBytes = drawIcon()
	})
	return iconBytes
}

func drawIcon() []byte {
	const big = iconSize * iconScale
	canvas := image.NewNRGBA(image.Rect(0, 0, iconSize, iconSize))

	// The same proportions as the launcher's vector, expressed as fractions of the
	// box so the two cannot drift apart when either size changes.
	stroke := 0.115 * big
	chevronTop := point{0.30 * big, 0.28 * big}
	chevronTip := point{0.52 * big, 0.50 * big}
	chevronBottom := point{0.30 * big, 0.72 * big}
	cursorLeft := point{0.60 * big, 0.70 * big}
	cursorRight := point{0.80 * big, 0.70 * big}

	for y := 0; y < iconSize; y++ {
		for x := 0; x < iconSize; x++ {
			hits := 0
			for sy := 0; sy < iconScale; sy++ {
				for sx := 0; sx < iconScale; sx++ {
					p := point{float64(x*iconScale + sx), float64(y*iconScale + sy)}
					if onStroke(p, chevronTop, chevronTip, stroke) ||
						onStroke(p, chevronTip, chevronBottom, stroke) ||
						onStroke(p, cursorLeft, cursorRight, stroke) {
						hits++
					}
				}
			}
			if hits == 0 {
				continue
			}
			alpha := uint8(255 * hits / (iconScale * iconScale))
			// White, and left to the panel to tint. GNOME recolours a tray icon to
			// match its own theme, and a coloured one fights that.
			canvas.SetNRGBA(x, y, color.NRGBA{R: 255, G: 255, B: 255, A: alpha})
		}
	}

	var out bytes.Buffer
	if err := png.Encode(&out, canvas); err != nil {
		log.Printf("cannot draw the tray icon: %v", err)
		return nil
	}
	return out.Bytes()
}

type point struct{ x, y float64 }

// onStroke reports whether a sample falls within half a stroke width of the
// segment, with round ends — the same cap the drawn glyphs in the headset use,
// and the reason a chevron's tip survives being small.
func onStroke(p, a, b point, width float64) bool {
	return distanceToSegment(p, a, b) <= width/2
}

func distanceToSegment(p, a, b point) float64 {
	dx, dy := b.x-a.x, b.y-a.y
	length := dx*dx + dy*dy
	if length == 0 {
		return math.Hypot(p.x-a.x, p.y-a.y)
	}
	t := ((p.x-a.x)*dx + (p.y-a.y)*dy) / length
	t = math.Max(0, math.Min(1, t))
	return math.Hypot(p.x-(a.x+t*dx), p.y-(a.y+t*dy))
}
