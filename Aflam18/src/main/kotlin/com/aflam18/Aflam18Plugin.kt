package com.aflam18

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Aflam18Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Aflam18Provider())
    }
}
