package com.tvsas.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-written mappers from Orbita's JSON to app models.
 * No reflection, no code generation — fast on old hardware and free for R8 to shrink.
 */
object Json {

    fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    fun JSONObject.optObject(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

    fun category(o: JSONObject) = Category(
        id = o.getInt("id"),
        title = o.optString("title"),
        uri = o.optString("uri"),
        hidden = o.optBoolean("hidden", false),
    )

    fun categories(root: JSONObject): List<Category> =
        root.optJSONArray("rows").map(::category).filter { it.id > 0 }

    fun topic(o: JSONObject): Topic {
        val video = o.optObject("video")
        return Topic(
            id = o.getInt("id"),
            uuid = o.getString("uuid"),
            title = o.optString("title"),
            teaser = o.optString("teaser"),
            publishedAt = o.optString("published_at"),
            viewsCount = o.optInt("views_count"),
            coverUuid = o.optObject("cover")?.optStringOrNull("uuid"),
            categoryId = if (o.isNull("category_id")) null else o.optInt("category_id"),
            categoryTitle = o.optObject("category")?.optStringOrNull("title"),
            levelTitle = o.optObject("level")?.optStringOrNull("title"),
            access = o.optBoolean("access", true),
            paid = o.optBoolean("paid", false),
            videoUuid = video?.optStringOrNull("id"),
            durationSec = video?.optInt("duration") ?: 0,
            serverTimeSec = video?.let { if (it.isNull("time")) null else it.optInt("time") },
            tags = o.optJSONArray("tags").map { it.optString("title") }.filter { it.isNotEmpty() },
        )
    }

    fun topics(root: JSONObject): Page<Topic> =
        Page(root.optInt("total"), root.optJSONArray("rows").map(::topic))

    fun topicDetail(o: JSONObject): TopicDetail {
        val topic = topic(o)
        val blocks = o.optObject("content")?.optJSONArray("blocks")
        val videos = ArrayList<VideoBlock>()
        val text = StringBuilder()
        blocks.map { it }.forEach { block ->
            val data = block.optObject("data") ?: return@forEach
            when (block.optString("type")) {
                "video" -> data.optStringOrNull("uuid")?.let {
                    videos += VideoBlock(
                        uuid = it,
                        durationSec = data.optInt("duration"),
                        width = data.optInt("width"),
                        height = data.optInt("height"),
                    )
                }
                "paragraph", "header" -> data.optStringOrNull("text")?.let {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(stripHtml(it))
                }
                "list" -> data.optJSONArray("items")?.let { items ->
                    for (i in 0 until items.length()) {
                        val item = items.opt(i)
                        val s = if (item is JSONObject) item.optString("content") else item?.toString().orEmpty()
                        if (s.isEmpty()) continue
                        if (text.isNotEmpty()) text.append('\n')
                        text.append("• ").append(stripHtml(s))
                    }
                }
            }
        }
        // Fallback: the topic's own video when content has no explicit block.
        if (videos.isEmpty() && topic.videoUuid != null) {
            videos += VideoBlock(topic.videoUuid, topic.durationSec, 0, 0)
        }
        val description = if (text.isNotEmpty()) text.toString() else stripHtml(topic.teaser)
        return TopicDetail(topic, description, videos)
    }

    fun user(root: JSONObject): User {
        val u = root.optObject("user") ?: root
        val sub = u.optObject("subscription")
        val level = sub?.optObject("level")
        return User(
            id = u.optInt("id"),
            username = u.optString("username"),
            fullname = u.optString("fullname"),
            subscriptionLevel = level?.optStringOrNull("title"),
            subscriptionActiveUntil = sub?.optStringOrNull("active_until"),
        )
    }

    fun progress(root: JSONObject?): PlaybackProgress? {
        if (root == null || root.length() == 0) return null
        return PlaybackProgress(root.optInt("time"), root.optStringOrNull("quality"))
    }

    // History persisted locally as JSON

    fun historyToJson(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("topic", topicToJson(e.topic))
                put("video", e.videoUuid)
                put("pos", e.positionSec)
                put("dur", e.durationSec)
                put("at", e.updatedAt)
            })
        }
        return arr.toString()
    }

    fun historyFromJson(s: String?): List<HistoryEntry> {
        if (s.isNullOrEmpty()) return emptyList()
        return runCatching {
            JSONArray(s).map { o ->
                HistoryEntry(
                    topic = topic(o.getJSONObject("topic")),
                    videoUuid = o.getString("video"),
                    positionSec = o.optInt("pos"),
                    durationSec = o.optInt("dur"),
                    updatedAt = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun topicToJson(t: Topic): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("uuid", t.uuid)
        put("title", t.title)
        put("teaser", t.teaser)
        put("published_at", t.publishedAt)
        put("views_count", t.viewsCount)
        t.coverUuid?.let { put("cover", JSONObject().put("uuid", it)) }
        t.categoryId?.let { put("category_id", it) }
        t.categoryTitle?.let { put("category", JSONObject().put("title", it)) }
        t.levelTitle?.let { put("level", JSONObject().put("title", it)) }
        put("access", t.access)
        put("paid", t.paid)
        t.videoUuid?.let { put("video", JSONObject().put("id", it).put("duration", t.durationSec)) }
        put("tags", JSONArray().also { arr -> t.tags.forEach { tag -> arr.put(JSONObject().put("title", tag)) } })
    }

    fun stripHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    private inline fun <T> JSONArray?.map(fn: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            out += fn(item)
        }
        return out
    }
}
