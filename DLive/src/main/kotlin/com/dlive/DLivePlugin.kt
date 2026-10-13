package com.dlive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DLiveProvider())
    }
}
