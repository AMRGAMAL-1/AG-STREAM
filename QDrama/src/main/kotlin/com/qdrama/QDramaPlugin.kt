package com.qdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class QDramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(QDramaProvider())
    }
}
