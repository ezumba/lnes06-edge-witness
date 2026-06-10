package com.exergynet.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * CallScreen — native sovereign A/V call UI (hosted by CallActivity). State-aware:
 *   RINGING   → incoming: Accept / Decline
 *   CALLING   → outgoing: Calling… / Cancel
 *   CONNECTED → remote video + local preview + Mute / Speaker / Video / End
 *   ENDED/IDLE→ [onFinished] is invoked so the activity closes.
 */
@Composable
fun CallScreen(callEngine: DLTNCallEngine, onFinished: () -> Unit) {
    val state by callEngine.callState.collectAsState()
    val peer = remember(state) { callEngine.getRemotePeer() }

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isVideoEnabled by remember { mutableStateOf(false) }

    val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(state) {
        if (state == DLTNCallEngine.CallState.ENDED || state == DLTNCallEngine.CallState.IDLE) {
            onFinished()
        }
    }
    DisposableEffect(Unit) { onDispose { callEngine.setRenderers(null, null) } }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B141A)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Node ${peer.take(8)}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            when (state) {
                DLTNCallEngine.CallState.RINGING -> "Incoming call…"
                DLTNCallEngine.CallState.CALLING -> "Calling…"
                DLTNCallEngine.CallState.CONNECTED -> "Connected · End-to-end encrypted"
                else -> ""
            },
            color = Color(0xFF8FA3AD), fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        // Remote video (large) — only meaningful when connected.
        Box(modifier = Modifier.fillMaxWidth().height(380.dp).padding(12.dp)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        remoteRenderer.value = r
                        callEngine.setRenderers(localRenderer.value, r)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // Local preview (small, mirrored), bottom-end.
            Box(modifier = Modifier.size(110.dp, 150.dp).align(Alignment.BottomEnd).padding(8.dp)) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { r ->
                            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            r.setMirror(true)
                            localRenderer.value = r
                            callEngine.setRenderers(r, remoteRenderer.value)
                        }
                    },
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            DLTNCallEngine.CallState.RINGING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(onClick = { callEngine.rejectCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) { Text("Decline") }
                Button(onClick = { callEngine.acceptIncomingCall(peer) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))) { Text("Accept") }
            }
            DLTNCallEngine.CallState.CALLING -> Button(onClick = { callEngine.endCall() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) { Text("Cancel") }
            DLTNCallEngine.CallState.CONNECTED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { isMuted = !isMuted; callEngine.setMuted(isMuted) }) {
                    Text(if (isMuted) "Unmute" else "Mute")
                }
                Button(onClick = { isSpeakerOn = !isSpeakerOn; callEngine.setSpeakerphoneOn(isSpeakerOn) }) {
                    Text(if (isSpeakerOn) "Spkr Off" else "Speaker")
                }
                Button(
                    onClick = { isVideoEnabled = !isVideoEnabled; callEngine.setVideoEnabled(isVideoEnabled) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isVideoEnabled) Color(0xFF4285F4) else ButtonDefaults.buttonColors().containerColor
                    )
                ) { Text(if (isVideoEnabled) "Vid Off" else "Video") }
                Button(onClick = { callEngine.endCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) { Text("End") }
            }
            else -> {}
        }
    }
}
