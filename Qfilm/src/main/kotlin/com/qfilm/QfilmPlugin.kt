package com.qfilm

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class QfilmPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(QfilmProvider())
    }
}
