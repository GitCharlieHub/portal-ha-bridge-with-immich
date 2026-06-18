package com.aeonos.portalha

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service — no event listening, used only for
 * performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) to blank the screen
 * without device admin.
 */
class ScreenAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenAccessibility? = null
        private const val TAG = "PortalHA"
    }

    override fun onServiceConnected() {
        instance = this
        // Override the XML declaration — we need no events at all, just performGlobalAction.
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        Log.i(TAG, "ScreenAccessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "ScreenAccessibility unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun sleepNow() {
        Log.i(TAG, "sleepNow: GLOBAL_ACTION_LOCK_SCREEN")
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}
