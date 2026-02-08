# pHash Hand Detection Fix — Complete Analysis

## Summary

**Root cause found:** 2 out of 4 hand cards are misidentified because:
1. **CDN art is composited onto WHITE background** — the transparent corners become white, which makes cards with sky-blue backgrounds (especially Arrows) match everything
2. **ROI crop region didn’t match CDN art** — the old ROI included card frame + elixir badge; switching to an art/portrait-only ROI (and widening slightly) puts in-game crops in the same “feature space” as CDN portraits
3. **16x16 hash is too low resolution** — insufficient spatial detail to distinguish similar-looking cards

## Tested on Real Screenshot

Samsung A35, 1080x2340, in-game match with:
- Hand: Mini P.E.K.K.A. (4), Giant (5), Arrows (3), Musketeer (4)
- Next: Fireball (4)

### Current Code Results
| Slot | Expected | Best Match | Correct? | Margin |
|------|----------|-----------|----------|--------|
| 0 | Mini P.E.K.K.A. | Arrows | ❌ | 0.023 |
| 1 | Giant | Giant | ✅ | 0.020 |
| 2 | Arrows | Arrows | ✅ | 0.074 |
| 3 | Musketeer | Arrows | ❌ | 0.024 |
| Next | Fireball | Fireball | ✅ | 0.047 |

**50% accuracy. Arrows dominates as false match due to its sky-blue CDN background matching the in-game blue UI.**

## Fixes Applied (all verified)

### Fix 1: BLACK background for CDN compositing
- **Before:** `Canvas(opaque).apply { drawColor(Color.WHITE) }`
- **After:** `Canvas(opaque).apply { drawColor(Color.BLACK) }`
- **Impact:** Alone brings accuracy from 2/4 → 4/4

### Fix 2: 32x32 hash instead of 16x16
- 4x more spatial features (3072 vs 768 floats)
- Better discrimination of fine card art details
- Negligible performance cost (resize is the same, just larger target)

### Fix 3: Fused scoring (Color + HSV Histogram)
- **Color cosine similarity** captures spatial structure
- **HSV histogram** captures color distribution (illumination invariant)
- **Fused score:** `0.6 * colorSim + 0.4 * hsvSim`
- **Impact:** Margins increase from 0.02 → 0.06-0.23

### Fix 4: Corrected ROI coordinates
Pixel-measured from actual Samsung A35 screenshot:

| Slot | Code ROI | New ROI (art-only) | Change |
|------|----------|--------------|--------|
| 0 | (248, 2025, 177, 215) | (252, 2045, 188, 190) | x+4, y+20, w+11, h-25 |
| 1 | (452, 2025, 176, 215) | (455, 2045, 188, 190) | x+3, y+20, w+12, h-25 |
| 2 | (654, 2025, 177, 215) | (658, 2045, 188, 190) | x+4, y+20, w+11, h-25 |
| 3 | (858, 2025, 177, 215) | (860, 2045, 190, 190) | x+2, y+20, w+13, h-25 |
| Next | (61, 2215, 79, 110) | (50, 2140, 115, 155) | bigger crop area |

### Optional Idea (Not Implemented): Elixir Digit OCR
This is a possible future fallback if pHash ever becomes ambiguous:
- OCR the elixir badge digit (1-10) and use it to narrow candidates within the 8-card deck.

## Results After Fixes

### Fixed Algorithm (5/5 correct, 4/4 dimmed correct)
| Slot | Expected | Best Match | Score | Margin | Correct? |
|------|----------|-----------|-------|--------|----------|
| 0 | Mini P.E.K.K.A. | Mini P.E.K.K.A. | 0.683 | 0.100 | ✅ |
| 1 | Giant | Giant | 0.866 | 0.150 | ✅ |
| 2 | Arrows | Arrows | 0.827 | 0.092 | ✅ |
| 3 | Musketeer | Musketeer | 0.776 | 0.063 | ✅ |
| Next | Fireball | Fireball | 0.893 | 0.226 | ✅ |

### Dimmed Cards (70% desaturation, simulating low elixir)
| Slot | Expected | Correct? | Margin |
|------|----------|----------|--------|
| 0 | Mini P.E.K.K.A. | ✅ | 0.064 |
| 1 | Giant | ✅ | 0.149 |
| 2 | Arrows | ✅ | 0.040 |
| 3 | Musketeer | ✅ | 0.068 |

## CDN Art vs In-Game Art Discrepancy

**CDN card art (from RoyaleAPI/cr-api-assets):**
- 150x180 RGBA with transparency (rounded corners)
- Just the character portrait, NO elixir badge
- NO card frame, NO level stars
- 33-40% transparent pixels

**In-game card art:**
- Card frame (dark blue/gray border)
- Character portrait
- Pink/magenta elixir badge at bottom with white number
- Level stars
- Full opaque rendering

**Key insight:** The elixir badge takes up the bottom ~25% of each card and differs per card. By cropping the ROI to END at y=2235 (above the badge), we compare only the portrait areas, which are what the CDN provides.

## Performance Impact
- 32x32 hash: ~0.1ms extra per card (negligible)
- HSV histogram: ~0.2ms per card (computed alongside color hash)
- **Total: well within 200ms scan interval** (device-dependent)
