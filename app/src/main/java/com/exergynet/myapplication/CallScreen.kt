package com.exergynet.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private val BG = Color(0xFF0B141A)
private val PANEL = Color(0xFF1F2C34)
private val GREEN = Color(0xFF00C853)
private val RED = Color(0xFFE53935)
private val MUTED = Color(0xFF8696A0)

/**
 * CallScreen — native sovereign call UI (hosted by CallActivity).
 *
 *   VOICE  (default): dark screen, avatar with initials, name + status, and a
 *                     control panel (Speaker / Video / Mute / End). Tapping Video
 *                     upgrades the call to video — no camera runs until then.
 *   VIDEO  (videoActive): the remote feed fills the whole screen with a small
 *                     mirrored self-preview in the corner; controls overlay the bottom.
 *   RINGING: Accept / Decline.   CALLING: Cancel.
 */
@Composable
fun CallScreen(callEngine: DLTNCallEngine, onFinished: () -> Unit) {
    val state by callEngine.callState.collectAsState()
    val videoActive by callEngine.videoActive.collectAsState()
    val peer = remember(state) { callEngine.getRemotePeer() }

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(false) }

    val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(state) {
        if (state == DLTNCallEngine.CallState.ENDED || state == DLTNCallEngine.CallState.IDLE) onFinished()
    }
    DisposableEffect(Unit) { onDispose { callEngine.setRenderers(null, null) } }

    val statusText = when (state) {
        DLTNCallEngine.CallState.RINGING -> "Incoming call"
        DLTNCallEngine.CallState.CALLING -> "Calling…"
        DLTNCallEngine.CallState.CONNECTED -> "🔒 End-to-end encrypted"
        else -> ""
    }

    Box(Modifier.fillMaxSize().background(BG)) {

        // Remote video surface — ALWAYS present so frames have a sink, but only
        // visible (full-screen) in video mode; covered by the voice overlay otherwise.
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { r ->
                    r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    // init() confirms the EGL surface is physically ready BEFORE we
                    // call setRenderers — this is the synchronization point that
                    // prevents the black-screen race between WebRTC track delivery
                    // and Compose's async view inflation.
                    r.init(callEngine.eglBaseContext(), null)
                    remoteRenderer.value = r
                    callEngine.setRenderers(localRenderer.value, r)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!videoActive) {
            // ── VOICE layout — opaque overlay hiding the (black) remote surface ──
            Column(
                modifier = Modifier.fillMaxSize().background(BG),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(72.dp))
                Text("Node ${peer.take(8)}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(statusText, color = MUTED, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(72.dp))
                // Avatar
                Box(
                    modifier = Modifier.size(140.dp).clip(CircleShape).background(Color(0xFFB9F6CA)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials(peer), color = Color(0xFF00695C), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // ── VIDEO layout — small mirrored self-preview, top-right ──
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .size(110.dp, 150.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { r ->
                            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            r.setMirror(true)
                            r.setZOrderMediaOverlay(true)
                            // Same init-before-setRenderers contract as the remote surface.
                            r.init(callEngine.eglBaseContext(), null)
                            localRenderer.value = r
                            callEngine.setRenderers(r, remoteRenderer.value)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Name + status overlay at top
            Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Node ${peer.take(8)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(statusText, color = Color(0xFFCFD8DC), fontSize = 13.sp)
            }
        }

        // ── Controls (bottom) ──────────────────────────────────────────────────
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp)) {
            when (state) {
                DLTNCallEngine.CallState.RINGING -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    RoundAction("✕", "Decline", RED) { callEngine.rejectCall() }
                    RoundAction("📞", "Accept", GREEN) { callEngine.acceptIncomingCall(peer) }
                }
                DLTNCallEngine.CallState.CALLING -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center
                ) {
                    RoundAction("📞", "Cancel", RED) { callEngine.endCall() }
                }
                DLTNCallEngine.CallState.CONNECTED -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(28.dp)).background(PANEL).padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            RoundAction(if (isSpeakerOn) "🔊" else "🔈", "Speaker", PANELBTN(isSpeakerOn)) {
                                isSpeakerOn = !isSpeakerOn; callEngine.setSpeakerphoneOn(isSpeakerOn)
                            }
                            RoundAction("📹", "Video", PANELBTN(isVideoOn)) {
                                isVideoOn = !isVideoOn; callEngine.setVideoEnabled(isVideoOn)
                            }
                            if (isVideoOn) {
                                RoundAction("🔄", "Flip", PANELBTN(false)) {
                                    callEngine.switchCamera()
                                }
                            }
                            RoundAction(if (isMuted) "🔇" else "🎤", "Mute", PANELBTN(isMuted)) {
                                isMuted = !isMuted; callEngine.setMuted(isMuted)
                            }
                            RoundAction("📞", "End", RED) { callEngine.endCall() }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Suppress("FunctionName")
private fun PANELBTN(active: Boolean): Color = if (active) Color(0xFF2A3942) else Color(0xFF374550)

@Composable
private fun RoundAction(glyph: String, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(60.dp).clip(CircleShape).background(bg).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(glyph, fontSize = 24.sp) }
        Text(label, color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(64.dp))
    }
}

private fun initials(nodeId: String): String {
    val an = nodeId.filter { it.isLetterOrDigit() }
    return if (an.length >= 2) an.take(2).uppercase() else if (an.isNotEmpty()) an.uppercase() else "#"
}
