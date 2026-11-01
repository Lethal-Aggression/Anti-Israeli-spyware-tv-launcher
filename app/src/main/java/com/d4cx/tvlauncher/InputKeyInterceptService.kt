package com.d4cx.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class InputKeyInterceptService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Log.d("InputKeyIntercept", "Accessibility service connected and configured")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // Keycode 178 = KEYCODE_TV_INPUT, 170 = KEYCODE_TV, scanCode 167 = remote TV INPUT button
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == KeyEvent.KEYCODE_TV || keyCode == 178 || keyCode == 170 || event.scanCode == 167) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d("InputKeyIntercept", "Intercepted KEYCODE_TV_INPUT DOWN -> launching InputSelectorActivity")
                val intent = Intent(this, InputSelectorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(intent)
            }
            // CRITICAL: Consume BOTH ACTION_DOWN and ACTION_UP!
            // If ACTION_UP is not consumed, the system handles KEYCODE_TV_INPUT on UP and opens Xiaomi stock drawer!
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val className = event.className?.toString() ?: ""
        val packageName = event.packageName?.toString() ?: ""

        // Fallback safety: If Xiaomi stock input selector is opened by any other system trigger
        if (packageName == "com.mitv.livetv" && className.contains("SelectInputActivity")) {
            Log.d("InputKeyIntercept", "Detected SelectInputActivity - dismissing instantly")
            performGlobalAction(GLOBAL_ACTION_BACK)

            val intent = Intent(this, InputSelectorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}
}
