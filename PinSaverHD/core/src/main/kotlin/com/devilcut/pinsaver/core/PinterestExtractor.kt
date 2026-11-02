package com.devilcut.pinsaver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Turns a Pinterest link into the list of downloadable video qualities.
 *
 * Strategy:
 * 1. Follow `pin.it` short links to get the Pin id.
 * 2. Ask Pinterest's web resource endpoint (`PinResource`) for the Pin JSON.
 * 3. If that fails, download the Pin page and read the JSON embedded in it.
 * 4. For every video: read HLS master playlists to learn their real maximum
 *    resolution, look for the MP4 twin that Pinterest keeps next to HLS
 *    streams, and check sizes with HEAD requests.
 */
class PinterestExtractor(
    private val http: OkHttpClient = defaultHttpClient(),
    webBase: String = DEFAULT_WEB_BASE,
) {
    private val base: HttpUrl = webBase.toHttpUrl()
    private val json = Json { isLenient = true }

    suspend fun extract(input: String): PinInfo {
        val link = PinterestLinks.parse(input)
            ?: throw PinterestException(PinterestException.Reason.INVALID_LINK, "Not a Pinterest Pin link: $input")
        val pinId = when (link) {
            is PinLink.Pin -> link.id
            is PinLink.Short -> resolveShortLink(link.url)
        }
        return fetchPin(pinId)
    }

    suspend fun resolveShortLink(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                var current: Response? = response
                while (current != null) {
                    PinterestLinks.pinIdFromUrl(current.request.url.toString())?.let { return@withContext it }
                    current.header("Location")?.let { location ->
                        PinterestLinks.pinIdFromUrl(location)?.let { return@withContext it }
                    }
                    current = current.priorResponse
                }
                HtmlPinParser.canonicalPinId(response.body?.string().orEmpty())?.let { return@withContext it }
            }
        } catch (e: IOException) {
            throw PinterestException(PinterestException.Reason.NETWORK, "Could not open $url", e)
        } catch (e: IllegalArgumentException) {
            throw PinterestException(PinterestException.Reason.INVALID_LINK, "Malformed link $url", e)
        }
        throw PinterestException(PinterestException.Reason.INVALID_LINK, "The short link did not lead to a Pin: $url")
    }

    suspend fun fetchPin(pinId: String): PinInfo = coroutineScope {
        val found = withContext(Dispatchers.IO) { findVideos(pinId) }
        val clips = found.clips
            .map { raw -> async(Dispatchers.IO) { resolveClip(raw) } }
            .awaitAll()
            .filter { it.variants.isNotEmpty() }
            .mapIndexed { index, clip -> clip.copy(index = index) }
        if (clips.isEmpty()) {
            throw PinterestException(PinterestException.Reason.NO_VIDEO, "No playable video in Pin $pinId")
        }
        PinInfo(
            pinId = pinId,
            title = found.title,
            thumbnailUrl = found.thumbnail ?: clips.first().thumbnailUrl,
            clips = clips,
        )
    }

    // ---------------------------------------------------------------- lookup

    private fun findVideos(pinId: String): FoundVideos {
        var networkError: IOException? = null
        var metadata: FoundVideos? = null

        for (fieldSet in API_FIELD_SETS) {
            try {
                val found = scanApi(pinId, fieldSet) ?: continue
                if (found.clips.isNotEmpty()) return found
                metadata = metadata ?: found
            } catch (e: IOException) {
                networkError = e
            }
        }
        try {
            val found = scanHtml(pinId)
            if (found.clips.isNotEmpty()) {
                return found.copy(
                    title = metadata?.title ?: found.title,
                    thumbnail = metadata?.thumbnail ?: found.thumbnail,
                )
            }
            metadata = metadata ?: found
        } catch (e: IOException) {
            networkError = e
        }
        if (metadata == null && networkError != null) {
            throw PinterestException(PinterestException.Reason.NETWORK, "Could not reach Pinterest", networkError)
        }
        throw PinterestException(PinterestException.Reason.NO_VIDEO, "Pin $pinId has no video")
    }

    private fun scanApi(pinId: String, fieldSet: String): FoundVideos? {
        val data = """{"options":{"id":"$pinId","field_set_key":"$fieldSet"},"context":{}}"""
        val url = base.newBuilder()
            .addPathSegments("resource/PinResource/get/")
            .addQueryParameter("source_url", "/pin/$pinId/")
            .addQueryParameter("data", data)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Pinterest-AppState", "active")
            .header("X-Pinterest-PWS-Handler", "www/[username].js")
            .header("X-Pinterest-Source-Url", "/pin/$pinId/")
            .header("Referer", pinPageUrl(pinId).toString())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            val resource = (root as? JsonObject)?.get("resource_response") as? JsonObject ?: return null
            val status = (resource["status"] as? JsonPrimitive)?.content
            if (status != null && status != "success") return null
            val pin = resource["data"]
            if (pin == null || pin is JsonNull) return null
            val scan = VideoJsonScanner.scan(listOf(pin), pinId)
            return FoundVideos(scan.clips, scan.title, scan.thumbnail)
        }
    }

    private fun scanHtml(pinId: String): FoundVideos {
        val request = Request.Builder()
            .url(pinPageUrl(pinId))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 410) {
                throw PinterestException(PinterestException.Reason.NOT_FOUND, "Pin $pinId does not exist")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
            return HtmlPinParser.parse(response.body?.string().orEmpty(), pinId)
        }
    }

    private fun pinPageUrl(pinId: String): HttpUrl =
        base.newBuilder().addPathSegment("pin").addPathSegment(pinId).addPathSegment("").build()

    // ------------------------------------------------------------ qualities

    private suspend fun resolveClip(raw: RawClip): VideoClip = coroutineScope {
        val listedMp4 = raw.variants.filterNot { VideoUrls.isHls(it.url) }.map { it.url }.toSet()
        val variants = raw.variants
            .map { variant -> async(Dispatchers.IO) { resolveVariant(variant, raw.durationMs, listedMp4) } }
            .awaitAll()
            .flatten()
        VideoClip(
            index = 0,
            variants = VariantRanking.rankAndPrune(variants),
            durationMs = raw.durationMs,
            thumbnailUrl = raw.thumbnail,
        )
    }

    private fun resolveVariant(raw: RawVariant, durationMs: Long?, listedMp4: Set<String>): List<VideoVariant> {
        if (!VideoUrls.isHls(raw.url)) {
            val probe = probe(raw.url)
            if (probe?.missing == true) return emptyList()
            return listOf(
                VideoVariant(
                    url = raw.url,
                    kind = VariantKind.MP4,
                    width = raw.width,
                    height = raw.height,
                    shortSideHint = VideoUrls.shortSideFromUrl(raw.url),
                    formatId = raw.formatId,
                    isHevc = VideoUrls.isHevc(raw.url, raw.formatId),
                    sizeBytes = probe?.length,
                ),
            )
        }

        val result = mutableListOf<VideoVariant>()
        val streams = fetchMasterPlaylist(raw.url)
        val best = streams?.maxWithOrNull(compareBy<HlsVariantStream>({ it.pixels }, { it.bandwidth ?: 0L }))
        val bitrate = best?.averageBandwidth ?: best?.bandwidth
        result += VideoVariant(
            url = raw.url,
            kind = VariantKind.HLS,
            width = best?.width ?: raw.width,
            height = best?.height ?: raw.height,
            formatId = raw.formatId,
            isHevc = best?.isHevc ?: VideoUrls.isHevc(raw.url, raw.formatId),
            bitrate = bitrate,
            estimatedBytes = if (bitrate != null && durationMs != null) bitrate * durationMs / 8_000 else null,
        )

        for (candidate in VideoUrls.derivedMp4Candidates(raw.url)) {
            if (candidate in listedMp4) continue
            val probe = probe(candidate) ?: continue
            if (!probe.ok || !probe.looksLikeVideo) continue
            val (width, height) = scaleToShortSide(raw.width ?: best?.width, raw.height ?: best?.height, 720)
            result += VideoVariant(
                url = candidate,
                kind = VariantKind.MP4,
                width = width,
                height = height,
                shortSideHint = 720,
                formatId = "V_720P",
                sizeBytes = probe.length,
                derived = true,
            )
            break
        }
        return result
    }

    private fun fetchMasterPlaylist(url: String): List<HlsVariantStream>? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            HlsPlaylist.parseMaster(response.body?.string().orEmpty(), response.request.url.toString())
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private class Probe(val code: Int, val length: Long?, val contentType: String?) {
        val ok: Boolean get() = code in 200..299
        val missing: Boolean get() = code == 404 || code == 410
        val looksLikeVideo: Boolean
            get() = contentType == null ||
                contentType.startsWith("video/") ||
                contentType.contains("octet-stream") ||
                contentType.contains("mp4")
    }

    /** HEAD request (with a 1-byte ranged GET fallback) to check a file and learn its size. */
    private fun probe(url: String): Probe? = try {
        val head = Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()
        http.newCall(head).execute().use { response ->
            if (response.code == 405 || response.code == 501) {
                rangeProbe(url)
            } else {
                Probe(response.code, response.header("Content-Length")?.toLongOrNull(), response.header("Content-Type"))
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun rangeProbe(url: String): Probe {
        val get = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-0").build()
        return http.newCall(get).execute().use { response ->
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val code = if (response.code == 206) 200 else response.code
            Probe(code, total ?: response.header("Content-Length")?.toLongOrNull(), response.header("Content-Type"))
        }
    }

    companion object {
        const val DEFAULT_WEB_BASE = "https://www.pinterest.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        private const val ACCEPT_LANGUAGE = "es-ES,es;q=0.9,en;q=0.8"
        private val API_FIELD_SETS = listOf("unauth_react_main_pin", "detailed")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        internal fun scaleToShortSide(width: Int?, height: Int?, target: Int): Pair<Int?, Int?> {
            if (width == null || height == null || width <= 0 || height <= 0) return null to null
            val shortSide = minOf(width, height)
            if (shortSide <= target) return width to height
            val scale = target.toDouble() / shortSide
            fun even(value: Double) = (Math.round(value / 2) * 2).toInt()
            return even(width * scale) to even(height * scale)
        }
    }
}
