package com.sese.keepix.ui.neu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Host for [NeuStatePreview] on a real device -- the step-1 sign-off gate needs
 * real panel rendering, not the IDE preview, because NeuBlurScale is tuned by
 * eye against the prototypes.
 *
 * ponytail: debug source set, so it costs the release build nothing and there is
 * no BuildConfig.DEBUG branch in production code to read at 3am.
 */
class NeuPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NeuStatePreview() }
    }
}
