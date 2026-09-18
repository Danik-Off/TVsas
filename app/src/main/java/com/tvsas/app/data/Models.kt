package com.tvsas.app.data

import android.os.Parcel
import android.os.Parcelable

/** A site section ("Доки", "Икиностас", ...). */
data class Category(
    val id: Int,
    val title: String,
    val uri: String,
    val hidden: Boolean,
)

/** A post in the feed. Only the fields the TV UI needs are kept. */
data class Topic(
    val id: Int,
    val uuid: String,
    val title: String,
    val teaser: String,
    val publishedAt: String,
    val viewsCount: Int,
    val coverUuid: String?,
    val categoryId: Int?,
    val categoryTitle: String?,
    val levelTitle: String?,
    val access: Boolean,
    val paid: Boolean,
    val videoUuid: String?,
    val durationSec: Int,
    /** Last playback position in seconds reported by the server for the logged-in user. */
    val serverTimeSec: Int?,
    val tags: List<String>,
) : Parcelable {
    val hasVideo: Boolean get() = videoUuid != null
    val locked: Boolean get() = !access

    override fun describeContents() = 0

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeInt(id)
        p.writeString(uuid)
        p.writeString(title)
        p.writeString(teaser)
        p.writeString(publishedAt)
        p.writeInt(viewsCount)
        p.writeString(coverUuid)
        p.writeInt(categoryId ?: -1)
        p.writeString(categoryTitle)
        p.writeString(levelTitle)
        p.writeInt(if (access) 1 else 0)
        p.writeInt(if (paid) 1 else 0)
        p.writeString(videoUuid)
        p.writeInt(durationSec)
        p.writeInt(serverTimeSec ?: -1)
        p.writeStringList(tags)
    }

    companion object CREATOR : Parcelable.Creator<Topic> {
        override fun createFromParcel(p: Parcel): Topic = Topic(
            id = p.readInt(),
            uuid = p.readString().orEmpty(),
            title = p.readString().orEmpty(),
            teaser = p.readString().orEmpty(),
            publishedAt = p.readString().orEmpty(),
            viewsCount = p.readInt(),
            coverUuid = p.readString(),
            categoryId = p.readInt().takeIf { it >= 0 },
            categoryTitle = p.readString(),
            levelTitle = p.readString(),
            access = p.readInt() == 1,
            paid = p.readInt() == 1,
            videoUuid = p.readString(),
            durationSec = p.readInt(),
            serverTimeSec = p.readInt().takeIf { it >= 0 },
            tags = mutableListOf<String>().also { p.readStringList(it) },
        )

        override fun newArray(size: Int): Array<Topic?> = arrayOfNulls(size)
    }
}

/** A video block inside a topic's editor.js content. */
data class VideoBlock(
    val uuid: String,
    val durationSec: Int,
    val width: Int,
    val height: Int,
)

/** Full topic with parsed content: plain-text description and the list of videos. */
data class TopicDetail(
    val topic: Topic,
    val description: String,
    val videos: List<VideoBlock>,
)

data class Page<T>(val total: Int, val rows: List<T>)

data class User(
    val id: Int,
    val username: String,
    val fullname: String,
    val subscriptionLevel: String?,
    val subscriptionActiveUntil: String?,
)

/** A chapter marker from the video's description ("mm:ss" → title). */
data class Chapter(val startSec: Int, val title: String)

/** Sprite sheet of preview frames used by the seek bar. */
data class Storyboard(
    val fileUuid: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: List<Tile>,
) {
    data class Tile(val startSec: Int, val x: Int, val y: Int)

    val sheetWidth: Int get() = (tiles.maxOfOrNull { it.x } ?: 0) + tileWidth
    val sheetHeight: Int get() = (tiles.maxOfOrNull { it.y } ?: 0) + tileHeight

    /** Tile that covers [positionSec] (the last one starting at or before it). */
    fun tileAt(positionSec: Int): Tile? {
        var best: Tile? = null
        for (t in tiles) { if (t.startSec <= positionSec) best = t else break }
        return best
    }
}

data class PlaybackProgress(
    val timeSec: Int,
    val quality: String?,
)

/** Locally stored "continue watching" entry. */
data class HistoryEntry(
    val topic: Topic,
    val videoUuid: String,
    val positionSec: Int,
    val durationSec: Int,
    val updatedAt: Long,
) {
    val percent: Int get() = if (durationSec > 0) (positionSec * 100 / durationSec).coerceIn(0, 100) else 0
}
