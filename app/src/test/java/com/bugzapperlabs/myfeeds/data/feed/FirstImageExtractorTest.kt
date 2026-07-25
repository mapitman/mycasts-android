package com.bugzapperlabs.myfeeds.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstImageExtractorTest {
    @Test
    fun extractFirstImageUrl_resolvesRelativeSrcAgainstBaseUrl() {
        val html = "<p>text</p><img src=\"/images/photo.jpg\" />"

        val result = FirstImageExtractor.extractFirstImageUrl(html, "https://example.com/article-1")

        assertEquals("https://example.com/images/photo.jpg", result)
    }

    @Test
    fun extractFirstImageUrl_keepsAbsoluteSrcAsIs() {
        val html = "<img src=\"https://cdn.example.com/photo.jpg\" />"

        val result = FirstImageExtractor.extractFirstImageUrl(html, "https://example.com/article-1")

        assertEquals("https://cdn.example.com/photo.jpg", result)
    }

    @Test
    fun extractFirstImageUrl_multipleImages_returnsFirstInDocumentOrder() {
        val html = """
            <img src="https://example.com/first.jpg" />
            <img src="https://example.com/second.jpg" />
        """.trimIndent()

        val result = FirstImageExtractor.extractFirstImageUrl(html, "https://example.com")

        assertEquals("https://example.com/first.jpg", result)
    }

    @Test
    fun extractFirstImageUrl_gifIsReturnedDirectly_noConversionProxy() {
        val html = "<img src=\"https://example.com/animated.gif\" />"

        val result = FirstImageExtractor.extractFirstImageUrl(html, "https://example.com")

        assertEquals("https://example.com/animated.gif", result)
    }

    @Test
    fun extractFirstImageUrl_httpSrc_upgradedToHttps() {
        // issue #149: plain http:// images are silently blocked by cleartext-traffic
        // restrictions (targetSdk 28+).
        val html = "<img src=\"http://example.com/photo.jpg\" />"

        val result = FirstImageExtractor.extractFirstImageUrl(html, "https://example.com/article-1")

        assertEquals("https://example.com/photo.jpg", result)
    }

    @Test
    fun extractFirstImageUrl_noImgTags_returnsNull() {
        assertNull(FirstImageExtractor.extractFirstImageUrl("<p>just text</p>", "https://example.com"))
    }

    @Test
    fun extractFirstImageUrl_blankHtml_returnsNull() {
        assertNull(FirstImageExtractor.extractFirstImageUrl("", "https://example.com"))
    }

    @Test
    fun extractAndStripFirstImage_removesOnlyTheMatchedImgFromDescription() {
        // issue #222: the item's own description often already embeds this same lead image --
        // once it's shown separately as the hero image, leaving it in the body renders it twice.
        val html = "<p>Intro</p><img src=\"https://example.com/hero.jpg\" /><p>Body text</p>"

        val result = FirstImageExtractor.extractAndStripFirstImage(html, "https://example.com")

        assertEquals("https://example.com/hero.jpg", result.url)
        assertEquals("<p>Intro</p>\n<p>Body text</p>", result.descriptionWithoutImage)
    }

    @Test
    fun extractAndStripFirstImage_onlyRemovesTheFirstImage_keepsLaterOnes() {
        val html = "<img src=\"https://example.com/first.jpg\" /><img src=\"https://example.com/second.jpg\" />"

        val result = FirstImageExtractor.extractAndStripFirstImage(html, "https://example.com")

        assertEquals("https://example.com/first.jpg", result.url)
        assertEquals("<img src=\"https://example.com/second.jpg\">", result.descriptionWithoutImage)
    }

    @Test
    fun extractAndStripFirstImage_noImgTags_returnsHtmlUnchanged() {
        val result = FirstImageExtractor.extractAndStripFirstImage("<p>just text</p>", "https://example.com")

        assertNull(result.url)
        assertEquals("<p>just text</p>", result.descriptionWithoutImage)
    }

    @Test
    fun extractAndStripFirstImage_blankHtml_returnsBlankUnchanged() {
        val result = FirstImageExtractor.extractAndStripFirstImage("", "https://example.com")

        assertNull(result.url)
        assertEquals("", result.descriptionWithoutImage)
    }
}
