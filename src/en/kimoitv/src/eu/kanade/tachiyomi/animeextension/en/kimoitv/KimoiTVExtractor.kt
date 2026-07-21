package eu.kanade.tachiyomi.animeextension.en.kimoitv

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import keiyoushi.utils.bodyString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import kotlin.coroutines.resume

/**
 * Solves the Cloudflare Turnstile challenge that guards KimoiTV's stream RPC and
 * extracts the resulting video URL.
 *
 * The watch/download page renders Turnstile with `render=explicit` and an obfuscated
 * callback; the token is POSTed to `/streamvpaid.php` in a field literally named
 * `token` (together with `d` = #fileInfo[data-name] and `id` = #fileInfo[data-id]).
 * Without a valid token the endpoint replies "Invalid Ticket!".
 *
 * Modeled on the proven `SendNowExtractor` WebView approach.
 */
class KimoiTVExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val tag by lazy { javaClass.simpleName }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<Video> {
        // Use the WebView's native UA so its Chrome version matches the actual engine.
        // Cloudflare Turnstile fails (runs the challenge but never issues a token) when the
        // UA's Chrome version doesn't match the WebView engine version (Mihon #3177).
        // getDefaultUserAgent can throw on devices without the WebView package, so fall back.
        val fallbackUa =
            headers["User-Agent"]
                ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUa
        Log.d(tag, "Using UA: $userAgent")

        val chromeVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "143"
        val isMobile = userAgent.contains("Android") || userAgent.contains("Mobile")

        val secChUa =
            "\"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\", \"Not A(Brand\";v=\"24\""

        val platform = when {
            userAgent.contains("Windows") -> "\"Windows\""
            userAgent.contains("Android") -> "\"Android\""
            userAgent.contains("Mac") -> "\"macOS\""
            userAgent.contains("Linux") -> "\"Linux\""
            else -> "\"Windows\""
        }

        val host = url.toHttpUrl().host

        val newHeaders = headers.newBuilder().apply {
            removeAll("Referer")
            set(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            )
            set("Accept-Encoding", "deflate")
            set("accept-language", "en-US,en;q=0.9")
            set("cache-control", "max-age=600")
            set("Connection", "keep-alive")
            set("Host", host)
            set("sec-ch-ua", secChUa)
            set("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
            set("sec-ch-ua-platform", platform)
            set("Sec-Fetch-Dest", "document")
            set("Sec-Fetch-Mode", "navigate")
            set("Sec-Fetch-Site", "none")
            set("Sec-Fetch-User", "?1")
            set("Upgrade-Insecure-Requests", "1")
            set("User-Agent", userAgent)
        }.build()

        // Solve Cloudflare Turnstile in a WebView and capture the token + the #fileInfo
        // fields from the same page the WebView loaded.
        Log.d(tag, "Opening WebView to solve Cloudflare Turnstile...")
        val result = try {
            withTimeout(60_000) { solveTurnstile(url, userAgent) }
        } catch (_: TimeoutCancellationException) {
            Log.e(tag, "Turnstile solving timed out")
            return emptyList()
        } catch (e: Exception) {
            // Preserve coroutine cancellation; only treat other failures as "no videos".
            if (e is CancellationException) throw e
            Log.e(tag, "Turnstile solving failed", e)
            return emptyList()
        }

        if (result.token.isBlank()) {
            Log.e(tag, "Turnstile token is blank")
            return emptyList()
        }
        if (result.id.isBlank() || result.name.isBlank()) {
            Log.e(tag, "Missing fileInfo id/name (id='${result.id}' name='${result.name}')")
            return emptyList()
        }
        Log.d(tag, "Turnstile solved (token length=${result.token.length}, id=${result.id}, d=${result.name})")

        val cookies = webViewCookies("https://$host/")

        val postHeaders = newHeaders.newBuilder().apply {
            set("Accept", "*/*")
            // Let OkHttp negotiate/decode content encoding itself so the body is always
            // decompressed before we parse it.
            removeAll("Accept-Encoding")
            set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            set("Origin", "https://$host")
            set("Referer", url)
            set("Sec-Fetch-Dest", "empty")
            set("Sec-Fetch-Mode", "cors")
            set("Sec-Fetch-Site", "same-origin")
            set("X-Requested-With", "XMLHttpRequest")
            if (cookies.isNotBlank()) set("Cookie", cookies)
        }.build()

        // The real site POSTs { d: data-name, id: data-id, token: <turnstile token> }.
        val formBody = FormBody.Builder()
            .add("d", result.name)
            .add("id", result.id)
            .add("token", result.token)
            .build()

        val bodyString = client.newCall(POST("https://$host/streamvpaid.php", postHeaders, formBody))
            .awaitSuccess()
            .bodyString()
        if (bodyString.contains("Invalid Ticket", ignoreCase = true)) {
            Log.e(tag, "streamvpaid.php rejected the token: ${bodyString.take(120)}")
            error("Invalid Ticket! Reload this page and wait 5 second to load Player.")
        }

        // The response is an HTML fragment injected into #player, e.g. <video><source src=...>.
        val videoDoc = Jsoup.parse(bodyString, url)

        val source = videoDoc.selectFirst("source")
        if (source == null) {
            Log.e(tag, "No <source> element in POST response")
            return emptyList()
        }

        val videoUrl = source.attr("abs:src").ifBlank { source.attr("src") }
        if (videoUrl.isBlank()) {
            Log.e(tag, "Blank video URL in <source>")
            return emptyList()
        }
        Log.d(tag, "Video source found: ${videoUrl.toHttpUrlOrNull()?.host ?: "unknown"}")

        val videoHeaders = headers.newBuilder()
            .set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .set("Referer", "https://$host/")
            .apply {
                videoUrl.toHttpUrlOrNull()?.host?.let { set("Host", it) }
            }
            .build()

        return when {
            videoUrl.contains(".m3u8") -> playlistUtils.extractFromHls(
                videoUrl,
                referer = "https://$host/",
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
            )
            else -> listOf(Video(videoUrl, "Video", videoUrl, headers = videoHeaders))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveTurnstile(url: String, userAgent: String): TurnstileResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var injected = false

                val webView = WebView(applicationContext)
                val jsInterface = TurnstileJsInterface { res ->
                    if (continuation.isActive) {
                        handler.post { webView.destroy() }
                        continuation.resume(res)
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent
                // Cloudflare's challenge runs on challenges.cloudflare.com (cross-origin from
                // the target host). Without third-party cookies the challenge state can't be
                // persisted, so Turnstile errors out and never issues a token.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.addJavascriptInterface(jsInterface, "turnstileBridge")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl?.contains("/cdn-cgi/") == true) return
                        if (injected) return
                        injected = true
                        view?.evaluateJavascript(POLL_SCRIPT) {}
                    }
                }
                webView.loadUrl(url, buildWebViewHeaders(userAgent))

                continuation.invokeOnCancellation {
                    handler.post { webView.destroy() }
                }
            }
        }
    }

    private fun buildWebViewHeaders(userAgent: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private suspend fun webViewCookies(url: String): String = withContext(Dispatchers.Main) {
        runCatching {
            CookieManager.getInstance()
                .getCookie(url)
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("; ")
        }.getOrNull().orEmpty()
    }

    private data class TurnstileResult(
        val token: String,
        val id: String,
        val name: String,
    )

    private class TurnstileJsInterface(private val onResult: (TurnstileResult) -> Unit) {
        private var delivered = false

        @JavascriptInterface
        fun onResult(token: String, id: String, name: String) {
            if (delivered) return
            delivered = true
            onResult(TurnstileResult(token, id, name))
        }
    }

    companion object {
        private val CHROME_REGEX = Regex("""Chrome/(\d+)""")

        // The page loads turnstile api.js with render=explicit and renders a widget into
        // #dl-container with a callback. Turnstile's default response-field creates a hidden
        // input[name="cf-turnstile-response"] whose value is set once the challenge solves,
        // so we poll for it first. If the widget never creates that input we fall back to
        // rendering our own widget with the same (deobfuscated) sitekey and capture the token
        // from the callback, exactly like the page does.
        private const val SITE_KEY = "0x4AAAAAAAMl1cZ-nVDRVerW"

        private val POLL_SCRIPT = """
            (function() {
                var done = false;
                function finish(token) {
                    if (done) return;
                    done = true;
                    var fi = document.getElementById('fileInfo');
                    var id = fi ? (fi.getAttribute('data-id') || '') : '';
                    var name = fi ? (fi.getAttribute('data-name') || '') : '';
                    turnstileBridge.onResult(token, id, name);
                }
                var tries = 0;
                var renderedOwn = false;
                var interval = setInterval(function() {
                    tries++;
                    var input = document.querySelector('input[name="cf-turnstile-response"]');
                    if (input && input.value) {
                        clearInterval(interval);
                        finish(input.value);
                    } else if (!renderedOwn && tries > 16 && !input && typeof window.turnstile !== 'undefined') {
                        renderedOwn = true;
                        var div = document.createElement('div');
                        div.style.display = 'none';
                        document.body.appendChild(div);
                        try {
                            window.turnstile.render(div, {
                                sitekey: '$SITE_KEY',
                                callback: function(t) { clearInterval(interval); finish(t); }
                            });
                        } catch (e) {}
                    } else if (tries > 200) {
                        clearInterval(interval);
                        finish('');
                    }
                }, 250);
            })();
        """.trimIndent()
    }
}
