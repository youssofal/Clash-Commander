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
    )
}
