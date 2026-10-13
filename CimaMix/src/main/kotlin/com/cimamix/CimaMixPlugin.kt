package com.cimamix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CimaMixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaMixProvider())
    }
}
