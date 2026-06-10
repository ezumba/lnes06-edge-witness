package com.exergynet.myapplication

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * CallActivity — native host for the Compose [CallScreen] (A/V call UI).
 *
 * The main app is a WebView (index.html); the old HTML call overlay could never
 * render WebRTC video because a SurfaceViewRenderer needs a native surface. This
 * activity is launched by DLTNForegroundService whenever a call enters
 * RINGING/CALLING and finishes itself when the engine returns to ENDED/IDLE.
 *
 * Lock-screen flags let an incoming call wake + show over the keyguard like a
 * real phone call.
 */
class CallActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            val i = Intent(context, CallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ring through the lock screen like the dialer does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val engine = DLTNForegroundService.instance?.callEngine
        if (engine == null) { finish(); return }

        setContent {
            CallScreen(callEngine = engine, onFinished = { finish() })
        }
    }
}
