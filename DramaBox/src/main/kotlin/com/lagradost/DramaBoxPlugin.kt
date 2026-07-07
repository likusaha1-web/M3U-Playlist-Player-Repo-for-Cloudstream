package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaBoxPlugin: Plugin() {
    override fun load(context: Context) {
        // Register provider kita ke Cloudstream
        registerMainAPI(DramaBoxProvider())
    }
}
