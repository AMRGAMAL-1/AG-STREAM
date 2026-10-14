package com.egytbest

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EgyBestPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EgyBestProvider())
    }
}
