package com.khayom.cocai

/**
 * Central policy guard for the product's non-spending requirement.
 *
 * The MVP does not expose Android Accessibility input or any API capable of
 * tapping game controls. This guard is an additional validation boundary:
 * future features must explicitly reject purchase/upgrade/spend actions.
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
