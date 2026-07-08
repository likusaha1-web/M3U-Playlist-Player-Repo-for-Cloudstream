package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import android.content.Context

import android.app.Activity
import android.app.Dialog
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import android.view.Window
import android.view.Gravity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ShortMaxProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private val PASSWORD = com.lagradost.ShortMax.BuildConfig.SHORTMAX_KEY

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        var cfCookies: String?
            get() = context?.getKey("SHORTMAX_CF_COOKIES")
            set(value) {
                context?.setKey("SHORTMAX_CF_COOKIES", value)
            }

        fun decryptCryptoJS(encryptedText: String): String {
            val ciphertextBytes = Base64.decode(encryptedText, Base64.DEFAULT)
            val header = "Salted__".toByteArray(Charsets.US_ASCII)
            if (ciphertextBytes.size < 16 || !ciphertextBytes.sliceArray(0..7).contentEquals(header)) {
                throw IllegalArgumentException("Invalid CryptoJS ciphertext")
            }
            val salt = ciphertextBytes.sliceArray(8..15)
            val encrypted = ciphertextBytes.sliceArray(16 until ciphertextBytes.size)

            val passwordBytes = PASSWORD.toByteArray(Charsets.UTF_8)
            val keyIv = ByteArray(48)
            var prev = ByteArray(0)
            var keyIvOffset = 0
            val md = MessageDigest.getInstance("MD5")

            while (keyIvOffset < keyIv.size) {
                md.reset()
                md.update(prev)
                md.update(passwordBytes)
                md.update(salt)
                prev = md.digest()
                val copyLen = min(prev.size, keyIv.size - keyIvOffset)
                System.arraycopy(prev, 0, keyIv, keyIvOffset, copyLen)
                keyIvOffset += copyLen
            }

            val key = keyIv.sliceArray(0..31)
            val iv = keyIv.sliceArray(32..47)

            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }
    }

    override var mainUrl = com.lagradost.ShortMax.BuildConfig.SHORTMAX_URL
    override var name = "ShortMax"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("ShortMax - For You", "foryou"),
        MainPageData("ShortMax - Rekomendasi", "rekomendasi")
    )

    private fun checkResponse(response: String) {
        val trimmed = response.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            throw Exception("Harap selesaikan tantangan Cloudflare (Klik Buka di Peramban / Ikon Bumi di kanan atas)!")
        }
    }

    private fun dp(context: Context, dpVal: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dpVal * density).toInt()
    }

    private fun getResumedActivity(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null) ?: return null
            val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
            mActivitiesField.isAccessible = true
            val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                if (activityRecord == null) continue
                val pausedField = activityRecord.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                val paused = pausedField.get(activityRecord) as? Boolean ?: true
                if (!paused) {
                    val activityField = activityRecord.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val activity = activityField.get(activityRecord) as? Activity
                    if (activity != null && !activity.isFinishing) {
                        return activity
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun solveCloudflare(activity: Activity, url: String): Boolean = suspendCancellableCoroutine { continuation ->
        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#121216")))
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            dialog.setCancelable(true)
            dialog.setOnCancelListener {
                if (continuation.isActive) continuation.resume(false)
            }

            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#121216"))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                val pad = dp(activity, 16)
                setPadding(pad, pad, pad, pad)
            }

            // Title layout
            val titleLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(activity, 4)
                }
            }

            val shieldEmoji = TextView(activity).apply {
                text = "🛡️ "
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            val titleTv = TextView(activity).apply {
                text = "Cloudflare Bypass"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            titleLayout.addView(shieldEmoji)
            titleLayout.addView(titleTv)
            rootLayout.addView(titleLayout)

            // Status Layout
            val statusTv = TextView(activity).apply {
                text = "⏳ Waiting for cookies... (0s)"
                setTextColor(Color.parseColor("#DFE6E9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(activity, 4)
                }
            }
            rootLayout.addView(statusTv)

            // Subtitle
            val subtitleTv = TextView(activity).apply {
                text = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
                setTextColor(Color.parseColor("#B2BEC3"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(activity, 12)
                }
            }
            rootLayout.addView(subtitleTv)

            // Progress Bar
            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 3)).apply {
                    bottomMargin = dp(activity, 12)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
                }
            }
            rootLayout.addView(progressBar)

            // WebView
            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = USER_AGENT
                setBackgroundColor(Color.parseColor("#121216"))
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, urlStr: String?) {
                        super.onPageFinished(view, urlStr)
                        val title = view?.title ?: ""
                        if (urlStr != null && !urlStr.contains("challenges.cloudflare.com") && !title.contains("Just a moment", ignoreCase = true)) {
                            try {
                                val cookies = CookieManager.getInstance().getCookie(urlStr)
                                if (cookies != null) {
                                    cfCookies = cookies
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            dialog.dismiss()
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
                }
            }
            rootLayout.addView(webView)

            dialog.setContentView(rootLayout)
            dialog.show()

            // Timer update
            var elapsedSeconds = 0
            val timer = Timer()
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    activity.runOnUiThread {
                        if (dialog.isShowing) {
                            elapsedSeconds++
                            statusTv.text = "⏳ Waiting for cookies... (${elapsedSeconds}s)"
                        } else {
                            timer.cancel()
                        }
                    }
                }
            }, 1000, 1000)

            webView.loadUrl(url)
        }
    }

    private suspend fun requestWithCf(url: String, params: Map<String, String>? = null): String {
        val headersMap = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
        cfCookies?.let { headersMap["Cookie"] = it }

        val response = if (params != null) {
            app.get(url, params = params, headers = headersMap).text
        } else {
            app.get(url, headers = headersMap).text
        }

        try {
            checkResponse(response)
            return response
        } catch (e: Exception) {
            val activity = getResumedActivity() ?: throw e
            val solved = solveCloudflare(activity, mainUrl)
            if (solved) {
                val newHeadersMap = mutableMapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                )
                cfCookies?.let { newHeadersMap["Cookie"] = it }
                val retryResponse = if (params != null) {
                    app.get(url, params = params, headers = newHeadersMap).text
                } else {
                    app.get(url, headers = newHeadersMap).text
                }
                checkResponse(retryResponse)
                return retryResponse
            } else {
                throw e
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = if (request.data == "foryou") {
            "/api/shortmax/foryou?page=$page"
        } else {
            "/api/shortmax/rekomendasi"
        }

        val rawHome = requestWithCf("$mainUrl$path")
        val decrypted = decryptCryptoJS(JSONObject(rawHome).getString("data"))

        val results = if (request.data == "foryou") {
            val jsonObj = JSONObject(decrypted)
            jsonObj.optJSONArray("data")
                ?: jsonObj.optJSONArray("results")
                ?: jsonObj.optJSONArray("list")
                ?: JSONArray()
        } else {
            if (decrypted.trim().startsWith("[")) {
                JSONArray(decrypted)
            } else {
                val jsonObj = JSONObject(decrypted)
                jsonObj.optJSONArray("results")
                    ?: jsonObj.optJSONArray("data")
                    ?: jsonObj.optJSONArray("list")
                    ?: JSONArray()
            }
        }

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("shortPlayId").takeIf { it.isNotEmpty() }
                ?: item.optString("id").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: "Drama $id"
            val cover = item.optString("cover").takeIf { it.isNotEmpty() }
                ?: item.optString("image").takeIf { it.isNotEmpty() }
                ?: ""

            list.add(
                newTvSeriesSearchResponse(
                    name = title,
                    url = id,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = cover
                }
            )
        }

        return newHomePageResponse(request.name, list, hasNext = request.data == "foryou" && results.length() > 0)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawSearch = requestWithCf("$mainUrl/api/shortmax/search", mapOf("query" to query))
        val decrypted = decryptCryptoJS(JSONObject(rawSearch).getString("data"))
        val json = JSONObject(decrypted)
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("shortPlayId").takeIf { it.isNotEmpty() }
                ?: item.optString("id").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: "Drama $id"
            val cover = item.optString("cover").takeIf { it.isNotEmpty() }
                ?: item.optString("image").takeIf { it.isNotEmpty() }
                ?: ""

            list.add(
                newTvSeriesSearchResponse(
                    name = title,
                    url = id,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = cover
                }
            )
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val shortPlayId = url
        val rawDetail = requestWithCf("$mainUrl/api/shortmax/detail?shortPlayId=$shortPlayId")
        val decryptedDetail = decryptCryptoJS(JSONObject(rawDetail).getString("data"))
        val json = JSONObject(decryptedDetail)

        val title = json.optString("title").takeIf { it.isNotEmpty() } ?: "Drama $shortPlayId"
        val cover = json.optString("cover")
        val description = json.optString("description")

        val totalEpisodes = json.optInt("totalEpisodes").takeIf { it > 0 }
            ?: json.optInt("updateEpisode").takeIf { it > 0 }
            ?: 100

        val episodes = (1..totalEpisodes).map { num ->
            newEpisode(
                data = "$shortPlayId|$num"
            ) {
                this.name = "Episode $num"
                this.episode = num
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = shortPlayId,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = cover
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        val shortPlayId = parts[0]
        val episodeNum = parts[1]

        val rawEpisode = requestWithCf("$mainUrl/api/shortmax/episode?shortPlayId=$shortPlayId&episodeNumber=$episodeNum")
        val decryptedEpisode = decryptCryptoJS(JSONObject(rawEpisode).getString("data"))
        val json = JSONObject(decryptedEpisode)

        val episodeObj = json.optJSONObject("episode") ?: return false
        val videoUrlObj = episodeObj.optJSONObject("videoUrl") ?: return false

        var foundLink = false
        for (key in listOf("video_1080", "video_720", "video_480")) {
            val videoPath = videoUrlObj.optString(key)
            if (videoPath.isNotEmpty()) {
                val absoluteUrl = if (videoPath.startsWith("http")) videoPath else "$mainUrl$videoPath"
                val quality = when (key) {
                    "video_1080" -> Qualities.P1080.value
                    "video_720" -> Qualities.P720.value
                    else -> Qualities.P480.value
                }
                callback.invoke(
                    newExtractorLink(
                        name = "ShortMax - $key",
                        source = this.name,
                        url = absoluteUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
                foundLink = true
            }
        }
        return foundLink
    }
}
