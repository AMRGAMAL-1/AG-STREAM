package com.wecima
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.wecima.WecimaProvider

@CloudstreamPlugin
class wecimaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WecimaProvider())
    }
}