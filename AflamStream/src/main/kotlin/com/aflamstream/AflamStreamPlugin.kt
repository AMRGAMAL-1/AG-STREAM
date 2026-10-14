package com.aflamstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AflamStreamPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AflamStreamProvider())
    }
}
