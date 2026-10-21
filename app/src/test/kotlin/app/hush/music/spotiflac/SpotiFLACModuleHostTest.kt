/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins what the module sandbox does, against a stubbed transport and a fixture module.
 *
 * This is the part of a module host that cannot be verified by reading it. A BitChord module is a
 * CommonJS script written for another app's sandbox, and the two ways it goes wrong are both silent:
 * a response shape that differs from the one the module reads (Rhino's Java-side JSON parser returns
 * arrays with no `Array.prototype`, so a module calling `results.slice(0, limit)` fails and reports an
 * empty search - measured, which is why `json()` goes through the engine's own parser), and a script
 * that reaches somewhere it should not. Each case below is one of those, with a module written to look
 * like the real one rather than like the host.
 */
class SpotiFLACModuleHostTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scriptRequests = AtomicInteger()
    private val apiRequests = AtomicInteger()

    /** The fixture module: the same shape the Archive module has, in a few lines. */
    private val fixtureModule =
        """
        (function () {
          'use strict';
          function searchTracks(query, limit, context) {
            return fetch('https://api.test/search?q=' + encodeURIComponent(query), {
              headers: { Accept: 'application/json' }
            }).then(function (resp) {
              if (!resp.ok) throw new Error('HTTP ' + resp.status);
              return resp.json();
            }).then(function (payload) {
              // `slice` on purpose: it is the call that fails when the JSON arrays the host hands
              // over have no Array.prototype.
              var docs = payload.response.docs.slice(0, limit);
              return { tracks: docs, total: docs.length };
            });
          }
          function getTrackStreamUrl(id, quality, context) {
            var url = 'https://api.test/stream/' + id;
            return fetch(url).then(function (resp) {
              var head = resp.text();
              if (head.indexOf('fLaC') !== 0) return { streamUrl: null };
              return {
                streamUrl: url,
                track: { id: id, audioQuality: 'LOSSLESS', mimeType: 'audio/flac' }
              };
            });
          }
          module.exports = {
            searchTracks: searchTracks,
            getTrackStreamUrl: getTrackStreamUrl,
            name: 'Fixture Module',
            version: '9.9.9'
          };
        })();
        """

    private val source =
        ExtensionSource(
            id = "fixture-index:archive-flac",
            name = "Internet Archive FLAC (archive.org)",
            runtime = ModuleRuntime,
            version = "1.0.0",
            downloadUrl = "https://index.test/modules/archive-flac.js",
        )

    private fun host(script: String = fixtureModule): SpotiFLACModuleHost {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        val url = request.url.toString()
                        when {
                            url.startsWith("https://index.test/") -> {
                                scriptRequests.incrementAndGet()
                                respond(url, script)
                            }

                            url.startsWith("https://api.test/search") -> {
                                apiRequests.incrementAndGet()
                                respond(url, searchPayload(), "application/json")
                            }

                            url.startsWith("https://api.test/stream/") -> {
                                apiRequests.incrementAndGet()
                                // What the module's own check reads: the first four bytes.
                                respond(url, "fLaC\u0000\u0000\u0000\u0022")
                            }

                            else -> respond(url, "", status = 404)
                        }
                    },
                ).build()
        return SpotiFLACModuleHost(httpClient = client, scriptDirectory = folder.newFolder("modules"))
    }

    private fun searchPayload(): String =
        """
        {
          "response": {
            "numFound": 2,
            "docs": [
              {"id": "TREASVRE_2023-03-11|01 Believe Me.flac", "title": "01 Believe Me", "artist": "TREASVRE", "album": "Bottom of the Hill", "duration": 277.4, "audioQuality": "LOSSLESS"},
              {"id": "PureHex_2023-04-18|02 Too Late.flac", "title": "02 Too Late", "artist": "Pure Hex", "album": "Live", "duration": 240, "audioQuality": "LOSSLESS"}
            ]
          }
        }
        """.trimIndent()

    private fun respond(
        url: String,
        body: String,
        contentType: String = "text/javascript",
        status: Int = 200,
    ): Response =
        Response
            .Builder()
            .request(okhttp3.Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "Not Found")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    @Test
    fun `a module searches through the host's fetch and its rows arrive whole`() = runTest {
        val module = host()

        val rows = module.search(source, "TREASVRE Believe Me").getOrThrow()

        assertEquals(2, rows.size)
        assertEquals("TREASVRE_2023-03-11|01 Believe Me.flac", rows[0].id)
        assertEquals("01 Believe Me", rows[0].title)
        assertEquals("TREASVRE", rows[0].artist)
        assertEquals("Bottom of the Hill", rows[0].album)
        // Seconds in the module's own field, milliseconds in Hush's.
        assertEquals(277_400L, rows[0].durationMs)
        assertEquals("LOSSLESS", rows[0].quality)
    }

    @Test
    fun `a module's stream URL is returned with its own quality claim`() = runTest {
        val module = host()

        val stream = module.stream(source, "TREASVRE_2023-03-11|01 Believe Me.flac").getOrThrow()

        assertNotNull(stream)
        assertEquals("https://api.test/stream/TREASVRE_2023-03-11|01 Believe Me.flac", stream?.url)
        assertEquals("LOSSLESS", stream?.quality)
        assertEquals("audio/flac", stream?.mimeType)
    }

    @Test
    fun `a refused stream URL is a successful answer, not a failure`() = runTest {
        // The module ran its own check and said no. Turning that into a failure would put a working
        // module into its cooldown for answering honestly.
        val module = host(script = fixtureModule.replace("fLaC", "MPEG"))
        module.clearFailure(source.id)

        val stream = module.stream(source, "anything").getOrThrow()

        assertNull(stream)
    }

    @Test
    fun `the script is fetched once and then served from the cache`() = runTest {
        val module = host()

        module.search(source, "one").getOrThrow()
        module.search(source, "two").getOrThrow()

        assertEquals(1, scriptRequests.get())
        assertEquals(2, apiRequests.get())
    }

    @Test
    fun `a module that fails is not re-run for every track`() = runTest {
        val module = host(script = "throw new Error('this module is broken');")
        module.searchBudgetMs = 2_000

        val first = module.search(source, "one")
        assertTrue(first.isFailure)

        val second = module.search(source, "two")
        assertTrue(second.isFailure)
        // The cooldown is what stops a broken module from being re-fetched and re-evaluated for every
        // track in a queue, each time costing the whole budget before the track fails.
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("failed recently"))
        assertEquals(1, scriptRequests.get())
    }

    @Test
    fun `a script that loops forever is interrupted, not waited on`() = runTest {
        val module = host(script = "module.exports = { searchTracks: function () { while (true) {} }, getTrackStreamUrl: function () { return Promise.resolve({ streamUrl: null }); } };")
        module.searchBudgetMs = 500
        val started = System.currentTimeMillis()

        val result = module.search(source, "anything")

        assertTrue(result.isFailure)
        assertTrue(
            "expected a budget failure, got ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("budget"),
        )
        // Without the instruction observer this never returns: a JavaScript loop cannot be cancelled
        // by a fetch timeout, and the track would hang instead of falling through to the next source.
        assertTrue("took ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 20_000)
    }

    @Test
    fun `a module cannot see the host's classes`() = runTest {
        val probing =
            """
            module.exports = {
              searchTracks: function () {
                return Promise.resolve({ tracks: [{
                  id: 'probe',
                  title: typeof Packages + ',' + typeof java + ',' + typeof getClass + ',' + typeof JavaAdapter,
                  artist: 'probe',
                  duration: 1
                }], total: 1 });
              },
              getTrackStreamUrl: function () { return Promise.resolve({ streamUrl: null }); }
            };
            """
        val module = host(script = probing)

        val rows = module.search(source, "anything").getOrThrow()

        // Every route to the host's process is absent, which is the whole point of running third-party
        // code in this engine rather than trusting it.
        assertEquals("undefined,undefined,undefined,undefined", rows.first().title)
    }

    @Test
    fun `a module that declares nothing Hush can call is reported as such`() = runTest {
        val module = host(script = "module.exports = { name: 'no functions' };")

        val probe = module.probe(source)

        assertTrue(probe.isFailure)
        assertTrue(probe.exceptionOrNull()?.message.orEmpty().contains("searchTracks"))
    }

    @Test
    fun `a module that exports both functions probes with its own name and version`() = runTest {
        val module = host()

        val verdict = module.probe(source).getOrThrow()

        assertEquals("Fixture Module 9.9.9", verdict)
    }

    @Test
    fun `a stream download lands on disk and reports its progress`() = runTest {
        val module = host()
        val destination = File(folder.newFolder("cache"), "track.flac")
        val progress = mutableListOf<Pair<Long, Long>>()

        val bytes = module.download("https://api.test/stream/1", destination) { received, total ->
            progress.add(received to total)
        }.getOrThrow()

        assertEquals(bytes, destination.length())
        assertTrue(bytes > 0L)
        assertTrue(progress.isNotEmpty())
        assertEquals(bytes, progress.last().first)
    }

    @Test
    fun `a failed download does not leave a file where the cache expects a track`() = runTest {
        val module = host()
        val destination = File(folder.newFolder("cache"), "track.flac")

        val result = module.download("https://api.test/missing", destination)

        assertTrue(result.isFailure)
        assertFalse(destination.exists())
    }

    @Test
    fun `a module argument is escaped so it cannot become code`() {
        // The call into a module is one evaluated expression, and a track title reaches it from the
        // user's own library - so the escaping is load-bearing, including the two characters JSON
        // leaves unescaped and JavaScript does not.
        assertEquals("\"a\\\"b\"", SpotiFLACModuleHost.jsString("a\"b"))
        assertEquals("\"a\\\\b\"", SpotiFLACModuleHost.jsString("a\\b"))
        assertEquals("\"a\\u2028b\"", SpotiFLACModuleHost.jsString("a\u2028b"))
        assertEquals("\"a\\u2029b\"", SpotiFLACModuleHost.jsString("a\u2029b"))
    }
}
