package com.khayom.cocai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs only the navigation gestures required by the Achievement scanner:
 * opening the player profile from the village and scrolling the visible
 * Achievement list. It does not inspect other apps' UI nodes or issue battle,
 * purchase, upgrade, or resource-spending actions.
 */
class GameAutomationAccessibilityService : AccessibilityService() {
    private val gestureInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastGestureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        gestureInFlight.set(false)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        gestureInFlight.set(false)
        super.onDestroy()
    }

    private fun dispatchPathOnMainThread(path: Path, durationMs: Long): Boolean {
        if (!gestureInFlight.compareAndSet(false, true)) return false
        val dispatch = Runnable {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        gestureInFlight.set(false)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        gestureInFlight.set(false)
                    }
                },
                mainHandler
            )
            if (!accepted) gestureInFlight.set(false)
            if (accepted) lastGestureAt = SystemClock.elapsedRealtime()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run()
        else mainHandler.post(dispatch)
        return true
    }

    fun tapPlayerProfile(): Boolean {
        if (gestureInFlight.get()) return false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f || width < height) return false

        // Clash of Clans player-level/profile badge: upper-left corner.
        val x = width * PROFILE_BADGE_X
        val y = height * PROFILE_BADGE_Y
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        return dispatchPathOnMainThread(path, TAP_DURATION_MS)
    }

    fun scrollAchievementList(): Boolean {
        if (gestureInFlight.get()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastGestureAt < MIN_GESTURE_INTERVAL_MS) return false

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= height || width <= 0f || height <= 0f) return false

        // The Achievement rows occupy the middle of the Profile panel.
        val x = width * SCROLL_X
        val startY = height * SCROLL_START_Y
        val endY = height * SCROLL_END_Y
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return dispatchPathOnMainThread(path, SCROLL_DURATION_MS)
    }

    companion object {
        private const val PROFILE_BADGE_X = 0.066f
        private const val PROFILE_BADGE_Y = 0.068f
        private const val SCROLL_X = 0.50f
        private const val SCROLL_START_Y = 0.74f
        private const val SCROLL_END_Y = 0.32f
        private const val TAP_DURATION_MS = 100L
        private const val SCROLL_DURATION_MS = 480L
        private const val MIN_GESTURE_INTERVAL_MS = 2_500L

        @Volatile private var activeService: GameAutomationAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val configured = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val target = ComponentName(context, GameAutomationAccessibilityService::class.java)
            return configured.split(':').any { ComponentName.unflattenFromString(it) == target }
        }

        fun tapPlayerProfile(): Boolean = activeService?.tapPlayerProfile() ?: false

        fun scrollAchievementList(): Boolean =
            activeService?.scrollAchievementList() ?: false

        fun isConnected(): Boolean = activeService != null
    }
}
