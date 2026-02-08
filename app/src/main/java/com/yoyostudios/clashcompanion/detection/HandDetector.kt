package com.yoyostudios.clashcompanion.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yoyostudios.clashcompanion.api.GeminiClient
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * pHash card-in-hand detection.
 *
 * Identifies which of the 8 deck cards are in the 4 visible hand slots
 * + 1 "next card" preview using 16x16 COLOR (RGB) normalized correlation.
 *
 * Uses 16 templates per deck: 8 normal + 8 desaturated (for dimmed/low-elixir cards).
 * Color provides 3x more discriminating features than greyscale, with margins of
 * 0.1+ instead of 0.003 between correct and wrong cards.
 *
 * Primary calibration: CDN card art from RoyaleAPI — downloaded automatically
 * when a deck is loaded. All 16 templates ready instantly, zero user interaction.
 *
 * Fallback calibration: Gemini 3 Flash vision — optional "Refine Calibrate"
 * button in overlay, captures live screenshot and uses AI to identify cards.
 *
 * Key design:
 *  - COLOR hashes (768 values = 16x16x3 RGB) for maximum discrimination
 *  - Per-channel normalization → handles brightness changes
 *  - Normal + desaturated templates → handles dim cards (low elixir)
 *  - <5ms per scan at 5 FPS → negligible CPU cost
 *  - Pure Kotlin math → zero dependencies
 *  - Greedy assignment → best correlation first, no slot/card reuse
 */
object HandDetector {

    private const val TAG = "ClashCompanion"
    private const val HASH_SIZE = 16
    private const val HASH_CHANNELS = 3 // R, G, B
    private const val HASH_LEN = HASH_SIZE * HASH_SIZE * HASH_CHANNELS // 768 floats
    private const val TEMPLATES_DIR = "phash_templates_rgb"
    // Low threshold — cosine similarity returns 0-1, most values will be 0.8+
    // Rely on relative ranking (highest wins among 8 candidates)
    private const val MIN_CORRELATION = 0.5f
    private const val MIN_CORRELATION_NEXT = 0.5f
    private const val MIN_MARGIN = 0.06f
    private const val MIN_MARGIN_NEXT = 0.04f
    private const val SCAN_INTERVAL_MS = 200L // ~5 FPS
    private const val DESATURATION_AMOUNT = 0.7f // How much to desaturate for dim templates

    // Temporal smoothing: reduces blink/animation noise in card portraits.
    private const val EMA_ALPHA = 0.35f

    // ── State ───────────────────────────────────────────────────────────

    /** Normal template storage: cardName → normalized 16×16 RGB (768 floats) */
    private val templates = mutableMapOf<String, FloatArray>()

    /** Desaturated template storage: cardName → normalized 16×16 RGB of dimmed card */
    private val dimTemplates = mutableMapOf<String, FloatArray>()

    /**
     * Current hand state: slotIndex → cardName.
     * Slots 0-3 = hand cards (left to right), slot 4 = next card.
     * Thread-safe via @Volatile + immutable map swap.
     */
    @Volatile
    var currentHand: Map<Int, String> = emptyMap()
        private set

    /** Convenience: cardName → slotIndex (hand slots 0-3 only) */
    val cardToSlot: Map<String, Int>
        get() = currentHand.filterKeys { it < 4 }
            .entries.associate { (slot, card) -> card to slot }

    /** The next card waiting in queue (slot 4), or null */
    val nextCard: String?
        get() = currentHand[4]

    /** Whether we have enough templates for detection (at least 4) */
    val isCalibrated: Boolean
        get() = templates.size >= 4

    /** Number of card templates captured */
    val templateCount: Int
        get() = templates.size

    /** Names of all calibrated cards */
    val calibratedCards: Set<String>
        get() = templates.keys.toSet()

    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var consecutiveNullFrames = 0
    private var emaDeckKey: String? = null
    private var emaScores: Array<FloatArray>? = null

    // ── Core Color Hash Functions ─────────────────────────────────────

    private data class WeightedSampleConfig(
        val centerX: Float,
        val centerY: Float,
        val sigmaX: Float,
        val sigmaY: Float,
        val borderCutoff: Float,
        val borderScale: Float,
        val maskElixir: Boolean
    )

    // Emphasize the portrait region and downweight borders and the elixir badge.
    // This reduces the domain gap between CDN art and in-game framed cards.
    private val HAND_SAMPLE = WeightedSampleConfig(
        centerX = 0.50f,
        centerY = 0.45f,
        sigmaX = 0.22f,
        sigmaY = 0.25f,
        borderCutoff = 0.08f,
        borderScale = 0.15f,
        maskElixir = true
    )
    private val NEXT_SAMPLE = WeightedSampleConfig(
        centerX = 0.52f,
        centerY = 0.45f,
        sigmaX = 0.24f,
        sigmaY = 0.27f,
        borderCutoff = 0.10f,
        borderScale = 0.20f,
        maskElixir = true
    )
    private val CDN_SAMPLE = WeightedSampleConfig(
        centerX = 0.50f,
        centerY = 0.48f,
        sigmaX = 0.26f,
        sigmaY = 0.28f,
        borderCutoff = 0.10f,
        borderScale = 0.20f,
        maskElixir = false
    )

    /**
     * Weighted sampling hash for a bitmap:
     * - Sample a 16x16 grid of pixels (no resize)
     * - Apply a center-weighted mask
     * - Downweight borders and (optionally) mask the elixir badge region
     *
     * Output is normalized (per-channel mean + L2) so cosineSimilarity is stable across brightness.
     */
    private fun weightedHash16x16(bitmap: Bitmap, config: WeightedSampleConfig): FloatArray {
        val result = FloatArray(HASH_LEN)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return result

        val fallbackPixel = bitmap.getPixel(w / 2, h / 2)

        for (gy in 0 until HASH_SIZE) {
            for (gx in 0 until HASH_SIZE) {
                val u = (gx + 0.5f) / HASH_SIZE
                val v = (gy + 0.5f) / HASH_SIZE

                val sx = (u * w).toInt().coerceIn(0, w - 1)
                val sy = (v * h).toInt().coerceIn(0, h - 1)

                val du = u - config.centerX
                val dv = v - config.centerY
                val wt = kotlin.math.exp(
                    -((du * du) / (config.sigmaX * config.sigmaX) + (dv * dv) / (config.sigmaY * config.sigmaY))
                ).toFloat()

                val border = minOf(u, 1f - u, v, 1f - v)
                val borderWt = if (border < config.borderCutoff) config.borderScale else 1f

                val elixirWt = if (config.maskElixir && u < 0.35f && v > 0.72f) 0.05f else 1f
                val wFinal = wt * borderWt * elixirWt

                val pixel = if (config.maskElixir && elixirWt < 0.5f) fallbackPixel else bitmap.getPixel(sx, sy)

                val idx = (gy * HASH_SIZE + gx) * HASH_CHANNELS
                result[idx] = (((pixel shr 16) and 0xFF).toFloat()) * wFinal
                result[idx + 1] = (((pixel shr 8) and 0xFF).toFloat()) * wFinal
                result[idx + 2] = ((pixel and 0xFF).toFloat()) * wFinal
            }
        }

        normalizeByMean(result)
        l2Normalize(result)
        return result
    }

    /**
     * Mean normalization (per channel) + L2 normalize:
     * reduces exposure/brightness differences while keeping values non-negative.
     */
    private fun normalizeByMean(data: FloatArray) {
        val pixelCount = data.size / HASH_CHANNELS
        for (ch in 0 until HASH_CHANNELS) {
            var sum = 0f
            for (i in 0 until pixelCount) {
                sum += data[i * HASH_CHANNELS + ch]
            }
            val mean = sum / pixelCount
            if (mean < 1e-6f) continue

            for (i in 0 until pixelCount) {
                val idx = i * HASH_CHANNELS + ch
                data[idx] = data[idx] / mean
            }
        }
    }

    private fun l2Normalize(data: FloatArray) {
        var mag = 0f
        for (v in data) mag += v * v
        val denom = kotlin.math.sqrt(mag.toDouble()).toFloat()
        if (denom < 1e-10f) return
        for (i in data.indices) data[i] = data[i] / denom
    }

    private fun ensureNormalized(data: FloatArray) {
        // Back-compat: older persisted templates were raw 0..255.
        val max = data.maxOrNull() ?: 0f
        if (max > 10f) {
            normalizeByMean(data)
            l2Normalize(data)
        }
    }

    /**
     * Cosine similarity between two raw color arrays.
     * No normalization needed — measures directional similarity
     * of raw RGB vectors regardless of brightness.
     * Returns [0, 1] where 1 = identical direction.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(magA.toDouble()) * kotlin.math.sqrt(magB.toDouble())
        return if (denom < 1e-10) 0f else (dot / denom).toFloat()
    }

    /**
     * Desaturate a bitmap by blending each pixel toward its greyscale equivalent.
     * Simulates CR's dim-card appearance when elixir is insufficient.
     * @param amount 0.0 = no change, 1.0 = fully greyscale
     */
    fun desaturate(bitmap: Bitmap, amount: Float = DESATURATION_AMOUNT): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val pixel = result.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val grey = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                val newR = (r + (grey - r) * amount).toInt().coerceIn(0, 255)
                val newG = (g + (grey - g) * amount).toInt().coerceIn(0, 255)
                val newB = (b + (grey - b) * amount).toInt().coerceIn(0, 255)
                result.setPixel(x, y, (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB)
            }
        }
        return result
    }

    // ── Card Slot Cropping ──────────────────────────────────────────────

    /**
     * Crop a card slot ROI from a full-screen frame.
     * Returns the cropped bitmap (caller must recycle), or null if out of bounds
     * or bitmap was recycled mid-operation.
     *
     * @param frame Full-screen capture bitmap
     * @param slotIndex 0-3 for hand slots, 4 for next card
     */
    fun cropCardSlot(frame: Bitmap, slotIndex: Int): Bitmap? {
        // Use frame-dimension-scaled ROIs, NOT displayMetrics-scaled
        val rois = Coordinates.getCardSlotROIs(frame.width, frame.height)
        val nextRoi = Coordinates.getNextCardROI(frame.width, frame.height)
        val roi = if (slotIndex == 4) {
            nextRoi
        } else if (slotIndex in 0 until rois.size) {
            rois[slotIndex]
        } else {
            return null
        }

        // Clamp to frame bounds
        val fx = roi.x.coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        val fy = roi.y.coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        val fw = roi.w.coerceAtMost((frame.width - fx).coerceAtLeast(1))
        val fh = roi.h.coerceAtMost((frame.height - fy).coerceAtLeast(1))

        if (fw <= 0 || fh <= 0) {
            Log.w(TAG, "PHASH: Invalid crop for slot $slotIndex: ($fx,$fy,$fw,$fh) frame=${frame.width}x${frame.height}")
            return null
        }

        return try {
            Bitmap.createBitmap(frame, fx, fy, fw, fh)
        } catch (e: Exception) {
            // Bitmap may have been recycled by ScreenCaptureService
            Log.w(TAG, "PHASH: Crop failed slot $slotIndex (bitmap recycled?): ${e.message}")
            null
        }
    }

    /**
     * Compute the color hash for a card slot: crop → 16×16 RGB → per-channel normalize.
     * Returns the normalized color hash (768 floats), or null if crop fails.
     * Recycles the crop immediately.
     */
    fun hashSlot(frame: Bitmap, slotIndex: Int): FloatArray? {
        val crop = cropCardSlot(frame, slotIndex) ?: return null
        val cfg = if (slotIndex == 4) NEXT_SAMPLE else HAND_SAMPLE
        val hash = weightedHash16x16(crop, cfg)
        crop.recycle()
        return hash
    }

    // ── CDN Template Loading (Primary) ────────────────────────────────

    /** OkHttp client for downloading card art PNGs from RoyaleAPI CDN */
    private val cdnClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Download card art PNGs from RoyaleAPI CDN and compute pHash templates.
     * Called automatically when a deck is loaded — no user interaction needed.
     * All 8 cards are downloaded in parallel for speed (~1 second total).
     *
     * @param cards List of 8 CardInfo objects from the loaded deck
     * @param context Android context for template persistence
     * @return Number of successfully loaded templates
     */
    suspend fun loadTemplatesFromCDN(
        cards: List<DeckManager.CardInfo>,
        context: Context
    ): Int = coroutineScope {
        Log.i(TAG, "PHASH: Downloading ${cards.size} card art PNGs from CDN...")
        val startTime = System.currentTimeMillis()

        // Download all cards in parallel, each with independent error handling
        val results = cards.map { card ->
            async(Dispatchers.IO) {
                try {
                    val url = DeckManager.getCardImageUrl(card)
                    val request = Request.Builder().url(url).build()
                    val response = cdnClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.w(TAG, "PHASH: CDN download failed for '${card.name}': HTTP ${response.code}")
                        return@async null
                    }

                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.w(TAG, "PHASH: CDN returned empty body for '${card.name}'")
                        return@async null
                    }

                    // Decode PNG to bitmap
                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        Log.w(TAG, "PHASH: Failed to decode PNG for '${card.name}'")
                        return@async null
                    }

                    // Handle PNG transparency: draw onto opaque white canvas
                    if (bitmap.hasAlpha()) {
                        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        Canvas(opaque).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(bitmap, 0f, 0f, null)
                        }
                        bitmap.recycle()
                        bitmap = opaque
                    }

                    // Compute weighted hash (normal) — normalized for stable cosine
                    val normalHash = weightedHash16x16(bitmap, CDN_SAMPLE)

                    // Compute color hash (desaturated — for dimmed/low-elixir cards)
                    val dimBitmap = desaturate(bitmap)
                    bitmap.recycle()
                    val dimHash = weightedHash16x16(dimBitmap, CDN_SAMPLE)
                    dimBitmap.recycle()

                    Triple(card.name, normalHash, dimHash)
                } catch (e: Exception) {
                    Log.w(TAG, "PHASH: Failed to download CDN art for '${card.name}': ${e.message}")
                    null
                }
            }
        }

        // Collect results
        var loaded = 0
        for (deferred in results) {
            val result = deferred.await() ?: continue
            templates[result.first] = result.second
            dimTemplates[result.first] = result.third
            loaded++
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "PHASH: CDN templates loaded: $loaded/${cards.size} in ${elapsed}ms")

        // Persist to disk
        if (loaded > 0) {
            saveTemplates(context)
        }

        loaded
    }

    // ── Gemini Flash Vision Auto-Calibration (Fallback) ─────────────────

    /**
     * Calibrate cards using Gemini 3 Flash vision.
     * Crops the 5 card slots, sends images to Gemini, identifies cards,
     * and stores pHash templates for each.
     *
     * @param frame Full-screen capture bitmap
     * @param deckCards List of 8 deck card names
     * @param context Android context for template persistence
     * @param onProgress Callback for status updates
     * @return Number of newly calibrated cards
     */
    suspend fun calibrateWithVision(
        frame: Bitmap,
        deckCards: List<String>,
        context: Context,
        onProgress: (String) -> Unit = {}
    ): Int {
        onProgress("Cropping card slots...")

        // Step 1: Crop and encode all slots
        val crops = mutableListOf<Pair<Int, String>>() // (slotIndex, base64)
        for (slot in 0..4) {
            val crop = cropCardSlot(frame, slot)
            if (crop != null) {
                crops.add(slot to GeminiClient.bitmapToBase64(crop))
                crop.recycle()
            } else {
                Log.w(TAG, "PHASH: Failed to crop slot $slot during calibration")
            }
        }

        if (crops.isEmpty()) {
            onProgress("ERROR: Could not crop any card slots")
            return 0
        }

        // Step 2: Build prompt
        val deckList = deckCards.joinToString(", ")
        val slotCount = crops.size
        val systemInstruction = """
You identify Clash Royale cards from screenshot crops of a player's hand.
The player's deck contains EXACTLY these ${deckCards.size} cards: $deckList.
You see $slotCount images. Images 1-4 are the visible hand cards (left to right).
Image 5 (if present) is the smaller "next card" preview near the bottom-left "Next:" label.
Cards may appear dimmed or greyed out (insufficient elixir) — still identify them.
Respond ONLY with a JSON array of card names in the EXACT order of the images.
Use EXACT card names from the deck list above. No other text.
        """.trimIndent()

        val userText = "Identify each card in these $slotCount images from my Clash Royale hand. My deck is: $deckList"

        // Step 3: Call Gemini Flash
        onProgress("Identifying cards with Gemini Flash...")
        Log.i(TAG, "PHASH: Sending $slotCount card crops to Gemini Flash")

        val startTime = System.currentTimeMillis()
        val response: String
        try {
            response = GeminiClient.chatWithImages(
                systemInstruction = systemInstruction,
                userText = userText,
                images = crops.map { it.second },
                maxTokens = 256
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "PHASH: ${e.message}")
            onProgress("ERROR: Set GEMINI_API_KEY in local.properties")
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "PHASH: Gemini calibration failed: ${e.message}", e)
            onProgress("Calibration failed: ${e.message?.take(40)}")
            return 0
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "PHASH: Gemini responded in ${elapsed}ms: $response")

        // Step 4: Parse JSON array response (strip markdown fences if present)
        val identifiedCards: List<String>
        try {
            val cleaned = GeminiClient.cleanJsonResponse(response)
            Log.i(TAG, "PHASH: Cleaned JSON: $cleaned")
            val listType = object : TypeToken<List<String>>() {}.type
            identifiedCards = Gson().fromJson(cleaned, listType)
        } catch (e: Exception) {
            Log.e(TAG, "PHASH: Failed to parse Gemini response as JSON array: ${e.message}")
            Log.e(TAG, "PHASH: Raw response was: ${response.take(200)}")
            onProgress("ERROR: Bad response from Gemini")
            return 0
        }

        if (identifiedCards.size != crops.size) {
            Log.w(TAG, "PHASH: Expected ${crops.size} card names, got ${identifiedCards.size}")
        }

        // Step 5: Store pHash templates for each identified card
        // Skip duplicates: if Gemini returns the same card for two slots
        // (e.g., Arrows in hand AND next card), only keep the FIRST occurrence
        // (main hand slots are more reliable than the small next-card preview).
        var calibrated = 0
        val validDeckNames = deckCards.toSet()
        val alreadyCalibrated = mutableSetOf<String>()

        for (i in identifiedCards.indices) {
            if (i >= crops.size) break
            val cardName = identifiedCards[i]
            val slotIndex = crops[i].first

            // Validate card name is in the deck
            if (cardName !in validDeckNames) {
                Log.w(TAG, "PHASH: Gemini returned '$cardName' which is not in deck, skipping")
                continue
            }

            // Skip duplicate: don't let next-card overwrite main hand template
            if (cardName in alreadyCalibrated) {
                Log.i(TAG, "PHASH: Skipping duplicate '$cardName' from slot $slotIndex")
                continue
            }

            // Re-crop this slot and compute pHash template
            val hash = hashSlot(frame, slotIndex)
            if (hash != null) {
                templates[cardName] = hash
                dimTemplates[cardName] = hash
                alreadyCalibrated.add(cardName)
                calibrated++
                Log.i(TAG, "PHASH: Calibrated '$cardName' from slot $slotIndex (${templates.size} total)")
            }
        }

        // Step 6: Persist templates
        if (calibrated > 0) {
            saveTemplates(context)
        }

        val msg = "Gemini identified $calibrated cards in ${elapsed}ms"
        Log.i(TAG, "PHASH: $msg (${templates.size}/8 total templates)")
        onProgress(msg)

        return calibrated
    }

    // ── Hand Scanning ───────────────────────────────────────────────────

    /**
     * Scan the current frame and identify which cards are in which slots.
     * Uses greedy assignment: best correlation first, no card or slot reuse.
     *
     * @param frame Full-screen capture bitmap
     * @param deckCards List of 8 deck card names
     * @return Map of slotIndex → cardName
     */
    fun scanHand(frame: Bitmap, deckCards: List<String>): Map<Int, String> {
        if (templates.isEmpty()) return emptyMap()

        // No frame.copy() needed — ScreenCaptureService keeps the previous frame
        // alive for one extra capture cycle (delayed recycling), so the frame
        // won't be recycled while we're cropping.

        data class Candidate(val card: String, val score: Float)

        val candidatesBySlot = mutableMapOf<Int, List<Candidate>>()

        // Initialize/reset EMA if deck changes.
        val key = deckCards.joinToString("|")
        if (emaDeckKey != key || emaScores == null || emaScores?.size != 5) {
            emaDeckKey = key
            emaScores = Array(5) { FloatArray(deckCards.size) { 0f } }
        }
        val ema = emaScores!!

        // Score all slots (0..4) vs all deck cards using the weighted hash.
        for (slot in 0..4) {
            val hash = hashSlot(frame, slot) ?: continue
            val threshold = if (slot == 4) MIN_CORRELATION_NEXT else MIN_CORRELATION
            val minMargin = if (slot == 4) MIN_MARGIN_NEXT else MIN_MARGIN

            // Update per-slot EMA for all 8 cards.
            for ((i, cardName) in deckCards.withIndex()) {
                val normalTemplate = templates[cardName] ?: continue
                ensureNormalized(normalTemplate)
                val dimTemplate = dimTemplates[cardName] ?: normalTemplate
                ensureNormalized(dimTemplate)

                val corrNormal = cosineSimilarity(hash, normalTemplate)
                val corrDim = cosineSimilarity(hash, dimTemplate)
                val corr = maxOf(corrNormal, corrDim)

                val prev = ema[slot][i]
                ema[slot][i] = if (prev == 0f) corr else (1f - EMA_ALPHA) * prev + EMA_ALPHA * corr
            }

            val scores = deckCards.mapIndexed { i, name -> Candidate(name, ema[slot][i]) }.toMutableList()
            if (scores.isEmpty()) continue
            scores.sortByDescending { it.score }
            val best = scores[0]
            val second = scores.getOrNull(1)
            val margin = best.score - (second?.score ?: 0f)

            Log.d(
                TAG,
                "PHASH: Slot $slot -> ${best.card} (${String.format(\"%.3f\", best.score)}) " +
                        "margin=${String.format(\"%.3f\", margin)}" +
                        if (second != null) " 2nd=${second.card}(${String.format(\"%.3f\", second.score)})" else \"\"
            )

            // Gate low-margin cases to prefer unknown over wrong.
            if (best.score < threshold || margin < minMargin) continue

            candidatesBySlot[slot] = scores.take(4)
        }

        // Brute-force best assignment with uniqueness (5 slots x 8 cards is tiny).
        val slotsToAssign = candidatesBySlot.keys.sortedBy { candidatesBySlot[it]?.size ?: 0 }
        var bestTotal = -1f
        var bestAssign: Map<Int, String> = emptyMap()

        fun dfs(idx: Int, used: MutableSet<String>, current: MutableMap<Int, String>, total: Float) {
            if (idx >= slotsToAssign.size) {
                if (total > bestTotal) {
                    bestTotal = total
                    bestAssign = current.toMap()
                }
                return
            }
            val slot = slotsToAssign[idx]
            val cands = candidatesBySlot[slot] ?: emptyList()
            for (cand in cands) {
                if (cand.card in used) continue
                used.add(cand.card)
                current[slot] = cand.card
                dfs(idx + 1, used, current, total + cand.score)
                current.remove(slot)
                used.remove(cand.card)
            }
            // allow leaving a slot unknown
            dfs(idx + 1, used, current, total)
        }

        dfs(0, mutableSetOf(), mutableMapOf(), 0f)

        val result = bestAssign.toMutableMap()

        // Atomic swap of hand state
        currentHand = result.toMap()

        return result
    }

    // ── Background Scanner ──────────────────────────────────────────────

    /**
     * Start continuous hand scanning in background at ~5 FPS.
     * Updates [currentHand] and [cardToSlot] automatically.
     *
     * @param deckCards The 8 deck card names to match against
     * @param onHandChanged Optional callback when hand state changes
     */
    fun startScanning(
        deckCards: List<String>,
        onHandChanged: ((Map<Int, String>) -> Unit)? = null
    ) {
        if (scanJob?.isActive == true) {
            Log.w(TAG, "PHASH: Scanning already active")
            return
        }
        if (!isCalibrated) {
            Log.w(TAG, "PHASH: Not calibrated (${templates.size} templates), can't scan")
            return
        }

        consecutiveNullFrames = 0
        Log.i(TAG, "PHASH: Starting background scan (${templates.size} templates, ${SCAN_INTERVAL_MS}ms interval)")

        scanJob = scope.launch {
            var lastHand = mapOf<Int, String>()

            while (isActive) {
                val frame = ScreenCaptureService.getLatestFrame()
                if (frame == null || frame.isRecycled) {
                    consecutiveNullFrames++
                    if (consecutiveNullFrames == 10) {
                        Log.e(TAG, "PHASH: Screen capture appears dead — no frames for ${10 * SCAN_INTERVAL_MS}ms")
                    }
                    delay(SCAN_INTERVAL_MS)
                    continue
                }

                consecutiveNullFrames = 0

                try {
                    // scanHand internally copies the frame to avoid recycling races
                    val hand = scanHand(frame, deckCards)

                    // Only notify on change
                    if (hand != lastHand) {
                        lastHand = hand.toMap()
                        onHandChanged?.invoke(hand)
                        if (hand.isNotEmpty()) {
                            val handStr = (0..3).map { hand[it] ?: "?" }.joinToString(" | ")
                            val nextStr = hand[4] ?: "?"
                            Log.i(TAG, "PHASH: Hand: $handStr | Next: $nextStr")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PHASH: Scan error: ${e.message}")
                }

                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /** Stop background scanning. */
    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        emaDeckKey = null
        emaScores = null
        Log.i(TAG, "PHASH: Scanning stopped")
    }

    /** Whether background scanning is active */
    val isScanning: Boolean
        get() = scanJob?.isActive == true

    // ── Template Persistence ────────────────────────────────────────────

    /**
     * Save all templates (normal + dim) to internal storage.
     * Format per file: 4 bytes name length + name UTF-8 + 768 floats normal + 768 floats dim.
     */
    fun saveTemplates(context: Context) {
        try {
            val dir = File(context.filesDir, TEMPLATES_DIR)
            if (!dir.exists()) dir.mkdirs()

            // Clear old files
            dir.listFiles()?.forEach { it.delete() }

            for ((cardName, normalHash) in templates) {
                val dimHash = dimTemplates[cardName] ?: normalHash
                val safeName = cardName.replace(Regex("[^a-zA-Z0-9]"), "_")
                val file = File(dir, "$safeName.phash")

                val nameBytes = cardName.toByteArray(Charsets.UTF_8)
                val buffer = ByteBuffer.allocate(4 + nameBytes.size + HASH_LEN * 4 * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(nameBytes.size)
                buffer.put(nameBytes)
                for (v in normalHash) buffer.putFloat(v)
                for (v in dimHash) buffer.putFloat(v)

                file.writeBytes(buffer.array())
            }

            Log.i(TAG, "PHASH: Saved ${templates.size} templates (normal + dim)")
        } catch (e: Exception) {
            Log.e(TAG, "PHASH: Failed to save templates: ${e.message}", e)
        }
    }

    /**
     * Load templates from internal storage.
     * @return Number of templates loaded
     */
    fun loadTemplates(context: Context): Int {
        try {
            val dir = File(context.filesDir, TEMPLATES_DIR)
            if (!dir.exists()) return 0

            val files = dir.listFiles()?.filter { it.extension == "phash" } ?: return 0
            var loaded = 0

            for (file in files) {
                try {
                    val bytes = file.readBytes()
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                    val nameLen = buffer.getInt()
                    val nameBytes = ByteArray(nameLen)
                    buffer.get(nameBytes)
                    val cardName = String(nameBytes, Charsets.UTF_8)

                    val normalHash = FloatArray(HASH_LEN)
                    for (i in normalHash.indices) {
                        normalHash[i] = buffer.getFloat()
                    }
                    templates[cardName] = normalHash

                    // Load dim hash if present (file might be old format)
                    if (buffer.remaining() >= HASH_LEN * 4) {
                        val dimHash = FloatArray(HASH_LEN)
                        for (i in dimHash.indices) {
                            dimHash[i] = buffer.getFloat()
                        }
                        dimTemplates[cardName] = dimHash
                    }

                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "PHASH: Failed to load ${file.name}: ${e.message}")
                }
            }

            if (loaded > 0) {
                Log.i(TAG, "PHASH: Loaded $loaded templates from storage (color RGB)")
            }
            return loaded
        } catch (e: Exception) {
            Log.e(TAG, "PHASH: Failed to load templates: ${e.message}", e)
            return 0
        }
    }

    /**
     * Clear all templates. Called when deck changes (old templates are invalid).
     */
    fun clearTemplates(context: Context? = null) {
        templates.clear()
        dimTemplates.clear()
        currentHand = emptyMap()

        context?.let {
            try {
                val dir = File(it.filesDir, TEMPLATES_DIR)
                dir.listFiles()?.forEach { f -> f.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "PHASH: Failed to clear template files: ${e.message}")
            }
        }

        Log.i(TAG, "PHASH: Templates cleared")
    }

    /** Clean up resources */
    fun destroy() {
        stopScanning()
        scope.cancel()
    }
}
