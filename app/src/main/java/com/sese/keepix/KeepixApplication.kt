package com.sese.keepix

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/**
 * Exists for one reason: Coil cannot decode a video URI out of the box, so
 * every video in the bin, library and Compressed grids rendered as an empty
 * well. The deck never showed it because it plays videos rather than
 * thumbnailing them.
 *
 * [VideoFrameDecoder] pulls the first frame. Registered here rather than at each
 * AsyncImage call site so a grid added later gets it for free.
 */
class KeepixApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
