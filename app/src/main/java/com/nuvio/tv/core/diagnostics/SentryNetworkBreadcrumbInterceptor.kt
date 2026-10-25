package com.nuvio.tv.core.diagnostics

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class SentryNetworkBreadcrumbInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!Sentry.isEnabled()) return chain.proceed(request)

        val startedAtNs = System.nanoTime()
        try {
            val response = chain.proceed(request)
            record(
                url = request.url,
                method = request.method,
                statusCode = response.code,
                elapsedMs = elapsedMs(startedAtNs),
                error = null
            )
            return response
        } catch (error: IOException) {
            record(
                url = request.url,
                method = request.method,
                statusCode = null,
                elapsedMs = elapsedMs(startedAtNs),
                error = error
            )
            throw error
        } catch (error: RuntimeException) {
            record(
                url = request.url,
                method = request.method,
                statusCode = null,
                elapsedMs = elapsedMs(startedAtNs),
                error = error
            )
            throw error
        }
    }

    private fun record(
        url: HttpUrl,
        method: String,
        statusCode: Int?,
        elapsedMs: Long,
        error: Throwable?
    ) {
        val breadcrumb = if (statusCode == null) {
            Breadcrumb.http(scrubbedUrl(url), method)
        } else {
            Breadcrumb.http(scrubbedUrl(url), method, statusCode)
        }
        breadcrumb.setCategory("http.client")
        breadcrumb.setLevel(levelFor(statusCode, error))
        breadcrumb.setData("host", url.host)
        breadcrumb.setData("path", scrubbedUrl(url).toHttpUrlOrNull()?.encodedPath ?: "")
        breadcrumb.setData("elapsed_ms", elapsedMs)
        if (error != null) {
            breadcrumb.setData("error_type", error.javaClass.name)
            // Exception messages can quote the request URL, login included.
            error.message?.let { breadcrumb.setData("error_message", it.replace(URL_IN_TEXT, "<url>").take(240)) }
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    private fun levelFor(statusCode: Int?, error: Throwable?): SentryLevel {
        if (error != null) return SentryLevel.ERROR
        val code = statusCode ?: return SentryLevel.INFO
        return when {
            code >= 500 -> SentryLevel.ERROR
            code >= 400 -> SentryLevel.WARNING
            else -> SentryLevel.INFO
        }
    }

    internal fun scrubbedUrl(url: HttpUrl): String {
        val builder = url.newBuilder()
            .query(null)
            .fragment(null)
        // Xtream-style stream URLs carry the login in the path: /live/<user>/<pass>/<id>.ts
        val segments = url.pathSegments
        if (segments.size >= 4 && segments[0].lowercase() in XTREAM_PATH_PREFIXES) {
            builder.setPathSegment(1, "redacted").setPathSegment(2, "redacted")
        } else if (segments.size == 3 && XTREAM_SHORT_ID.matches(segments[2])) {
            // Short Xtream form: /<user>/<pass>/<id>.ts
            builder.setPathSegment(0, "redacted").setPathSegment(1, "redacted")
        }
        return builder.build().toString()
    }

    private companion object {
        val XTREAM_PATH_PREFIXES = setOf("live", "movie", "series", "timeshift")
        val XTREAM_SHORT_ID = Regex("""\d+(\.\w{1,5})?""")
        val URL_IN_TEXT = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return (System.nanoTime() - startedAtNs) / 1_000_000L
    }
}
