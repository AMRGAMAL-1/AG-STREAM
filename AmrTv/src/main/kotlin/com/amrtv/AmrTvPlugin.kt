package com.amrtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AmrTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AmrTvProvider())
    }
}
