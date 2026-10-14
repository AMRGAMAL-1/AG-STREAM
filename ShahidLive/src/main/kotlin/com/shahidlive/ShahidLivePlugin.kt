package com.shahidlive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShahidLivePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShahidLiveProvider())
    }
}
