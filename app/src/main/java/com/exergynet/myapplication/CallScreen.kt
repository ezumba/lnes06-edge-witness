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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
private val ACCENT = Color(0xFF00E887)

/**
 * CallScreen — sovereign call UI supporting:
 *   VOICE  : dark screen, avatar, control panel.
 *   VIDEO  : full-screen remote feed, self-preview in corner.
 *   GROUP  : participant tile grid; "Add Person" button upgrades 1:1 → group.
 *   RINGING / CALLING : accept/decline or cancel.
 *
 * [onAddPerson] is called by the "+" button; the caller should open a node-picker
 * and then invoke [DLTNCallEngine.upgradeToGroupCall].
 */
@Composable
fun CallScreen(
    callEngine: DLTNCallEngine,
    onFinished: () -> Unit,
    onAddPerson: (() -> Unit)? = null,
) {
    val state by callEngine.callState.collectAsState()
    val videoActive by callEngine.videoActive.collectAsState()
    val groupParticipants by callEngine.groupParticipants.collectAsState()
    val isGroupCall = groupParticipants.isNotEmpty() || callEngine.activeRoomId != null
    val peer = remember(state) { callEngine.getRemotePeer() }

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(false) }

    val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    // Participant peerId → SurfaceViewRenderer for group tiles
    val participantRenderers = remember { mutableStateMapOf<String, SurfaceViewRenderer>() }

    LaunchedEffect(state) {
        if (state == DLTNCallEngine.CallState.ENDED || state == DLTNCallEngine.CallState.IDLE) onFinished()
    }
    DisposableEffect(Unit) { onDispose { callEngine.setRenderers(null, null) } }

    val statusText = when (state) {
        DLTNCallEngine.CallState.RINGING -> "Incoming call"
        DLTNCallEngine.CallState.CALLING -> "Calling…"
        DLTNCallEngine.CallState.CONNECTED ->
            if (isGroupCall) "Group Session · ${groupParticipants.size + 1} participants"
            else "🔒 End-to-end encrypted"
        else -> ""
    }

    Box(Modifier.fillMaxSize().background(BG)) {

        if (isGroupCall && state == DLTNCallEngine.CallState.CONNECTED) {
            // ── GROUP VIDEO GRID ──────────────────────────────────────────────
            Column(Modifier.fillMaxSize()) {
                // Group header
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Group Session", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(statusText, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }

                // All participants as tiles (existing remote + each group peer)
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Primary 1:1 remote peer tile
                    item {
                        ParticipantTile(
                            peerId = peer,
                            renderer = remoteRenderer.value,
                            isVideoActive = videoActive,
                            eglContext = callEngine.eglBaseContext(),
                            onRendererReady = { r ->
                                remoteRenderer.value = r
                                callEngine.setRenderers(localRenderer.value, r)
                            }
                        )
                    }
                    // Additional group peers
                    items(groupParticipants) { peerId ->
                        val existing = participantRenderers[peerId]
                        ParticipantTile(
                            peerId = peerId,
                            renderer = existing,
                            isVideoActive = videoActive,
                            eglContext = callEngine.eglBaseContext(),
                            onRendererReady = { r ->
                                participantRenderers[peerId] = r
                                // Register with the peer's WebRtcClient via engine
                                callEngine.setParticipantRenderer(peerId, r)
                            }
                        )
                    }
                }

                // Self-preview strip (small, bottom-left)
                Box(
                    Modifier.padding(start = 10.dp, bottom = 120.dp)
                        .size(80.dp, 110.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { r ->
                                r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                r.setMirror(true)
                                r.setZOrderMediaOverlay(true)
                                r.init(callEngine.eglBaseContext(), null)
                                localRenderer.value = r
                                callEngine.setRenderers(r, remoteRenderer.value)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

        } else {
            // ── 1:1 CALL LAYOUT ───────────────────────────────────────────────

            // Remote video surface — always present as a sink
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        r.init(callEngine.eglBaseContext(), null)
                        remoteRenderer.value = r
                        callEngine.setRenderers(localRenderer.value, r)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!videoActive) {
                Column(
                    modifier = Modifier.fillMaxSize().background(BG),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(72.dp))
                    Text("Node ${peer.take(8)}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(statusText, color = MUTED, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(72.dp))
                    Box(
                        modifier = Modifier.size(140.dp).clip(CircleShape).background(Color(0xFFB9F6CA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials(peer), color = Color(0xFF00695C), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Self-preview top-right corner
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
                                r.init(callEngine.eglBaseContext(), null)
                                localRenderer.value = r
                                callEngine.setRenderers(r, remoteRenderer.value)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Node ${peer.take(8)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(statusText, color = Color(0xFFCFD8DC), fontSize = 13.sp)
                }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            // Add Person — upgrades 1:1 to group
                            RoundAction("👤", "Add", PANELBTN(false)) {
                                onAddPerson?.invoke()
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

// ── Participant Tile ──────────────────────────────────────────────────────────

@Composable
private fun ParticipantTile(
    peerId: String,
    renderer: SurfaceViewRenderer?,
    isVideoActive: Boolean,
    eglContext: org.webrtc.EglBase.Context,
    onRendererReady: (SurfaceViewRenderer) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(PANEL)
    ) {
        if (isVideoActive) {
            AndroidView(
                factory = { ctx ->
                    (renderer ?: SurfaceViewRenderer(ctx).also { r ->
                        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        r.init(eglContext, null)
                        onRendererReady(r)
                    })
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF2A3942)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials(peerId), color = ACCENT, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Node ${peerId.take(8)}", color = Color.White, fontSize = 13.sp)
                }
            }
        }
        // Peer ID badge
        Text(
            peerId.take(8),
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x88000000)).padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Suppress("FunctionName")
private fun PANELBTN(active: Boolean): Color = if (active) Color(0xFF2A3942) else Color(0xFF374550)

@Composable
private fun RoundAction(glyph: String, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(bg).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(glyph, fontSize = 22.sp) }
        Text(label, color = MUTED, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).width(58.dp))
    }
}

private fun initials(nodeId: String): String {
    val an = nodeId.filter { it.isLetterOrDigit() }
    return if (an.length >= 2) an.take(2).uppercase() else if (an.isNotEmpty()) an.uppercase() else "#"
}
