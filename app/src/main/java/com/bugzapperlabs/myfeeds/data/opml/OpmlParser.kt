package com.bugzapperlabs.myfeeds.data.opml

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class OpmlFeed(val title: String, val xmlUrl: String, val description: String? = null)

/**
 * A folder-level outline grouping feeds. Not the app's (removed, issue #118) `Category` entity --
 * subscribing (via [OpmlImporter]/[com.bugzapperlabs.myfeeds.data.DefaultFeedsSeeder]) ignores [name] and
 * just flattens every folder's feeds; only [com.bugzapperlabs.myfeeds.data.directory.FeedDirectory] (a
 * different feature, issue #62) uses [name] as a topic label for its offline keyword search.
 */
data class OpmlFolder(val name: String, val feeds: List<OpmlFeed>)

data class OpmlDocument(val folders: List<OpmlFolder>) {
    val feeds: List<OpmlFeed> get() = folders.flatMap { it.feeds }
}

/**
 * Ported rule from MyFeeds/Opml.cs: an outline without `xmlUrl` is a folder, a nested outline with
 * `xmlUrl` is a feed within it, and a feed outline directly under `<body>` (no parent folder)
 * falls into "Uncategorized".
 */
object OpmlParser {
    private const val UNCATEGORIZED = "Uncategorized"

    fun parse(input: InputStream): OpmlDocument {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        val body = document.getElementsByTagName("body").item(0) as? Element
            ?: return OpmlDocument(emptyList())

        val folders = mutableListOf<OpmlFolder>()
        val looseFeeds = mutableListOf<OpmlFeed>()

        for (outline in body.childElements("outline")) {
            val xmlUrl = outline.getAttribute("xmlUrl")
            if (xmlUrl.isNullOrBlank()) {
                val feeds = outline.childElements("outline")
                    .mapNotNull { it.toOpmlFeedOrNull() }
                folders += OpmlFolder(name = outline.outlineTitle(), feeds = feeds)
            } else {
                looseFeeds += OpmlFeed(
                    title = outline.outlineTitle(),
                    xmlUrl = xmlUrl,
                    description = outline.getAttribute("description").ifBlank { null },
                )
            }
        }

        if (looseFeeds.isNotEmpty()) {
            folders += OpmlFolder(name = UNCATEGORIZED, feeds = looseFeeds)
        }

        return OpmlDocument(folders)
    }

    private fun Element.childElements(tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tagName) result += node
        }
        return result
    }

    private fun Element.toOpmlFeedOrNull(): OpmlFeed? {
        val xmlUrl = getAttribute("xmlUrl")
        if (xmlUrl.isNullOrBlank()) return null
        return OpmlFeed(
            title = outlineTitle(),
            xmlUrl = xmlUrl,
            description = getAttribute("description").ifBlank { null },
        )
    }

    private fun Element.outlineTitle(): String =
        getAttribute("text").ifBlank { getAttribute("title") }
}
