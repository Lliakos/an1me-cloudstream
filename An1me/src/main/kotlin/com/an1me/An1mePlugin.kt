package com.an1me

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class An1mePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(An1meProvider())
    }
}
