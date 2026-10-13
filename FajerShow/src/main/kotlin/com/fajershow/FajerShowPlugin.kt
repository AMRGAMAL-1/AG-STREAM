package com.fajershow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FajerShowPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FajerShowProvider())
    }
}
