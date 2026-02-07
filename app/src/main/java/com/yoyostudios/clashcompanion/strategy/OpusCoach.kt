package com.yoyostudios.clashcompanion.strategy

import android.content.Context
import android.util.Log
import com.yoyostudios.clashcompanion.api.AnthropicClient
import com.yoyostudios.clashcompanion.deck.DeckManager
import java.io.File

/**
 * Pre-match deck analyzer using Claude Opus.
 * Generates a strategic playbook JSON that Haiku uses during gameplay (M8).
 *
 * Coach + Player architecture:
 *   Opus (this) = Coach: deep analysis once per deck load, 15-30s
 *   Haiku (M8)  = Player: real-time decisions using this playbook, <800ms
 */
object OpusCoach {

    private const val TAG = "ClashCompanion"
    private const val MODEL = "claude-opus-4-6"
    private const val PLAYBOOK_FILE = "playbook.json"

    /** Cached playbook JSON from the last analysis */
    var cachedPlaybook: String? = null
        private set

    private val SYSTEM_PROMPT = """
You are an expert Clash Royale analyst. Analyze this deck for a real-time voice
command system that will make tactical decisions during live gameplay.

Generate a comprehensive strategic playbook as JSON with these sections:

1. "archetype": Name and one-sentence playstyle summary

2. "win_conditions": Array of primary and secondary win conditions with strategy

3. "defense_table": Object mapping common opponent cards to best counter FROM THIS
   DECK. Each entry: counter_card, placement, timing, elixir_trade, note.
   Cover: Hog Rider, Balloon, Golem, Royal Giant, Giant, Mega Knight, Elite
   Barbarians, Goblin Barrel, Lava Hound, Graveyard, Minion Horde, Skeleton Army,
   Wizard, Witch, Sparky, X-Bow, Mortar, Three Musketeers, P.E.K.K.A, Prince

4. "offense_plays": Recommended offensive sequences with cards, order, placement

5. "card_placement_defaults": For each card in deck, default offensive + defensive zone

6. "synergies": Key card combinations and how they work

7. "never_do": Critical mistakes to avoid with this specific deck

8. "elixir_management": When to push vs defend based on elixir

Respond ONLY with valid JSON. No other text.
    """.trimIndent()

    /**
     * Analyze a deck with Opus and cache the playbook.
     * Reports progress via the onProgress callback (runs on calling thread).
     *
     * @return The playbook JSON string, or null on failure
     */
    suspend fun analyzeWithProgress(
        deck: List<DeckManager.CardInfo>,
        context: Context,
        onProgress: (String) -> Unit
    ): String? {
        val deckJson = DeckManager.getDeckJson()
        val deckNames = deck.joinToString(", ") { it.name }

        onProgress("Analyzing deck with Claude Opus...")
        Log.i(TAG, "OPUS: Starting analysis for: $deckNames")

        return try {
            val startTime = System.currentTimeMillis()

            val userMessage = "DECK (loaded from game data):\n$deckJson\n\nAnalyze this deck and generate the strategic playbook."

            val result = AnthropicClient.chat(
                model = MODEL,
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userMessage,
                maxTokens = 4096
            )

            val elapsed = System.currentTimeMillis() - startTime

            // Cache the playbook
            cachedPlaybook = result

            // Save to internal storage
            savePlaybook(context, result)

            val previewLen = minOf(result.length, 100)
            Log.i(TAG, "OPUS: Analysis complete in ${elapsed}ms (${result.length} chars)")
            onProgress("Playbook ready (${elapsed / 1000}s)")

            result
        } catch (e: IllegalStateException) {
            // API key not set
            Log.w(TAG, "OPUS: ${e.message}")
            onProgress("Set API key in local.properties")
            loadFallbackPlaybook(context)
            null
        } catch (e: Exception) {
            Log.e(TAG, "OPUS: Analysis failed: ${e.message}", e)
            onProgress("Opus error: ${e.message?.take(50)}")
            loadFallbackPlaybook(context)
            null
        }
    }

    /**
     * Save playbook to internal storage for persistence.
     */
    private fun savePlaybook(context: Context, playbook: String) {
        try {
            val file = File(context.filesDir, PLAYBOOK_FILE)
            file.writeText(playbook)
            Log.i(TAG, "OPUS: Playbook saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "OPUS: Failed to save playbook: ${e.message}")
        }
    }

    /**
     * Load playbook from internal storage (from previous session).
     * Returns the playbook string, or null if not found.
     */
    fun loadSavedPlaybook(context: Context): String? {
        try {
            val file = File(context.filesDir, PLAYBOOK_FILE)
            if (file.exists()) {
                val playbook = file.readText()
                if (playbook.isNotBlank()) {
                    cachedPlaybook = playbook
                    Log.i(TAG, "OPUS: Loaded saved playbook (${playbook.length} chars)")
                    return playbook
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OPUS: Failed to load saved playbook: ${e.message}")
        }
        return null
    }

    /**
     * Load or create a fallback playbook when Opus is unavailable.
     * Uses a minimal generic playbook.
     */
    private fun loadFallbackPlaybook(context: Context): String {
        // Try loading saved playbook from previous session
        val saved = loadSavedPlaybook(context)
        if (saved != null) return saved

        // Generic fallback
        val fallback = """
        {
            "archetype": "Generic deck - play reactively",
            "win_conditions": [{"card": "Use highest-DPS troop", "strategy": "Push opposite lane after defending"}],
            "defense_table": {},
            "offense_plays": [{"sequence": "Tank + support behind", "placement": "back center then bridge"}],
            "card_placement_defaults": {},
            "synergies": [],
            "never_do": ["Don't overcommit elixir", "Don't ignore opposite lane pushes"],
            "elixir_management": "Defend first, counter-push with surviving troops"
        }
        """.trimIndent()

        cachedPlaybook = fallback
        return fallback
    }
}
