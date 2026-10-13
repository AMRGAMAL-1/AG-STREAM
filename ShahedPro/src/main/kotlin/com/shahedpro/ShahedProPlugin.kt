package com.shahedpro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShahedProPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShahedProProvider())
    }
}
