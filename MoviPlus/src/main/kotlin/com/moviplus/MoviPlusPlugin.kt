package com.moviplus

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviPlusPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviPlusProvider())
    }
}
