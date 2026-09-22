package com.maxrave.domain.manager

/** One atomic DataStore snapshot; credentials must never appear in logs. */
data class YouTubeSession(
    val loggedIn: Boolean,
    val cookie: String,
    val pageId: String?,
    val authUser: Int,
) {
    val authenticated: Boolean get() = loggedIn && cookie.isNotBlank()

    override fun toString(): String = "YouTubeSession(redacted)"
}