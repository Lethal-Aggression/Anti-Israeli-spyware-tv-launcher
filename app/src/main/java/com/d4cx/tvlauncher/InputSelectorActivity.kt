package com.d4cx.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class InputSelectorActivity : Activity() {

    private val interpolator = DecelerateInterpolator(1.5f)
    private lateinit var topInputBar: View
    private var isClosing = false

    private var tvInputManager: TvInputManager? = null

    // Auto-vanish inactivity timer (4.5s)
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        Log.d("InputSelector", "Inactivity timeout reached -> auto-vanishing")
        closeWithAnimation()
    }
    private val INACTIVITY_TIMEOUT_MS = 4500L

    // Real-time listener for TV input connection status changes
    private val tvInputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String?, state: Int) {
            runOnUiThread {
                refreshInputDots()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_selector)

        topInputBar = findViewById(R.id.topInputBar)

        tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        try {
            tvInputManager?.registerCallback(tvInputCallback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w("InputSelector", "Could not register TvInputCallback", e)
        }

        // 1. Home -> Launches OUR custom launcher!
        setupItem(
            container = findViewById(R.id.input_home),
            icon = findViewById(R.id.icon_home),
            label = findViewById(R.id.text_home)
        ) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            closeWithAnimation()
        }

        // 2. HDMI 1
        setupItem(
            container = findViewById(R.id.input_hdmi1),
            icon = findViewById(R.id.icon_hdmi1),
            label = findViewById(R.id.text_hdmi1)
        ) {
            switchTvInput("com.mediatek.tis/.HdmiInputService/HW2")
            closeWithAnimation()
        }

        // 3. HDMI 2
        setupItem(
            container = findViewById(R.id.input_hdmi2),
            icon = findViewById(R.id.icon_hdmi2),
            label = findViewById(R.id.text_hdmi2)
        ) {
            switchTvInput("com.mediatek.tis/.HdmiInputService/HW3")
            closeWithAnimation()
        }

        // 4. HDMI 3
        setupItem(
            container = findViewById(R.id.input_hdmi3),
            icon = findViewById(R.id.icon_hdmi3),
            label = findViewById(R.id.text_hdmi3)
        ) {
            switchTvInput("com.mediatek.tis/.HdmiInputService/HW4")
            closeWithAnimation()
        }

        // 5. AV
        setupItem(
            container = findViewById(R.id.input_av),
            icon = findViewById(R.id.icon_av),
            label = findViewById(R.id.text_av)
        ) {
            switchTvInput("com.mediatek.tis/.CompositeInputService/HW5")
            closeWithAnimation()
        }

        // 6. TV
        setupItem(
            container = findViewById(R.id.input_tv),
            icon = findViewById(R.id.icon_tv),
            label = findViewById(R.id.text_tv)
        ) {
            switchTvInput("com.mediatek.tis/.AnalogInputService/HW1")
            closeWithAnimation()
        }

        refreshInputDots()

        // Slide down animation on open
        topInputBar.translationY = -40f
        topInputBar.alpha = 0f
        topInputBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(150)
            .setInterpolator(interpolator)
            .withEndAction {
                findViewById<View>(R.id.input_home)?.requestFocus()
            }
            .start()

        resetInactivityTimer()
    }

    private fun refreshInputDots() {
        updateDot(findViewById(R.id.dot_hdmi1), "com.mediatek.tis/.HdmiInputService/HW2")
        updateDot(findViewById(R.id.dot_hdmi2), "com.mediatek.tis/.HdmiInputService/HW3")
        updateDot(findViewById(R.id.dot_hdmi3), "com.mediatek.tis/.HdmiInputService/HW4")
        updateDot(findViewById(R.id.dot_av), "com.mediatek.tis/.CompositeInputService/HW5")
        updateDot(findViewById(R.id.dot_tv), "com.mediatek.tis/.AnalogInputService/HW1")
    }

    private fun updateDot(dotView: View?, inputId: String) {
        if (dotView == null) return
        try {
            val state = tvInputManager?.getInputState(inputId)
            // 0 = INPUT_STATE_CONNECTED (Device plugged in and sending active signal)
            val isConnected = (state == TvInputManager.INPUT_STATE_CONNECTED)
            dotView.visibility = if (isConnected) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            dotView.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInputDots()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        try {
            tvInputManager?.unregisterCallback(tvInputCallback)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun resetInactivityTimer() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        if (!isClosing) {
            autoDismissHandler.postDelayed(autoDismissRunnable, INACTIVITY_TIMEOUT_MS)
        }
    }

    private fun setupItem(container: LinearLayout, icon: ImageView, label: TextView, action: () -> Unit) {
        val focusBg = ContextCompat.getDrawable(this, R.drawable.bg_item_focus)

        container.alpha = 0.40f
        container.scaleX = 1.0f
        container.scaleY = 1.0f
        container.background = null
        label.setTextColor(Color.parseColor("#88FFFFFF"))
        icon.setColorFilter(Color.parseColor("#88FFFFFF"))

        container.setOnFocusChangeListener { view, hasFocus ->
            resetInactivityTimer()
            if (hasFocus) {
                view.background = focusBg
                view.animate()
                    .scaleX(1.10f)
                    .scaleY(1.10f)
                    .translationY(-2f)
                    .alpha(1.0f)
                    .setDuration(120)
                    .setInterpolator(interpolator)
                    .start()
                label.setTextColor(Color.WHITE)
                icon.setColorFilter(Color.WHITE)
            } else {
                view.background = null
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationY(0f)
                    .alpha(0.40f)
                    .setDuration(120)
                    .setInterpolator(interpolator)
                    .start()
                label.setTextColor(Color.parseColor("#88FFFFFF"))
                icon.setColorFilter(Color.parseColor("#88FFFFFF"))
            }
        }

        container.setOnClickListener {
            action.invoke()
        }
    }

    private fun switchTvInput(inputId: String) {
        try {
            val encodedId = Uri.encode(inputId)
            val uri = Uri.parse("content://android.media.tv/passthrough/$encodedId")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("TVLauncher", "Failed to switch TV input $inputId", e)
        }
    }

    private fun closeWithAnimation() {
        if (isClosing) return
        isClosing = true
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        topInputBar.animate()
            .translationY(-40f)
            .alpha(0f)
            .setDuration(120)
            .setInterpolator(interpolator)
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any interaction resets the inactivity auto-vanish countdown!
        resetInactivityTimer()

        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_TV_INPUT ||
                keyCode == 178) {
                closeWithAnimation()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        closeWithAnimation()
    }
}
