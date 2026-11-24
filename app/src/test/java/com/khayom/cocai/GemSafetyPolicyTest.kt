package com.khayom.cocai

import org.junit.Assert.assertFalse
import org.junit.Test

class GemSafetyPolicyTest {
    @Test
    fun gameInputIsPermanentlyDisabled() {
        assertFalse(GemSafetyPolicy.canSendGameInput())
    }

    @Test
    fun allGameActionsRemainBlocked() {
        val actions = listOf(
            "open profile",
            "scroll achievement list",
            "buy builder",
            "spend gems",
            "upgrade building",
            "boost timer",
            "finish now",
            "купить",
            "потратить кристаллы",
            "улучшить здание"
        )
        actions.forEach { action ->
            assertFalse("Unexpected game action allowed: $action",
                GemSafetyPolicy.mayPerformGameAction(action))
        }
    }

    @Test
    fun gemSpendingFeatureIsDisabled() {
        assertFalse(GemSafetyPolicy.GEM_SPENDING_ENABLED)
    }
}
