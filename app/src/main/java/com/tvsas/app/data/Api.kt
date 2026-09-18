package com.tvsas.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : IOException(message)

/**
 * Thin client for the Orbita REST API that powers sasflix.ru.
 * Endpoints are taken from the open-source engine (github.com/bezumkin/orbita).
 */
object Api {
    const val HOST = "sasflix.ru"
    const val BASE = "https://$HOST"
    private const val API = "$BASE/api"
    const val USER_AGENT = "TVsas/1.0 (Android TV; unofficial sasflix.ru client)"
    private const val HEADER_TTL = "X-App-Cache-Ttl"

    lateinit var client: OkHttpClient
        private set

    fun init(context: Context) {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 8L * 1024 * 1024))
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Accept", "application/json")
                Prefs.token?.let { b.header("Authorization", "Bearer $it") }
                chain.proceed(b.build())
            }
            // The server sends no caching headers; make public catalogue GETs cacheable so that
            // relaunches and back-navigation are instant and the box does less network work.
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val ttl = req.header(HEADER_TTL)
                val resp = chain.proceed(req.newBuilder().removeHeader(HEADER_TTL).build())
                if (ttl != null && req.method == "GET" && resp.isSuccessful) {
                    resp.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=$ttl")
                        .build()
                } else resp
            }
            .build()
    }

    // ---- Public catalogue ----

    suspend fun categories(): List<Category> =
        Json.categories(getJson("$API/web/categories", cacheSeconds = 600))
            .filter { !it.hidden }

    suspend fun topics(
        categoryId: Int? = null,
        page: Int = 1,
        limit: Int = 20,
        sort: String = "date",
    ): Page<Topic> {
        val url = "$API/web/topics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort)
            .apply { if (categoryId != null) addQueryParameter("category_id", categoryId.toString()) }
            .build()
        return Json.topics(getJson(url, cacheSeconds = 120))
    }

    suspend fun topic(uuid: String): TopicDetail =
        Json.topicDetail(getJson("$API/web/topics/$uuid", cacheSeconds = 60))

    suspend fun search(query: String, page: Int = 1, limit: Int = 24): Page<Topic> {
        val url = "$API/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return Json.topics(getJson(url, cacheSeconds = 60))
    }

    // ---- Auth ----

    suspend fun login(username: String, password: String): String {
        val body = jsonBody(JSONObject().put("username", username).put("password", password))
        val json = request(Request.Builder().url("$API/security/login").post(body).build())
        return json.optString("token").ifEmpty { throw ApiException("errors.login.wrong") }
    }

    suspend fun profile(): User = Json.user(getJson("$API/user/profile", cacheSeconds = 0))

    suspend fun logout() {
        runCatching { request(Request.Builder().url("$API/user/logout").post(jsonBody(JSONObject())).build()) }
    }

    /** Responses depend on the logged-in user (access flags), so drop cached ones on login/logout. */
    fun clearCache() {
        runCatching { client.cache?.evictAll() }
    }

    // ---- Playback progress (server side, logged-in users only) ----

    suspend fun progress(videoUuid: String): PlaybackProgress? =
        runCatching { Json.progress(getJson("$API/user/video/$videoUuid", cacheSeconds = 0)) }.getOrNull()

    suspend fun saveProgress(videoUuid: String, timeSec: Int, quality: String) {
        val body = jsonBody(
            JSONObject().put("quality", quality).put("time", timeSec).put("volume", 1).put("speed", 1),
        )
        runCatching { request(Request.Builder().url("$API/user/video/$videoUuid").post(body).build()) }
    }

    // ---- URLs ----

    /** Server-side resized WebP cover — keeps decode cost and RAM low on old boxes. */
    fun imageUrl(uuid: String, w: Int, h: Int): String =
        "$API/image/$uuid?w=$w&h=$h&fit=crop&fm=webp"

    /** HLS master playlist. The token travels as a query param so it is propagated into variant URLs. */
    fun videoUrl(videoUuid: String): String {
        val token = Prefs.token
        return if (token != null) "$API/video/$videoUuid?token=$token" else "$API/video/$videoUuid"
    }

    // ---- Internals ----

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun jsonBody(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON_TYPE)

    private suspend fun getJson(url: String, cacheSeconds: Int): JSONObject = getJson(url.toHttpUrl(), cacheSeconds)

    private suspend fun getJson(url: HttpUrl, cacheSeconds: Int): JSONObject {
        val b = Request.Builder().url(url)
        if (cacheSeconds > 0) {
            b.header(HEADER_TTL, cacheSeconds.toString())
            b.cacheControl(CacheControl.Builder().maxAge(cacheSeconds, TimeUnit.SECONDS).build())
        } else {
            b.cacheControl(CacheControl.FORCE_NETWORK)
        }
        return request(b.build())
    }

    private suspend fun request(req: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Vesp returns failures as a bare JSON string: "errors.login.wrong"
                val msg = runCatching { JSONObject("{\"m\":$text}").getString("m") }.getOrDefault(text)
                throw ApiException(msg.ifEmpty { "HTTP ${resp.code}" }, resp.code)
            }
            if (text.isEmpty() || text == "null") return@use JSONObject()
            runCatching { JSONObject(text) }.getOrElse { throw ApiException("Bad response") }
        }
    }
}
