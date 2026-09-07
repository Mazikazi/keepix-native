package com.sese.keepix.ui

import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A muted, looping video preview for whichever card is on top.
 *
 * Lifted out of SwipeScreen so the neumorphic deck can use it too and it does
 * not die with that file. Callers are responsible for only composing it for the
 * front card, and for dropping it while a fullscreen viewer is open -- otherwise
 * two players run at once and the hidden one keeps decoding.
 */
@Composable
internal fun AutoplayVideo(
    uri: Uri,
    modifier: Modifier = Modifier,
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(uri) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setVideoURI(uri)
                setOnPreparedListener { player: MediaPlayer ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    start()
                }
                videoViewRef = this
            }
        },
        update = { videoView ->
            if (!videoView.isPlaying) {
                videoView.setVideoURI(uri)
                videoView.start()
            }
        },
    )
}
