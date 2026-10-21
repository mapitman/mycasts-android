package com.bugzapperlabs.mycasts.data.local

/** The few [FeedItem] columns needed to work out an episode's downloaded file name (issue #302),
 *  so a query over every episode doesn't have to load each one's full description. */
data class EnclosureRef(val id: String, val enclosureUrl: String?, val enclosureType: String?)
