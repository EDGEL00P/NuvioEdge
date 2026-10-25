package com.nuvio.tv.data.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Servers that carry IPTV logins in their stream URLs (Xtream puts user and password in the path).
 * The shared video player normally retries a failed TLS handshake without certificate checks (for
 * self-signed home media servers) and follows https -> http redirects; for these servers it must
 * do neither, or the login could be sent to an impostor or in clear text.
 */
object LiveStreamGuard {
    private val hosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Marks the server behind [url] as carrying a login. */
    fun protect(url: String?) {
        url?.toHttpUrlOrNull()?.host?.lowercase()?.let(hosts::add)
    }

    fun isProtected(host: String): Boolean = host.lowercase() in hosts

    /**
     * Network interceptor: refuses https -> http redirects, either for every request ([always]) or
     * only for protected servers. A redirect from a protected server to another https server
     * protects that server too, since the login travels in the redirected URL.
     */
    fun redirectGuard(always: Boolean = false): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val guarded = always || isProtected(request.url.host)
        if (guarded && response.isRedirect) {
            val location = response.header("Location")
            if (IptvSafety.isInsecureRedirect(request.isHttps, location)) {
                response.close()
                throw IptvSafety.InsecureRedirectException()
            }
            location?.let { request.url.resolve(it) }?.let { protect(it.toString()) }
        }
        response
    }

    internal fun clearForTest() = hosts.clear()
}
