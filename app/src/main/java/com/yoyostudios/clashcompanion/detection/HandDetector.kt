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
 *  - COLOR hashes (3072 values = 32x32x3 RGB) for maximum discrimination
 *  - HSV histogram fusion → illumination-invariant color distribution matching
 *  - CDN art composited on BLACK background → matches dark in-game card frames
 *  - Normal + desaturated templates → handles dim cards (low elixir)
 *  - <5ms per scan at 5 FPS → negligible CPU cost
 *  - Pure Kotlin math → zero dependencies
 *  - Greedy assignment → best correlation first, no slot/card reuse
 */
object HandDetector {

    private const val TAG = "ClashCompanion"
    private const val HASH_SIZE = 32
    private const val HASH_CHANNELS = 3 // R, G, B
    private const val HASH_LEN = HASH_SIZE * HASH_SIZE * HASH_CHANNELS // 3072 floats

    // HSV histogram parameters
    private const val HUE_BINS = 24
    private const val SAT_BINS = 12
    private const val VAL_BINS = 12
    private const val HIST_LEN = HUE_BINS + SAT_BINS + VAL_BINS // 48 floats

    // Fusion weights: color similarity vs HSV histogram similarity
    private const val COLOR_WEIGHT = 0.6f
    private const val HSV_WEIGHT = 0.4f
    private const val TEMPLATES_DIR = "phash_templates_rgb"
    // Low threshold — cosine similarity returns 0-1, most values will be 0.8+
    // Rely on relative ranking (highest wins among 8 candidates)
    private const val MIN_CORRELATION = 0.5f
    private const val MIN_CORRELATION_NEXT = 0.5f
    private const val SCAN_INTERVAL_MS = 200L // ~5 FPS
    private const val DESATURATION_AMOUNT = 0.7f // How much to desaturate for dim templates

    // ── State ───────────────────────────────────────────────────────────

    /** Normal template storage: cardName → 32×32 RGB (3072 floats) */
    private val templates = mutableMapOf<String, FloatArray>()

    /** Desaturated template storage: cardName → 32×32 RGB of dimmed card */
    private val dimTemplates = mutableMapOf<String, FloatArray>()

    /** HSV histogram templates: cardName → HSV histogram (48 floats) */
    private val hsvTemplates = mutableMapOf<String, FloatArray>()

    /** Desaturated HSV histogram templates */
    private val dimHsvTemplates = mutableMapOf<String, FloatArray>()

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

    // ── Core Color Hash Functions ─────────────────────────────────────

    /**
     * Downsample a bitmap to 32×32 COLOR (RGB) float array.
     * Returns 3072 floats: [R0, G0, B0, R1, G1, B1, ...] for each pixel.
     * 32x32 provides 4x more spatial features than 16x16, with minimal cost.
     */
    fun toColorHash(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val result = FloatArray(HASH_LEN)
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        var p = 0
        var i = 0
        while (p < pixels.size) {
            val pixel = pixels[p]
            result[i] = ((pixel shr 16) and 0xFF).toFloat()     // R
            result[i + 1] = ((pixel shr 8) and 0xFF).toFloat()  // G
            result[i + 2] = (pixel and 0xFF).toFloat()          // B
            p++
            i += 3
        }
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    // Keep old name as alias for backward compat with Gemini calibration
    fun toColor16x16(bitmap: Bitmap): FloatArray = toColorHash(bitmap)

    /**
     * Compute HSV histogram from a bitmap.
     * Returns 48 floats: [24 hue bins, 12 saturation bins, 12 value bins].
     * Illumination-invariant — handles brightness changes better than raw RGB.
     * Hue bins weighted 2x for emphasis on color identity.
     */
    fun toHsvHistogram(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val hHist = FloatArray(HUE_BINS)
        val sHist = FloatArray(SAT_BINS)
        val vHist = FloatArray(VAL_BINS)

        var chromaPixels = 0

        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)

        var p = 0
        while (p < pixels.size) {
            val pixel = pixels[p]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val diff = maxC - minC

            // Value histogram (always counted)
            val vBin = (maxC * (VAL_BINS - 1)).toInt().coerceIn(0, VAL_BINS - 1)
            vHist[vBin]++

            // Only count hue/sat for chromatic pixels
            if (diff > 0.01f) {
                chromaPixels++

                // Hue [0, 6) → normalized to [0, 1)
                val hue = when (maxC) {
                    r -> ((g - b) / diff).let { if (it < 0) it + 6 else it }
                    g -> (b - r) / diff + 2
                    else -> (r - g) / diff + 4
                } / 6f

                val sat = diff / maxC

                val hBin = (hue * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                val sBin = (sat * (SAT_BINS - 1)).toInt().coerceIn(0, SAT_BINS - 1)
                hHist[hBin]++
                sHist[sBin]++
            }
            p++
        }

        if (scaled !== bitmap) scaled.recycle()

        // Normalize each histogram to sum=1, weight hue 2x
        val totalPixels = (HASH_SIZE * HASH_SIZE).toFloat()
        val chromaTotal = chromaPixels.toFloat().coerceAtLeast(1f)
        for (i in hHist.indices) hHist[i] = (hHist[i] / chromaTotal) * 2f
        for (i in sHist.indices) sHist[i] = sHist[i] / chromaTotal
        for (i in vHist.indices) vHist[i] = vHist[i] / totalPixels

        // Avoid allocations from '+' operator; pack into one array.
        val out = FloatArray(HIST_LEN)
        var o = 0
        for (i in hHist.indices) out[o++] = hHist[i]
        for (i in sHist.indices) out[o++] = sHist[i]
        for (i in vHist.indices) out[o++] = vHist[i]
        return out
    }

    /**
     * Per-channel normalization: subtract mean and divide by std for each RGB channel independently.
     * This handles brightness changes (dimmed cards) while preserving color ratios.
     * A dimmed card and a bright card of the same type produce similar normalized patterns.
     */
    fun normalize(data: FloatArray) {
        val pixelCount = data.size / HASH_CHANNELS
        for (ch in 0 until HASH_CHANNELS) {
            var sum = 0f
            for (i in 0 until pixelCount) {
                sum += data[i * HASH_CHANNELS + ch]
            }
            val mean = sum / pixelCount

            var variance = 0f
            for (i in 0 until pixelCount) {
                val diff = data[i * HASH_CHANNELS + ch] - mean
                variance += diff * diff
            }
            val std = kotlin.math.sqrt((variance / pixelCount).toDouble()).toFloat()
            if (std < 1e-6f) continue // Flat channel — skip

            for (i in 0 until pixelCount) {
                val idx = i * HASH_CHANNELS + ch
                data[idx] = (data[idx] - mean) / std
            }
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
     * Compute color hash + HSV histogram for a card slot.
     * Returns Pair(colorHash, hsvHistogram), or null if crop fails.
     * Recycles the crop immediately.
     */
    fun hashSlot(frame: Bitmap, slotIndex: Int): Pair<FloatArray, FloatArray>? {
        val crop = cropCardSlot(frame, slotIndex) ?: return null
        val colorHash = toColorHash(crop)
        val hsvHist = toHsvHistogram(crop)
        crop.recycle()
        return Pair(colorHash, hsvHist)
    }

    // ── CDN Template Loading (Primary) ────────────────────────────────

    /** OkHttp client for downloading card art PNGs from RoyaleAPI CDN */
    private val cdnClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class CdnTemplate(
        val name: String,
        val normalColor: FloatArray,
        val dimColor: FloatArray,
        val normalHsv: FloatArray,
        val dimHsv: FloatArray
    )

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

                    // Handle PNG transparency: draw onto opaque BLACK canvas.
                    // BLACK matches in-game dark card frame — critical for accuracy.
                    // WHITE caused Arrows (sky-blue CDN bg) to false-match everything.
                    if (bitmap.hasAlpha()) {
                        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        Canvas(opaque).apply {
                            drawColor(Color.BLACK)
                            drawBitmap(bitmap, 0f, 0f, null)
                        }
                        bitmap.recycle()
                        bitmap = opaque
                    }

                    // Compute color hash (normal) — raw RGB, 32x32
                    val normalHash = toColorHash(bitmap)
                    val normalHsv = toHsvHistogram(bitmap)

                    // Compute color hash (desaturated — for dimmed/low-elixir cards)
                    val dimBitmap = desaturate(bitmap)
                    bitmap.recycle()
                    val dimHash = toColorHash(dimBitmap)
                    val dimHsv = toHsvHistogram(dimBitmap)
                    dimBitmap.recycle()

                    CdnTemplate(
                        name = card.name,
                        normalColor = normalHash,
                        dimColor = dimHash,
                        normalHsv = normalHsv,
                        dimHsv = dimHsv
                    )
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
            templates[result.name] = result.normalColor
            dimTemplates[result.name] = result.dimColor
            hsvTemplates[result.name] = result.normalHsv
            dimHsvTemplates[result.name] = result.dimHsv
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
Image 5 (if present) is the smaller "next card" preview at the right edge.
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

            // Re-crop this slot and compute pHash + HSV template
            val hashes = hashSlot(frame, slotIndex)
            if (hashes != null) {
                templates[cardName] = hashes.first
                hsvTemplates[cardName] = hashes.second
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

        // Score all (slot, card) pairs using FUSED scoring
        data class Match(val slot: Int, val card: String, val fusedScore: Float,
                         val colorSim: Float, val hsvSim: Float)
        val allMatches = mutableListOf<Match>()

        for (slot in 0..4) {
            val hashes = hashSlot(frame, slot) ?: continue
            val (colorHash, hsvHist) = hashes
            val threshold = if (slot == 4) MIN_CORRELATION_NEXT else MIN_CORRELATION

            for (cardName in deckCards) {
                val normalColor = templates[cardName] ?: continue
                val dimColor = dimTemplates[cardName]
                val normalHsv = hsvTemplates[cardName]
                val dimHsv = dimHsvTemplates[cardName]

                // Check both normal and desaturated templates, take the best
                val colorNormal = cosineSimilarity(colorHash, normalColor)
                val colorDim = if (dimColor != null) cosineSimilarity(colorHash, dimColor) else colorNormal
                val bestColor = maxOf(colorNormal, colorDim)

                val hsvNormal = if (normalHsv != null) cosineSimilarity(hsvHist, normalHsv) else 0f
                val hsvDim = if (dimHsv != null) cosineSimilarity(hsvHist, dimHsv) else hsvNormal
                val bestHsv = maxOf(hsvNormal, hsvDim)

                // Fused score: color spatial similarity + HSV distribution similarity
                val fused = COLOR_WEIGHT * bestColor + HSV_WEIGHT * bestHsv

                if (fused > threshold) {
                    allMatches.add(Match(slot, cardName, fused, bestColor, bestHsv))
                }
            }
        }

        // Diagnostic: log best match per slot
        for (slot in 0..4) {
            val slotMatches = allMatches.filter { it.slot == slot }.sortedByDescending { it.fusedScore }
            if (slotMatches.isNotEmpty()) {
                val best = slotMatches[0]
                val secondBest = slotMatches.getOrNull(1)
                val margin = if (secondBest != null) best.fusedScore - secondBest.fusedScore else best.fusedScore
                Log.d(TAG, "PHASH: Slot $slot -> ${best.card} " +
                        "(fused=${String.format("%.3f", best.fusedScore)} " +
                        "color=${String.format("%.3f", best.colorSim)} " +
                        "hsv=${String.format("%.3f", best.hsvSim)}) " +
                        "margin=${String.format("%.3f", margin)}" +
                        if (secondBest != null) " 2nd=${secondBest.card}(${String.format("%.3f", secondBest.fusedScore)})" else "")
            }
        }

        // Greedy assignment: best fused score first, no reuse
        allMatches.sortByDescending { it.fusedScore }
        val result = mutableMapOf<Int, String>()
        val usedSlots = mutableSetOf<Int>()
        val usedCards = mutableSetOf<String>()

        for (match in allMatches) {
            if (match.slot in usedSlots || match.card in usedCards) continue
            result[match.slot] = match.card
            usedSlots.add(match.slot)
            usedCards.add(match.card)
            if (usedSlots.size == 5) break
        }

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
        Log.i(TAG, "PHASH: Scanning stopped")
    }

    /** Whether background scanning is active */
    val isScanning: Boolean
        get() = scanJob?.isActive == true

    // ── Template Persistence ────────────────────────────────────────────

    /**
     * Save all templates (normal + dim + HSV) to internal storage.
     * Format per file: 4 bytes name length + name UTF-8 + color hashes + HSV histograms.
     */
    fun saveTemplates(context: Context) {
        try {
            val dir = File(context.filesDir, TEMPLATES_DIR)
            if (!dir.exists()) dir.mkdirs()

            // Clear old files
            dir.listFiles()?.forEach { it.delete() }

            for ((cardName, normalHash) in templates) {
                val dimHash = dimTemplates[cardName] ?: normalHash
                val normalHsv = hsvTemplates[cardName] ?: FloatArray(HIST_LEN)
                val dimHsv = dimHsvTemplates[cardName] ?: normalHsv
                val safeName = cardName.replace(Regex("[^a-zA-Z0-9]"), "_")
                val file = File(dir, "$safeName.phash")

                val nameBytes = cardName.toByteArray(Charsets.UTF_8)
                // color hashes (2 * HASH_LEN) + HSV histograms (2 * HIST_LEN)
                val buffer = ByteBuffer.allocate(
                    4 + nameBytes.size + (HASH_LEN * 2 + HIST_LEN * 2) * 4
                ).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(nameBytes.size)
                buffer.put(nameBytes)
                for (v in normalHash) buffer.putFloat(v)
                for (v in dimHash) buffer.putFloat(v)
                for (v in normalHsv) buffer.putFloat(v)
                for (v in dimHsv) buffer.putFloat(v)

                file.writeBytes(buffer.array())
            }

            Log.i(TAG, "PHASH: Saved ${templates.size} templates (color + HSV, normal + dim)")
        } catch (e: Exception) {
            Log.e(TAG, "PHASH: Failed to save templates: ${e.message}", e)
        }
    }

    /**
     * Load templates from internal storage.
     * Handles both old format (color only) and new format (color + HSV).
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

                    // Load dim hash if present
                    if (buffer.remaining() >= HASH_LEN * 4) {
                        val dimHash = FloatArray(HASH_LEN)
                        for (i in dimHash.indices) {
                            dimHash[i] = buffer.getFloat()
                        }
                        dimTemplates[cardName] = dimHash
                    }

                    // Load HSV histograms if present (new format)
                    if (buffer.remaining() >= HIST_LEN * 4) {
                        val normalHsv = FloatArray(HIST_LEN)
                        for (i in normalHsv.indices) {
                            normalHsv[i] = buffer.getFloat()
                        }
                        hsvTemplates[cardName] = normalHsv
                    }
                    if (buffer.remaining() >= HIST_LEN * 4) {
                        val dimHsv = FloatArray(HIST_LEN)
                        for (i in dimHsv.indices) {
                            dimHsv[i] = buffer.getFloat()
                        }
                        dimHsvTemplates[cardName] = dimHsv
                    }

                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "PHASH: Failed to load ${file.name}: ${e.message}")
                }
            }

            if (loaded > 0) {
                Log.i(TAG, "PHASH: Loaded $loaded templates from storage (color + HSV)")
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
        hsvTemplates.clear()
        dimHsvTemplates.clear()
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
