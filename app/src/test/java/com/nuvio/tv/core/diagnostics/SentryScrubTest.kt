package com.nuvio.tv.core.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SentryScrubTest {
    private val interceptor = SentryNetworkBreadcrumbInterceptor()

    @Test
    fun `xtream logins are removed from breadcrumb urls`() {
        assertEquals(
            "http://panel.example:8080/live/redacted/redacted/123.ts",
            interceptor.scrubbedUrl("http://panel.example:8080/live/joe/secret/123.ts".toHttpUrl())
        )
        assertEquals(
            "http://panel.example:8080/redacted/redacted/123.ts",
            interceptor.scrubbedUrl("http://panel.example:8080/joe/secret/123.ts".toHttpUrl())
        )
        assertEquals(
            "http://panel.example/player_api.php",
            interceptor.scrubbedUrl("http://panel.example/player_api.php?username=joe&password=secret".toHttpUrl())
        )
    }

    @Test
    fun `userinfo query fragment and archive logins never reach breadcrumbs`() {
        assertEquals(
            "https://panel.example/timeshift/redacted/redacted/60/2026-10-28:10-00/1.ts",
            interceptor.scrubbedUrl("https://alice:secret@panel.example/timeshift/user/password/60/2026-10-28:10-00/1.ts?token=private#private".toHttpUrl())
        )
    }
}
