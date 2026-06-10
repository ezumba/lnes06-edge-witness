package com.exergynet.myapplication.webrtc

import android.util.Log
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * No-op base for WebRTC's [SdpObserver] so call sites override only what they need.
 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.w("Sdp", "create failed: $error") }
    override fun onSetFailure(error: String?) { Log.w("Sdp", "set failed: $error") }
}
