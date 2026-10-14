package com.starzplaylive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StarzPlayLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StarzPlayLiveProvider())
    }
}
