package com.tvsas.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.data.Json

/** Fixtures are trimmed copies of real responses from sasflix.ru/api/web/... */
class JsonTest {

    private val topicsJson = """
        {"total":723,"rows":[
          {"id":1135,"uuid":"2c5205df-9223-4d5e-909b-e7220a40183c","title":"Немцы — снова Гитлер №214","teaser":"",
           "type":null,"level_id":null,"category_id":null,"price":null,"views_count":31753,"reactions_count":2016,
           "comments_count":537,"published_at":"2026-09-13T17:33:00.000000Z","tags":[{"id":9,"title":"Новости"}],
           "active":true,"cover":{"id":33631,"uuid":"83e5ad92-ce57-4b6a-b49a-5a22b9085f9a"},"category":null,"level":null,
           "access":true,"paid":false,"has_video":true,
           "video":{"id":"595418b2-d5ed-44c8-b55a-82c999d28e17","duration":9254,"time":null,"updated_at":0},
           "favorite":false,"reactions":{"1":2005,"2":11}},
          {"id":1137,"uuid":"f2b50e4f-a30d-4ae1-8805-8ad62694e121","title":"Стас и Полина","teaser":"",
           "type":"video","level_id":1,"category_id":5,"views_count":8010,"published_at":"2026-09-16T10:53:02.000000Z",
           "tags":[{"id":18,"title":"Полина"}],"active":true,
           "cover":{"id":33733,"uuid":"873ae4af-d9ad-4d24-ab59-0d7c2e22bbc3"},
           "category":{"id":5,"title":"Стас Комментатор","uri":"commentator"},
           "level":{"id":1,"title":"Токсичный","price":300},"access":false,"paid":true,"has_video":true,
           "video":{"id":"b04d258a-bdfa-4f8f-bd39-3d44a081a21f","duration":6534,"time":1200,"updated_at":0}}
        ]}
    """.trimIndent()

    @Test
    fun parsesTopicList() {
        val page = Json.topics(JSONObject(topicsJson))
        assertEquals(723, page.total)
        assertEquals(2, page.rows.size)

        val free = page.rows[0]
        assertEquals("2c5205df-9223-4d5e-909b-e7220a40183c", free.uuid)
        assertEquals("83e5ad92-ce57-4b6a-b49a-5a22b9085f9a", free.coverUuid)
        assertNull(free.categoryId)
        assertNull(free.categoryTitle)
        assertTrue(free.access)
        assertFalse(free.locked)
        assertEquals("595418b2-d5ed-44c8-b55a-82c999d28e17", free.videoUuid)
        assertEquals(9254, free.durationSec)
        assertNull(free.serverTimeSec)
        assertEquals(listOf("Новости"), free.tags)

        val paid = page.rows[1]
        assertTrue(paid.locked)
        assertTrue(paid.paid)
        assertEquals(5, paid.categoryId)
        assertEquals("Стас Комментатор", paid.categoryTitle)
        assertEquals("Токсичный", paid.levelTitle)
        assertEquals(1200, paid.serverTimeSec)
    }

    @Test
    fun parsesTopicDetailWithContentBlocks() {
        val json = """
            {"id":1,"uuid":"u","title":"T","teaser":"tz","published_at":"2026-01-01T00:00:00.000000Z",
             "access":true,"paid":false,"has_video":true,
             "video":{"id":"v1","duration":100,"time":null},
             "content":{"time":1,"blocks":[
               {"type":"paragraph","data":{"text":"Первый <b>абзац</b>&nbsp;текста"}},
               {"type":"video","data":{"uuid":"v1","duration":100,"width":1920,"height":1080}},
               {"type":"list","data":{"items":["раз","два"]}},
               {"type":"video","data":{"uuid":"v2","duration":50,"width":1280,"height":720}}
             ]}}
        """.trimIndent()
        val d = Json.topicDetail(JSONObject(json))
        assertEquals(2, d.videos.size)
        assertEquals("v2", d.videos[1].uuid)
        assertEquals("Первый абзац текста\n• раз\n• два", d.description)
    }

    @Test
    fun fallsBackToTopicVideoWhenNoBlocks() {
        val json = """{"id":1,"uuid":"u","title":"T","teaser":"<p>Тизер</p>","video":{"id":"v9","duration":7}}"""
        val d = Json.topicDetail(JSONObject(json))
        assertEquals(1, d.videos.size)
        assertEquals("v9", d.videos[0].uuid)
        assertEquals("Тизер", d.description)
    }

    @Test
    fun parsesCategoriesAndProfile() {
        val cats = Json.categories(JSONObject("""{"total":2,"rows":[{"id":1,"title":"Доки","uri":"documentary","hidden":false},{"id":5,"title":"X","uri":"x","hidden":true}]}"""))
        assertEquals(2, cats.size)
        assertTrue(cats[1].hidden)

        val user = Json.user(JSONObject("""{"user":{"id":7,"username":"stas","fullname":"Стас","subscription":{"active_until":"2027-01-01","level":{"id":1,"title":"Токсичный"}}}}"""))
        assertEquals("stas", user.username)
        assertEquals("Токсичный", user.subscriptionLevel)
    }

    @Test
    fun historyRoundTrips() {
        val topic = Json.topics(JSONObject(topicsJson)).rows[1]
        val entry = HistoryEntry(topic, "b04d258a", 1500, 6534, 123L)
        val restored = Json.historyFromJson(Json.historyToJson(listOf(entry)))
        assertEquals(1, restored.size)
        assertEquals(entry.copy(topic = entry.topic.copy(serverTimeSec = null)), restored[0])
        assertEquals(22, restored[0].percent)
    }

    @Test
    fun parsesChaptersSortedByTime() {
        val ch = Json.chapters(JSONObject("""{"02:52":"Безвиз с Китаем","00:00":"Начало","1:02:10":"Финал","bad":"x"}"""))
        assertEquals(listOf(0, 172, 3730), ch.map { it.startSec })
        assertEquals("Финал", ch[2].title)
        assertNull(Json.parseTimecode("1:2:3:4"))
    }

    @Test
    fun parsesStoryboardAndFindsTiles() {
        val sb = Json.storyboard(JSONObject("""{"file":{"uuid":"f1"},"tileWidth":213,"tileHeight":120,
            "tiles":[{"startTime":0,"x":0,"y":0},{"startTime":15,"x":213,"y":0},{"startTime":30,"x":0,"y":120}]}"""))!!
        assertEquals(426, sb.sheetWidth)
        assertEquals(240, sb.sheetHeight)
        assertEquals(15, sb.tileAt(29)!!.startSec)
        assertEquals(30, sb.tileAt(1000)!!.startSec)
        assertNull(Json.storyboard(JSONObject("{}")))
    }

    @Test
    fun corruptHistoryIsIgnored() {
        assertTrue(Json.historyFromJson("not json").isEmpty())
        assertTrue(Json.historyFromJson(null).isEmpty())
    }
}
