package com.fngml

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FngmlPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FngmlProvider())
    }
}
