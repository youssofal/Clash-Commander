package com.yoyostudios.clashcompanion.util

import android.content.Context
import android.util.DisplayMetrics

/**
 * Coordinates scaled relative to screen size.
 * Calibrated on Samsung Galaxy A35 (1080 x 2340) then stored as ratios
 * so they work on any device with the same aspect ratio.
 */
object Coordinates {

    // Reference device: 1080 x 2340
    private const val REF_W = 1080f
    private const val REF_H = 2340f

    private var screenW: Float = REF_W
    private var screenH: Float = REF_H

    fun init(context: Context) {
        val dm: DisplayMetrics = context.resources.displayMetrics
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()
    }

    private fun x(refX: Float): Float = refX / REF_W * screenW
    private fun y(refY: Float): Float = refY / REF_H * screenH
    private fun pos(refX: Float, refY: Float): Pair<Float, Float> = Pair(x(refX), y(refY))

    /** Scale a reference width dimension to actual screen pixels */
    private fun dimW(refDim: Float): Int = (refDim / REF_W * screenW).toInt()

    /** Scale a reference height dimension to actual screen pixels */
    private fun dimH(refDim: Float): Int = (refDim / REF_H * screenH).toInt()

    // ── Card Slot ROIs for pHash cropping ────────────────────────────────
    // Bounding boxes for the card ART area (not the full card frame).
    // Derived from calibrated slot centers: x=350,520,690,860 at y=2130.
    // Card art crop: 140px wide × 130px tall, starting ~100px above center.
    //
    // IMPORTANT: These use RAW pixel values for the 1080x2340 screen capture
    // frame, NOT scaled via displayMetrics. ScreenCaptureService captures the
    // full resolution including nav bar, while displayMetrics excludes it.
    // Use frameScaled() variants when cropping from a captured Bitmap.

    data class CardSlotROI(val x: Int, val y: Int, val w: Int, val h: Int)

    // Reference ROI values (1080x2340 full-frame coordinates)
    // Pixel-verified from actual in-game screenshot on Samsung A35 (Feb 2026)
    // ROI covers card ART area only — excludes card frame border and elixir badge.
    // Elixir badge starts at y≈2240, so ROI ends at 2235 to avoid it.
    // CDN art (character portrait) matches this region, not the full card.
    private val REF_CARD_ROIS = listOf(
        CardSlotROI(252, 2045, 188, 190), // slot 0 (leftmost)
        CardSlotROI(455, 2045, 188, 190), // slot 1
        CardSlotROI(658, 2045, 188, 190), // slot 2
        CardSlotROI(860, 2045, 190, 190), // slot 3 (rightmost)
    )
    // Next card is smaller, bottom-left corner. Larger ROI for better match.
    private val REF_NEXT_ROI = CardSlotROI(50, 2140, 115, 155)

    /**
     * Get card slot ROIs scaled to actual frame dimensions.
     * Call with the actual captured frame size, NOT displayMetrics.
     */
    fun getCardSlotROIs(frameW: Int, frameH: Int): List<CardSlotROI> {
        if (frameW == REF_W.toInt() && frameH == REF_H.toInt()) return REF_CARD_ROIS
        val sx = frameW / REF_W
        val sy = frameH / REF_H
        return REF_CARD_ROIS.map { roi ->
            CardSlotROI(
                (roi.x * sx).toInt(), (roi.y * sy).toInt(),
                (roi.w * sx).toInt(), (roi.h * sy).toInt()
            )
        }
    }

    /** Get next-card ROI scaled to actual frame dimensions. */
    fun getNextCardROI(frameW: Int, frameH: Int): CardSlotROI {
        if (frameW == REF_W.toInt() && frameH == REF_H.toInt()) return REF_NEXT_ROI
        val sx = frameW / REF_W
        val sy = frameH / REF_H
        return CardSlotROI(
            (REF_NEXT_ROI.x * sx).toInt(), (REF_NEXT_ROI.y * sy).toInt(),
            (REF_NEXT_ROI.w * sx).toInt(), (REF_NEXT_ROI.h * sy).toInt()
        )
    }

    /** Convenience: get ROIs using raw reference values (for 1080x2340 frames) */
    val CARD_SLOT_ROIS: List<CardSlotROI> get() = REF_CARD_ROIS
    val NEXT_CARD_ROI: CardSlotROI get() = REF_NEXT_ROI

    // ── Card Slot Centers (bottom of screen, 4 visible cards) ──
    val CARD_SLOT_1 get() = pos(350f, 2130f)
    val CARD_SLOT_2 get() = pos(520f, 2130f)
    val CARD_SLOT_3 get() = pos(690f, 2130f)
    val CARD_SLOT_4 get() = pos(860f, 2130f)

    val CARD_SLOTS get() = listOf(CARD_SLOT_1, CARD_SLOT_2, CARD_SLOT_3, CARD_SLOT_4)

    // ── Arena Zones (where to place cards) ──
    val LEFT_BRIDGE    get() = pos(240f, 950f)
    val RIGHT_BRIDGE   get() = pos(840f, 950f)
    val CENTER         get() = pos(540f, 1050f)
    val BEHIND_LEFT    get() = pos(270f, 1300f)
    val BEHIND_RIGHT   get() = pos(810f, 1300f)
    val FRONT_LEFT     get() = pos(270f, 800f)
    val FRONT_RIGHT    get() = pos(810f, 800f)
    val BACK_CENTER    get() = pos(540f, 1400f)

    // Map of zone name aliases to coordinates
    val ZONE_MAP: Map<String, Pair<Float, Float>> get() = mapOf(
        "left_bridge" to LEFT_BRIDGE,
        "left" to LEFT_BRIDGE,
        "left bridge" to LEFT_BRIDGE,
        "bridge left" to LEFT_BRIDGE,
        "right_bridge" to RIGHT_BRIDGE,
        "right" to RIGHT_BRIDGE,
        "right bridge" to RIGHT_BRIDGE,
        "bridge right" to RIGHT_BRIDGE,
        "wright" to RIGHT_BRIDGE,
        "wright bridge" to RIGHT_BRIDGE,
        "write" to RIGHT_BRIDGE,
        "write bridge" to RIGHT_BRIDGE,
        "right tower" to RIGHT_BRIDGE,
        "left tower" to LEFT_BRIDGE,
        "center" to CENTER,
        "middle" to CENTER,
        "mid" to CENTER,
        "behind_left" to BEHIND_LEFT,
        "back left" to BEHIND_LEFT,
        "behind left" to BEHIND_LEFT,
        "behind_right" to BEHIND_RIGHT,
        "back right" to BEHIND_RIGHT,
        "behind right" to BEHIND_RIGHT,
        "front_left" to FRONT_LEFT,
        "front left" to FRONT_LEFT,
        "front_right" to FRONT_RIGHT,
        "front right" to FRONT_RIGHT,
        "back_center" to BACK_CENTER,
        "back" to BACK_CENTER,
        "back center" to BACK_CENTER,
        "behind king" to BACK_CENTER,
        "bridge" to LEFT_BRIDGE,
    )
}
