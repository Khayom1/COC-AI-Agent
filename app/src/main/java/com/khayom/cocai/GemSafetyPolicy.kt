package com.khayom.cocai

/**
 * Central policy guard for the product's non-spending requirement.
 *
 * The Accessibility service is restricted to the player-profile entry tap and
 * vertical scrolling of a confirmed Achievement list. It has no troop, battle,
 * purchase, upgrade, boost, or resource-spending controls.
 */
object GemSafetyPolicy {
    const val GEM_SPENDING_ENABLED = false

    private val prohibitedActionWords = listOf(
        "buy", "purchase", "spend", "upgrade", "boost", "finish now",
        "купить", "потратить", "улучшить", "ускорить", "завершить за",
        "кристалл потратить", "гем потратить"
    )

    fun mayPerformGameAction(actionDescription: String): Boolean {
        if (!GEM_SPENDING_ENABLED) return false
        val normalized = actionDescription.lowercase()
        return prohibitedActionWords.none { normalized.contains(it) }
    }

    fun canSendGameInput(): Boolean = false
}
