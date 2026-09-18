package com.tvsas.app.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.dispose
import coil.load
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.data.Topic
import com.tvsas.app.data.TopicDetail
import com.tvsas.app.util.Format

/** "Show all" card at the end of a row. */
data class MoreItem(val categoryId: Int, val title: String)

/** Icon card in the bottom "Меню" row. */
data class MenuItem(val id: Int, val title: String, val icon: Int) {
    companion object {
        const val SEARCH = 1
        const val ACCOUNT = 2
        const val SETTINGS = 3
        const val REFRESH = 4
    }
}

/** 16:9 cover card for topics and history entries. */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val res = parent.resources
        val w = res.getDimensionPixelSize(R.dimen.card_width)
        val h = res.getDimensionPixelSize(R.dimen.card_height)
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // Leanback hides the info area until a row is "activated"; on TV we always want titles.
            cardType = BaseCardView.CARD_TYPE_INFO_UNDER
            infoVisibility = BaseCardView.CARD_REGION_VISIBLE_ALWAYS
            setMainImageDimensions(w, h)
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
            setMainImageAdjustViewBounds(false)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        val ctx = card.context
        val topic: Topic
        val content: String
        when (item) {
            is HistoryEntry -> {
                topic = item.topic
                content = ctx.getString(R.string.card_watched_pct, item.percent)
            }
            is Topic -> {
                topic = item
                content = listOfNotNull(
                    topic.categoryTitle,
                    Format.duration(topic.durationSec).takeIf { it.isNotEmpty() },
                    Format.date(topic.publishedAt).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
            }
            else -> return
        }
        card.titleText = topic.title
        card.contentText = content
        card.badgeImage = if (topic.locked) ContextCompat.getDrawable(ctx, R.drawable.ic_lock) else null

        val image = card.mainImageView ?: return
        val w = image.layoutParams.width
        val h = image.layoutParams.height
        val cover = topic.coverUuid
        if (cover != null) {
            image.load(Api.imageUrl(cover, roundSize(w), roundSize(h))) {
                placeholder(R.drawable.card_placeholder)
                error(R.drawable.card_placeholder)
                size(w, h)
            }
        } else {
            image.dispose()
            image.setImageResource(R.drawable.card_placeholder)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.mainImageView?.dispose()
        card.badgeImage = null
        card.mainImage = null
    }

    /** Round to a multiple of 80px so every box with a similar density hits the same CDN-cached size. */
    private fun roundSize(px: Int): Int = ((px + 79) / 80) * 80
}

/** Square icon card: "Показать всё", search, settings… */
class IconCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val size = parent.resources.getDimensionPixelSize(R.dimen.menu_card_size)
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            cardType = BaseCardView.CARD_TYPE_INFO_UNDER
            infoVisibility = BaseCardView.CARD_REGION_VISIBLE_ALWAYS
            setMainImageDimensions(size, size)
            setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)
            mainImageView?.setPadding(size / 4, size / 4, size / 4, size / 4)
            mainImageView?.setBackgroundResource(R.drawable.menu_card_bg)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        when (item) {
            is MenuItem -> {
                card.titleText = item.title
                card.contentText = null
                card.mainImage = ContextCompat.getDrawable(card.context, item.icon)
            }
            is MoreItem -> {
                card.titleText = card.context.getString(R.string.card_more)
                card.contentText = item.title
                card.mainImage = ContextCompat.getDrawable(card.context, R.drawable.ic_more)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }
}

/** Title / meta line / description on the details screen. */
class DescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(vh: ViewHolder, item: Any) {
        val detail = item as? TopicDetail ?: return
        val t = detail.topic
        val ctx = vh.view.context
        vh.title.text = t.title
        vh.subtitle.text = listOfNotNull(
            t.categoryTitle,
            Format.date(t.publishedAt).takeIf { it.isNotEmpty() },
            Format.duration(t.durationSec).takeIf { it.isNotEmpty() },
            ctx.getString(R.string.details_views, Format.count(t.viewsCount)),
            t.levelTitle?.let { "🔒 $it" }?.takeIf { t.paid },
        ).joinToString("  ·  ")
        vh.body.text = detail.description
    }
}
