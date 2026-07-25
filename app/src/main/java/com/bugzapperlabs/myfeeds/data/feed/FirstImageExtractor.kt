package com.bugzapperlabs.myfeeds.data.feed

import org.jsoup.Jsoup

/**
 * Ported from MyFeeds/HtmlUtility.cs::FindImages: scan an item's description/content HTML for
 * `<img src>` tags in document order, resolve relative URLs against the item's link, and use the
 * first one as the item's image.
 *
 * The original also proxied `.gif` URLs through a personal `Gif2Jpg.aspx` conversion service,
 * because Silverlight's Image control couldn't render animated GIFs. That service is long gone
 * and unnecessary here -- Coil (this port's image loader, see plan) renders GIFs natively -- so
 * this is intentionally dropped rather than ported.
 */
data class FirstImageResult(val url: String?, val descriptionWithoutImage: String)

object FirstImageExtractor {
    fun extractFirstImageUrl(html: String, baseUrl: String): String? =
        extractAndStripFirstImage(html, baseUrl).url

    /**
     * Same extraction as [extractFirstImageUrl], but also returns [html] with that matched `<img>`
     * tag removed. The item's own description/content often already has this same image inline --
     * once it's shown separately as the item's hero image, leaving it in the body renders it a
     * second time at a different size (issue #222).
     */
    fun extractAndStripFirstImage(html: String, baseUrl: String): FirstImageResult {
        if (html.isBlank()) return FirstImageResult(null, html)

        val document = try {
            Jsoup.parse(html, baseUrl)
        } catch (_: Exception) {
            return FirstImageResult(null, html)
        }

        val img = document.select("img[src]").firstOrNull() ?: return FirstImageResult(null, html)
        val url = img.attr("abs:src").ifBlank { null }?.upgradeToHttps()
        img.remove()
        return FirstImageResult(url, document.body().html())
    }
}
