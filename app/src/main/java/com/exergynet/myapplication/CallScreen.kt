package com.exergynet.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * CallScreen — sovereign A/V call UI. Hosts a large remote video with a small
 * local preview, plus mute / speaker / video controls. The two
 * [SurfaceViewRenderer]s are handed to the [DLTNCallEngine], which forwards them
 * to the WebRtcClient as the local + remote video sinks (TASK 4).
 */
@Composable
fun CallScreen(callEngine: DLTNCallEngine) {
    val callState by callEngine.callState.collectAsState()
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var isVideoEnabled by remember { mutableStateOf(false) }

    val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose { callEngine.setRenderers(null, null) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Call State: ${callState.name}")

        // Remote video (large)
        Box(modifier = Modifier.fillMaxWidth().height(360.dp).padding(8.dp)) {
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
        }

        // Local preview (small, mirrored)
        Box(modifier = Modifier.size(120.dp, 160.dp).padding(8.dp)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        r.setMirror(true)
                        localRenderer.value = r
                        callEngine.setRenderers(r, remoteRenderer.value)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = {
                isMuted = !isMuted
                callEngine.setMuted(isMuted)
            }) {
                Text(text = if (isMuted) "Unmute" else "Mute")
            }

            Button(onClick = {
                isSpeakerOn = !isSpeakerOn
                callEngine.setSpeakerphoneOn(isSpeakerOn)
            }) {
                Text(text = if (isSpeakerOn) "Speaker Off" else "Speaker On")
            }

            Button(
                onClick = {
                    isVideoEnabled = !isVideoEnabled
                    callEngine.setVideoEnabled(isVideoEnabled)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isVideoEnabled) Color(0xFF4285F4) else ButtonDefaults.buttonColors().containerColor
                )
            ) {
                Text(text = if (isVideoEnabled) "Video Off" else "Video On")
            }
        }
    }
}
