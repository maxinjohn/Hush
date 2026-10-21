/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.di.ModuleScriptDirectory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Runs the JavaScript modules a module-index repository publishes, on this device, in a sandbox.
 *
 * A repository that is not a SpotiFLAC registry - `OreoMuncher45/bitchord-streaming-modules` is the
 * one Hush ships - does not publish extension packages at all. It publishes **modules**: one
 * JavaScript file per source under `modules/`, each a CommonJS script exporting two functions
 *
 * ```
 * module.exports.searchTracks(query, limit, context) -> { tracks, total }
 * module.exports.getTrackStreamUrl(id, quality, context) -> { streamUrl, track }
 * ```
 *
 * and nothing else. Until this class existed those sources were listed and could not be switched
 * on, because the only engine Hush had for a remote script was the SpotiFLAC runtime, whose
 * contract is a signed extension package, a session and a `DownloadByStrategy` request. A module is
 * none of those; it is a script that talks to its own public API and hands back a URL. So the honest
 * state of an Archive row was a disabled switch and a sentence explaining that Hush had no runtime
 * for it - which is a statement about Hush, printed on a row the user came to use.
 *
 * ## Why this runs at all
 *
 * The engine is **Rhino**, the JavaScript implementation already on this project's classpath for the
 * cipher work in `core`, at `Context.VERSION_ES6`, where it has a real `Promise` (including
 * `Promise.race`, which these modules lean on). Nothing new is bundled and no AAR is rebuilt. What
 * the module needs from a host is small and was measured against the real Archive module rather than
 * guessed at: `module`/`exports`, a `fetch` that answers with `ok`/`status`/`text()`/`json()`,
 * `setTimeout`, and a `console`. Given those, `searchTracks("Pure Hex", 5, {})` returned five real
 * archive.org FLAC rows in 3.8s and `getTrackStreamUrl` answered the module's own 4-byte `fLaC`
 * Range check with a live URL.
 *
 * ## What the sandbox is, and what it is not
 *
 * A module is third-party code. It runs with no Java access at all: a context created from
 * [SandboxFactory] uses `initSafeStandardObjects` **and** a [ClassShutter] that hides every class, so
 * `java.*`, `Packages`, `getClass` and the reflection they lead to are all gone - the same reason the
 * list is deleted defensively after init, because a build whose Rhino changes that default must not
 * silently hand a module the host's process. The only capability it is given is the fetch bridge,
 * which is a plain HTTP request built from the module's own arguments and nothing more, and a wall
 * clock: the factory's instruction observer throws out of a script that runs past its budget, so a
 * module cannot wedge the host with a loop the fetch timeouts would never interrupt.
 *
 * ## Timeouts, and the one contract that is deliberately not implemented
 *
 * These modules are written against BitChord's QuickJS sandbox, where `text()` and `json()` are
 * synchronous, `arrayBuffer()` does not exist, `headers.get()` always answers null, and
 * `URLSearchParams`/`Buffer` are absent - all documented in the modules themselves. Hush's fetch
 * keeps that shape exactly, which is why the same scripts run unmodified. `setTimeout` is the one
 * stub: it returns an id and never fires. It is used by these modules only to arm a guard that loses
 * a `Promise.race`, and the real bound has to be the transport's anyway - a JavaScript timer cannot
 * interrupt a thread that is blocked in a socket. The transport timeout is therefore what enforces
 * every deadline here, and a module's retry delay simply runs immediately, which makes it eager
 * rather than wrong.
 */
@Singleton
class SpotiFLACModuleHost @Inject constructor(
    httpClient: OkHttpClient,
    /**
     * Where fetched module scripts are cached.
     *
     * Injected as a folder rather than read from the app context here, which is what lets this class
     * be tested against a stubbed transport and a temporary directory: the sandbox is the part of
     * this feature that cannot be verified by reading it.
     */
    @ModuleScriptDirectory private val scriptDirectory: File,
) {
    companion object {
        private const val TAG = "SpotiFLACModules"

        /**
         * Longest script Hush will run.
         *
         * The Archive module is 14 KB and the Tidal one is the same order; the cap exists because the
         * address comes from a repository, and a "module" that is 40 MB of something is not a module.
         */
        private const val MAX_SCRIPT_BYTES = 2L * 1024L * 1024L

        /** How long a module's own `fetch` may take, end to end. */
        private const val FETCH_CALL_TIMEOUT_MS = 10_000L

        /** How long a module may spend reading one response body. */
        private const val FETCH_READ_TIMEOUT_MS = 12_000L

        /**
         * The most of a response a module is shown.
         *
         * A module reads JSON, or probes the first bytes of an audio file with a `Range` request -
         * and these CDNs do not always honour `Range`. Measured on the reporting device against
         * archive.org: the probe asks for four bytes and the node answered with the whole 30 MB FLAC,
         * which this host then read into a string and spent the module's entire budget doing. The cap
         * is what keeps that a bounded read: the module sees a body longer than the four bytes it
         * asked for and refuses the URL on its own check (`a length over 8 means Range was ignored`),
         * which is the honest outcome - and it is the same answer BitChord's own sandbox would give,
         * since its `text()` reads what the connection hands over too.
         */
        private const val MAX_FETCH_BODY_BYTES = 512L * 1024L

        /** How long a script fetch may take before the module is treated as unreachable. */
        private const val SCRIPT_CALL_TIMEOUT_MS = 20_000L

        /**
         * A whole file from a module, as opposed to one of its fetches.
         *
         * No call timeout: a module's stream URL is a lossless file and the only bound worth having
         * on it is per read. The fetch timeout above is what this used to share, which is why the
         * Tidal module's 24 MB download was abandoned at fifteen seconds on the reporting device
         * while the transfer was still running perfectly.
         */
        private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000L

        /**
         * How many times one module file may be fetched across a transport failure.
         *
         * A module's stream URL is somebody else's host, and those hosts cut a transfer for their
         * own reasons: measured on the reporting device, the gglos addon's own file host
         * (`tracks.monochrome.st`) reset the connection on a 43 MB FLAC after 34s, while the same
         * URL answered `206` to a `Range` request and delivered the whole file a moment later. One
         * reset was costing the track and the sweep, and the file that had already been written was
         * thrown away with it - so the retry resumes from the bytes on disk rather than starting
         * over, which is why it is cheap enough to make automatic.
         */
        private const val DOWNLOAD_ATTEMPTS = 2

        /**
         * Wall clock for one module call, checked by the instruction observer.
         *
         * Sized above what a module legitimately does, and above one transport timeout so a module's
         * own retry still fits: the Archive module retries a fast 5xx once. Measured against the real
         * Archive module: 3.8s for a search that expands four items, 1.2s for one stream URL - and
         * the budget is only ever reached by something wedged, which the observer then interrupts.
         */
        private const val SEARCH_BUDGET_MS = 30_000L
        private const val STREAM_BUDGET_MS = 30_000L

        /** Instructions between timeout checks; small enough to interrupt a tight loop promptly. */
        private const val INSTRUCTION_CHECK_INTERVAL = 20_000

        /**
         * How long a module that failed is left alone.
         *
         * A module is asked about every track nothing else could serve, so a module that is broken -
         * a dead host, a syntax error, an API that changed shape - would otherwise be re-run and
         * re-fetched for every track in a queue, each time costing the full search budget before the
         * track fails. Long enough to stop that, short enough that a transient outage heals in one
         * sitting rather than needing a restart.
         */
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L

        /** Longest reason carried out of a failed module call, for a row or a log line. */
        private const val MAX_REASON = 200

        /** How many console lines one module call may add to the diagnostic log. */
        private const val MAX_CONSOLE_LINES = 20

        /** Fetch lines one call may add to the diagnostic log, reset per call. */
        private const val MAX_FETCH_LOGS = 12

        private val json = Json

        /**
         * Escapes a string for a JavaScript literal.
         *
         * The call into a module is built as one evaluated expression, so the only thing standing
         * between a track title and module code is this. JSON's grammar is a subset of JavaScript's
         * string grammar, with one exception that still bites: U+2028 and U+2029 are line terminators
         * in JavaScript and legal unescaped inside a JSON string, and a title holding one would end
         * the literal early and run whatever followed it as code.
         */
        internal fun jsString(value: String): String =
            json.encodeToString(String.serializer(), value)
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
    }

    /** One row of a module's own search results, in the terms the rest of Hush matches on. */
    data class ModuleTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        val quality: String?,
        /**
         * What the source says the *file* is, when it says anything (`flac`, `dash`, `hls`).
         *
         * A server addon answers with both the row's quality and its delivery format, and the two
         * are not the same fact: measured against `gglos`, a Tidal-backed row advertises
         * `HI_RES_LOSSLESS` and delivers a 30-second DASH *manifest*, while the same catalogue's
         * proxied rows deliver a plain FLAC file. Hush downloads what it is given, so the format is
         * the only thing that can tell the two apart before the bytes arrive.
         */
        val format: String? = null,
    )

    /** What a module hands back for one of its own track ids. */
    data class ModuleStream(
        val url: String,
        val quality: String? = null,
        val mimeType: String? = null,
    )

    /**
     * One thread for every module, for the process's lifetime.
     *
     * Rhino confines a `Context` to the thread that made it, so the host cannot be shared across
     * dispatchers - and it must not be, because these calls block: the transport is synchronous by
     * design (see the class note on `text()`), so a shared pool would interleave two modules inside
     * one engine. The executor is never shut down; it holds one idle thread for an app that is about
     * to resolve the next track anyway.
     */
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, "spotiflac-module-host") }
            .asCoroutineDispatcher()

    /** The transport, with the module's own deadlines applied rather than the app's defaults. */
    private val client: OkHttpClient =
        httpClient
            .newBuilder()
            .callTimeout(FETCH_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(FETCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    /** The transport for a module's *file*, which is a different question from its fetches. */
    private val downloadClient: OkHttpClient =
        httpClient
            .newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    /**
     * Wall clock for one call, as a field so a test can hold a runaway script to a bound it can wait
     * for. The production values are the constants above, and nothing else writes these.
     */
    internal var searchBudgetMs: Long = SEARCH_BUDGET_MS
    internal var streamBudgetMs: Long = STREAM_BUDGET_MS

    /** Loaded engines by source id, each used from [dispatcher] alone. */
    private val loaded = ConcurrentHashMap<String, LoadedModule>()

    /** Sources whose last failure is still within [FAILURE_COOLDOWN_MS]. */
    private val failedUntil = ConcurrentHashMap<String, Long>()

    /** Modules Hush has already reported as broken, so the diagnostic log says it once. */
    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()

    /**
     * Asks a module to search, and returns its own rows.
     *
     * A failure is a `Result`, never an exception: a module is one candidate among several, and a
     * module that cannot answer is a candidate that did not answer.
     */
    suspend fun search(
        source: ExtensionSource,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Result<List<ModuleTrack>> = runModule(source, op = "search") { cx, scope ->
        val call =
            "__hushAwait(module.exports.searchTracks(${jsString(query)}, ${limit.coerceIn(1, MAX_SEARCH_LIMIT)}, {}), 0);"
        cx.evaluateString(scope, call, "search", 1, null)
        when (val slot = awaitSlot(cx, scope, searchBudgetMs)) {
            is Slot.Failed -> Result.failure(ModuleCallFailure(slot.reason))
            is Slot.Done -> {
                val tracks = readArray(slot.value as? Scriptable, "tracks")
                Result.success(tracks)
            }
        }
    }

    /**
     * Asks a module for the stream URL of one of its ids, and returns it with the module's own
     * quality claim.
     *
     * `streamUrl: null` is a *successful* call: it is a module saying it ran its own check - the
     * Archive module probes the first four bytes for `fLaC` - and the file is not what it claims.
     * Turning that into a failure would put a working module into the cooldown for answering
     * honestly.
     */
    suspend fun stream(
        source: ExtensionSource,
        trackId: String,
        quality: String = "LOSSLESS",
    ): Result<ModuleStream?> = runModule(source, op = "stream") { cx, scope ->
        val call =
            "__hushAwait(module.exports.getTrackStreamUrl(${jsString(trackId)}, ${jsString(quality)}, {}), 0);"
        cx.evaluateString(scope, call, "stream", 1, null)
        when (val slot = awaitSlot(cx, scope, streamBudgetMs)) {
            is Slot.Failed -> Result.failure(ModuleCallFailure(slot.reason))
            is Slot.Done -> {
                val answer = slot.value as? Scriptable
                val url = stringOf(answer, "streamUrl")?.takeIf { it.isNotBlank() }
                if (url == null) return@runModule Result.success(null)
                val track = answer?.let { property(it, "track") } as? Scriptable
                Result.success(
                    ModuleStream(
                        url = url,
                        quality = stringOf(track, "audioQuality")?.takeIf { it.isNotBlank() },
                        mimeType = stringOf(track, "mimeType")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    /**
     * Loads a module's script and reports what it exports, without asking it about a track.
     *
     * This is what the Audio Sources row's Test does for a module, and it is the same question the
     * native runtime's health probe answers for an extension: can this source serve anything at all.
     * It is deliberately only the load: the module's own search needs a query, and a made-up one
     * would report whether *that* query had results as if it were the source's health.
     */
    suspend fun probe(source: ExtensionSource): Result<String> =
        runModule(source, op = "probe") { _, scope ->
            val exports = exportsOf(scope)
            val searchable = functionOf(exports, "searchTracks")
            val streamable = functionOf(exports, "getTrackStreamUrl")
            if (!searchable || !streamable) {
                return@runModule Result.failure(
                    ModuleCallFailure(
                        "module exports ${if (searchable) "searchTracks" else "no searchTracks"}" +
                            " and ${if (streamable) "getTrackStreamUrl" else "no getTrackStreamUrl"}",
                    ),
                )
            }
            val name = stringOf(exports, "name")?.takeIf { it.isNotBlank() } ?: source.displayName
            val version = stringOf(exports, "version")?.takeIf { it.isNotBlank() }
            Result.success(if (version != null) "$name $version" else name)
        }

    /**
     * Fetches what a module pointed at into [destination], and reports how far it got.
     *
     * Lives here rather than in the bridge because this class is the one holding the transport, and
     * the bridge is the one that owns cache policy - the same split as the search and the stream URL,
     * which are the module's answers and not the app's.
     *
     * The bytes are streamed and the copy is abandoned on failure: a module's stream URL is a plain
     * HTTP file, and a half-written one left where the playback cache expects a track is exactly the
     * truncated copy the cache then has to detect and discard on the next play.
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Long> =
        withContext(dispatcher) {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            runCatching {
                val parsed = url.toHttpUrlOrNull() ?: error("module gave an unparseable stream URL")
                destination.parentFile?.mkdirs()
                var received = 0L
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        received =
                            downloadOnce(
                                url = parsed,
                                destination = destination,
                                resumeFrom = received,
                                job = job,
                                onProgress = onProgress,
                            )
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IOException) {
                        // The transport failed mid-file - a reset stream, a timeout, a host that
                        // closed early. Only a transport failure is retried: an HTTP answer, a
                        // manifest or an empty body is the source's verdict about the track and
                        // asking again would just repeat it.
                        if (attempt >= DOWNLOAD_ATTEMPTS) throw error
                        SpotiFLACDiag.log(
                            "module download attempt $attempt/$DOWNLOAD_ATTEMPTS interrupted " +
                                "after $received byte(s) (${error.message}); resuming",
                        )
                    }
                }
                if (received <= 0L) error("the module's stream URL answered with no bytes")
                received
            }.onFailure { runCatching { destination.delete() } }
        }

    /**
     * One attempt at a module's file, continuing from what an earlier attempt already wrote.
     *
     * The offset is sent as a `Range` request and the bytes are appended, so a transfer that is cut
     * off two thirds of the way through costs the last third rather than the whole file. A host that
     * ignores `Range` answers `200` with the file from the start, and the partial copy is replaced
     * rather than appended to - which is the only way the retry can be wrong, and it is handled by
     * the same flag that decides where writing starts.
     */
    private fun downloadOnce(
        url: okhttp3.HttpUrl,
        destination: File,
        resumeFrom: Long,
        job: kotlinx.coroutines.Job?,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Long {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", MODULE_USER_AGENT)
                .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
                .build()
        downloadClient.newCall(request).execute().use { answer ->
            if (!answer.isSuccessful) error("HTTP ${answer.code} from the module's stream URL")
            val body = answer.body ?: error("the module's stream URL answered with no body")
            val resumed = resumeFrom > 0L && answer.code == 206
            var received = if (resumed) resumeFrom else 0L
            val length = body.contentLength()
            val total = if (length > 0L) length + received else 0L
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (job?.isActive == false) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
            if (received <= 0L) error("the module's stream URL answered with no bytes")
            return received
        }
    }

    /** Drops a module's engine, so the next call re-reads and re-evaluates its script. */
    fun forget(sourceId: String) {
        loaded.remove(sourceId)?.dispose()
    }

    /** Starts a failed module's cooldown over - used when the user asks for it again by hand. */
    fun clearFailure(sourceId: String) {
        failedUntil.remove(sourceId)
        reportedFailures.remove(sourceId)
    }

    /**
     * The one path every operation takes: cooldown, engine, sandbox, error mapping.
     *
     * Written once because the three operations differ only in the expression they evaluate, and
     * because the parts that are easy to get subtly wrong - which thread, which context, what the
     * timeout is, what a failure means for the next track - must not be re-decided per operation.
     */
    private suspend fun <T> runModule(
        source: ExtensionSource,
        op: String,
        body: (JsContext, Scriptable) -> Result<T>,
    ): Result<T> {
        val entryPoint = source.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(ModuleCallFailure("module declares no script"))
        cooldownRemaining(source.id)?.let { remaining ->
            return Result.failure(
                ModuleCallFailure("module failed recently (${remaining / 1000}s left of its cooldown)"),
            )
        }
        return withContext(dispatcher) {
            val started = System.currentTimeMillis()
            consoleLines.set(0)
            fetchLogs.set(0)
            var computed: Any? = null
            val outcome: Result<T> =
                try {
                    val module = loaded[source.id] ?: load(source, entryPoint).also { loaded[source.id] = it }
                    val factory = module.factory
                    // Read once, as a job: the questions below are asked from inside a JavaScript
                    // call, which is not a suspension point - so the answer has to be a value that
                    // can be read from there rather than the context itself.
                    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
                    val active: () -> Boolean = { job?.isActive != false }
                    factory.deadlineMs = System.currentTimeMillis() + budgetFor(op)
                    factory.call(
                        ContextAction { cx ->
                            if (!active()) throw CancellationException()
                            computed = body(cx, module.scope)
                            if (!active()) throw CancellationException()
                            null
                        },
                    )
                    @Suppress("UNCHECKED_CAST")
                    (computed as? Result<T>) ?: Result.failure(ModuleCallFailure("module returned nothing"))
                } catch (error: Throwable) {
                    when (error) {
                        is CancellationException -> throw error
                        is ModuleCallFailure -> Result.failure(ModuleCallFailure(shorten(error.message)))
                        is ModuleTimeoutFailure ->
                            Result.failure(
                                ModuleCallFailure("module ran past its ${seconds(budgetFor(op))}s budget"),
                            )

                        else ->
                            Result.failure(
                                ModuleCallFailure(shorten("${error::class.simpleName}: ${error.message}")),
                            )
                    }
                }
            if (outcome.isFailure) noteFailure(source, outcome.exceptionOrNull())
            SpotiFLACDiag.log(
                "module $op source=${source.id} in ${System.currentTimeMillis() - started}ms " +
                    (outcome.exceptionOrNull()?.let { "failed: ${it.message}" } ?: "ok"),
            )
            outcome
        }
    }

    private fun budgetFor(op: String): Long = if (op == "stream") streamBudgetMs else searchBudgetMs

    /** Seconds, to one decimal, for a message a person reads while looking at a stuck row. */
    private fun seconds(ms: Long): String = "%.1f".format(ms / 1000.0)

    /** Remembers a failure so the next tracks in a queue do not each pay for it again. */
    private fun noteFailure(source: ExtensionSource, error: Throwable?) {
        failedUntil[source.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        if (reportedFailures.add(source.id)) {
            SpotiFLACDiag.log("module ${source.id} failed, cooling down: ${error?.message}")
        }
    }

    private fun cooldownRemaining(sourceId: String): Long? {
        val until = failedUntil[sourceId] ?: return null
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0) {
            failedUntil.remove(sourceId, until)
            reportedFailures.remove(sourceId)
            return null
        }
        return remaining
    }

    // ------------------------------------------------------------------ loading

    /**
     * One evaluated module: its engine, its globals, and the sandbox that bounds it.
     *
     * Nothing to release. A `Context` is entered and left around each call by the factory, so what a
     * forgotten module holds is its scope and the closures in it - which is what dropping the entry
     * from [loaded] is for, and why the next call re-reads and re-evaluates the script.
     */
    private class LoadedModule(
        val factory: SandboxFactory,
        val scope: Scriptable,
    ) {
        fun dispose() = Unit
    }

    private fun load(source: ExtensionSource, entryPoint: String): LoadedModule {
        val script = fetchScript(source, entryPoint).getOrElse { error ->
            throw ModuleCallFailure(shorten(error.message))
        }
        val factory = SandboxFactory()
        var scope: Scriptable? = null
        factory.call(
            ContextAction { cx ->
                val globals = cx.initSafeStandardObjects()
                // Belt and braces with the shutter in `makeContext`: `initSafeStandardObjects` is
                // what removes Java access, and a Rhino that changed that default would otherwise
                // hand a third-party script the host's class loader.
                HIDDEN_GLOBALS.forEach { ScriptableObject.deleteProperty(globals, it) }
                installBridges(cx, globals)
                cx.evaluateString(globals, script, "module.js", 1, null)
                scope = globals
                null
            },
        )
        val globals = scope ?: throw ModuleCallFailure("module scope was not created")
        return LoadedModule(factory, globals)
    }

    /**
     * The globals a module is given, and the shape of the response it reads.
     *
     * `fetch` resolves with an object literal rather than with the host's response, and the JSON is
     * parsed *here*, in JavaScript, on purpose: Rhino's Java-side JSON parser returns arrays with no
     * `Array.prototype`, so `array.slice(...)` - which these modules call - is missing on every one
     * of them. Measured, not assumed: with the Java-side parse the Archive module failed on
     * `Cannot find function slice` and reported an empty search, and with this parse it returned its
     * rows. The rest of the shape is BitChord's own contract, kept exactly (see the class note).
     */
    private fun installBridges(
        cx: JsContext,
        scope: Scriptable,
    ) {
        ScriptableObject.putProperty(
            scope,
            "__hushRequest",
            object : BaseFunction() {
                override fun call(
                    cx: JsContext,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any {
                    val url = args.getOrNull(0)?.let { JsContext.toString(it) }.orEmpty()
                    val options = args.getOrNull(1) as? Scriptable
                    return performRequest(cx, scope, url, options)
                }
            },
        )
        ScriptableObject.putProperty(
            scope,
            "__hushLog",
            object : BaseFunction() {
                override fun call(
                    cx: JsContext,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any {
                    if (consoleLines.get() < MAX_CONSOLE_LINES) {
                        consoleLines.incrementAndGet()
                        SpotiFLACDiag.log(
                            "module console: " +
                                args.joinToString(" ") { JsContext.toString(it) }.take(300),
                        )
                    }
                    return Undefined.instance
                }
            },
        )
        cx.evaluateString(scope, PREAMBLE, "hush-host", 1, null)
    }

    private val consoleLines = java.util.concurrent.atomic.AtomicInteger(0)

    /** Bounded per-call fetch log, so a module that fetches in a loop cannot flood the diag file. */
    private val fetchLogs = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * One HTTP request for a module, synchronously.
     *
     * Synchronous because the modules are written for it: they call `resp.text()` and read the
     * answer, on the path where BitChord's own sandbox is synchronous too. The method, URL, headers
     * and body come from the module and are all the module can influence - there is no cookie jar
     * shared with the app, and no credentials of Hush's are attached.
     */
    private fun performRequest(
        cx: JsContext,
        scope: Scriptable,
        url: String,
        options: Scriptable?,
    ): Any {
        val parsed = url.toHttpUrlOrNull()
            ?: throw JsContext.reportRuntimeError("module asked for an unparseable URL")
        val method = (options?.let { stringOf(it, "method") } ?: "GET").uppercase()
        val builder = Request.Builder().url(parsed)
        (options?.let { property(it, "headers") } as? Scriptable)?.let { headers ->
            for (id in headers.ids) {
                val name = JsContext.toString(id)
                val value = stringOf(headers, name) ?: continue
                runCatching { builder.header(name, value) }
            }
        }
        val body = options?.let { stringOf(it, "body") }
        builder.method(
            method,
            when {
                body != null && method != "GET" && method != "HEAD" ->
                    body.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                else -> null
            },
        )
        val request = builder.build()
        val started = System.currentTimeMillis()
        val response =
            runCatching { client.newCall(request).execute() }
                .getOrElse { error ->
                    throw JsContext.reportRuntimeError("module fetch failed: ${error.message}")
                }
        response.use { answer ->
            // Declared length first, so a Range-ignoring CDN costs no reading at all: the answer is
            // refused on its own header rather than after half a megabyte of a lossless file.
            val declared = answer.body?.contentLength() ?: -1L
            if (declared > MAX_FETCH_BODY_BYTES) {
                throw JsContext.reportRuntimeError(
                    "module fetch answered with $declared bytes; a fetched response is capped at " +
                        "$MAX_FETCH_BODY_BYTES (a media URL that ignores Range replies this way)",
                )
            }
            // Bounded on purpose: see MAX_FETCH_BODY_BYTES. `peekBody` holds the original response
            // unread, so nothing downstream depends on how much of it was looked at.
            val text = runCatching { answer.peekBody(MAX_FETCH_BODY_BYTES).string() }.getOrDefault("")
            if (fetchLogs.incrementAndGet() <= MAX_FETCH_LOGS) {
                SpotiFLACDiag.log(
                    "module fetch ${request.method} ${parsed.host}${parsed.encodedPath.take(80)} " +
                        "-> ${answer.code} ${text.length}B in ${System.currentTimeMillis() - started}ms " +
                        "range=${request.header("Range") ?: "-"}",
                )
            }
            return JsContext.javaToJS(SandboxResponse(answer.code, text), scope)
        }
    }

    /**
     * The module's script, from the cache when it is there and from its repository otherwise.
     *
     * The cache key carries the repository's own version as well as the address, so a module index
     * that publishes a new build is picked up on the next call instead of being served the script it
     * replaced - while the ordinary case, the same URL and version asked about once per process,
     * costs one file read.
     */
    private fun fetchScript(source: ExtensionSource, entryPoint: String): Result<String> {
        if (!scriptDirectory.isDirectory) scriptDirectory.mkdirs()
        val directory = scriptDirectory
        val cacheKey = sha1("$entryPoint|${source.version}")
        val cached = File(directory, "$cacheKey.js")
        if (cached.isFile && cached.length() > 0L) {
            runCatching { cached.readText() }.getOrNull()?.let { return Result.success(it) }
        }
        return runCatching {
            val client = client.newBuilder().callTimeout(SCRIPT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
            client.newCall(Request.Builder().url(entryPoint).header("User-Agent", MODULE_USER_AGENT).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for the module script")
                    val length = response.body?.contentLength() ?: -1L
                    if (length > MAX_SCRIPT_BYTES) error("module script is larger than the 2 MB cap")
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) error("the module script is empty")
                    if (text.length.toLong() > MAX_SCRIPT_BYTES) error("module script is larger than the 2 MB cap")
                    // Written through a temporary name: a reader must never see a half-written
                    // script and evaluate the first half of a module as if it were all of it.
                    val temporary = File(directory, "$cacheKey.part")
                    runCatching {
                        temporary.writeText(text)
                        if (!temporary.renameTo(cached)) {
                            cached.writeText(text)
                            temporary.delete()
                        }
                    }
                    text
                }
        }.recoverCatching { error -> throw ModuleCallFailure(shorten(error.message)) }
    }

    // ------------------------------------------------------------------ the engine

    /**
     * A context factory that cannot be escaped and cannot run forever.
     *
     * The deadline is mutable because it is per call: a search and a stream URL get different
     * budgets, and the observer is the only thing that can interrupt a script already inside a loop.
     */
    private class SandboxFactory : ContextFactory() {
        @Volatile
        var deadlineMs: Long = Long.MAX_VALUE

        override fun makeContext(): JsContext =
            super.makeContext().apply {
                languageVersion = JsContext.VERSION_ES6
                // The interpreter, deliberately: generated bytecode is another code path inside the
                // host for a script that runs a handful of times per track, and the interpreter is
                // the one the deadline observer is written against.
                optimizationLevel = -1
                setClassShutter(ClassShutter { false })
                instructionObserverThreshold = INSTRUCTION_CHECK_INTERVAL
            }

        override fun observeInstructionCount(
            cx: JsContext,
            instructionCount: Int,
        ) {
            if (System.currentTimeMillis() > deadlineMs) throw ModuleTimeoutFailure("module timed out")
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** What a promise settled to, or that it had not settled when the budget ran out. */
    private sealed interface Slot {
        data class Done(val value: Any?) : Slot

        data class Failed(val reason: String) : Slot
    }

    /**
     * Waits for slot [slot] to settle, by draining the engine's microtask queue.
     *
     * Rhino runs promise callbacks at the end of each top-level evaluation, so one evaluation of a
     * no-op statement is what advances a chain - and because this host's `fetch` is synchronous,
     * there is never anything to wait for beyond that: by the time an evaluation returns, every
     * response it needed has already arrived and only the callbacks are left. The loop therefore
     * turns rather than sleeps, and the budget is what ends it.
     */
    private fun awaitSlot(
        cx: JsContext,
        scope: Scriptable,
        budgetMs: Long,
    ): Slot {
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            val state =
                cx.evaluateString(scope, "__hushSlotState(0)", "module-await", 1, null)
                    ?.let { JsContext.toString(it) }
                    .orEmpty()
            when (state) {
                "done" -> return Slot.Done(cx.evaluateString(scope, "__hushSlotValue(0)", "module-await", 1, null))
                "failed" ->
                    return Slot.Failed(
                        cx.evaluateString(scope, "__hushSlotError(0)", "module-await", 1, null)
                            ?.let { JsContext.toString(it) }
                            .orEmpty()
                            .ifBlank { "module rejected" },
                    )
            }
            if (System.currentTimeMillis() > deadline) {
                throw ModuleTimeoutFailure("module call did not settle within ${budgetMs / 1000}s")
            }
            cx.evaluateString(scope, "void 0;", "module-pump", 1, null)
        }
    }

    private fun exportsOf(scope: Scriptable): Scriptable {
        val module = ScriptableObject.getProperty(scope, "module") as? Scriptable
            ?: throw ModuleCallFailure("module did not declare itself")
        return ScriptableObject.getProperty(module, "exports") as? Scriptable
            ?: throw ModuleCallFailure("module exported nothing")
    }

    private fun functionOf(exports: Scriptable, name: String): Boolean =
        ScriptableObject.getProperty(exports, name) is org.mozilla.javascript.Function

    private fun readArray(value: Scriptable?, propertyName: String): List<ModuleTrack> {
        val array = (value?.let { property(it, propertyName) } as? NativeArray) ?: return emptyList()
        val rows = ArrayList<ModuleTrack>(array.size)
        for (index in 0 until array.size) {
            val row = array.get(index, array) as? Scriptable ?: continue
            val id = stringOf(row, "id")?.takeIf { it.isNotBlank() } ?: continue
            val title = stringOf(row, "title")?.takeIf { it.isNotBlank() } ?: continue
            rows.add(
                ModuleTrack(
                    id = id,
                    title = title,
                    artist = stringOf(row, "artist").orEmpty(),
                    album = stringOf(row, "album")?.takeIf { it.isNotBlank() },
                    // The modules report seconds; Hush matches on milliseconds.
                    durationMs = (numberOf(row, "duration") * 1000.0).toLong().coerceAtLeast(0L),
                    quality = stringOf(row, "audioQuality")?.takeIf { it.isNotBlank() },
                ),
            )
        }
        return rows
    }

    private fun property(scope: Scriptable, name: String): Any? =
        ScriptableObject.getProperty(scope, name).takeIf { it != Scriptable.NOT_FOUND }

    /**
     * A string property, or null.
     *
     * Only primitives are converted: `Context.toString` on a scriptable asks it for a default value,
     * and a module's object literal - parsed from JSON, so with the objects the engine made - is not
     * a thing to stringify into a field.
     */
    private fun stringOf(scope: Scriptable?, name: String): String? {
        val value = scope?.let { property(it, name) } ?: return null
        return when {
            value === Undefined.instance || value === Scriptable.NOT_FOUND -> null
            value is CharSequence -> value.toString()
            value is Number -> value.toString()
            value is Boolean -> value.toString()
            else -> null
        }
    }

    private fun numberOf(scope: Scriptable?, name: String): Double {
        val value = scope?.let { property(it, name) } ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is CharSequence -> value.toString().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun shorten(reason: String?): String {
        val text = reason.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (text.length <= MAX_REASON) text.ifBlank { "module failed" } else text.take(MAX_REASON - 1) + "…"
    }

    private fun sha1(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A module that could not be run, with the reason as its message. */
    private class ModuleCallFailure(message: String) : Exception(message)

    /** A script that ran past its budget, thrown out of the instruction observer. */
    private class ModuleTimeoutFailure(message: String) : RuntimeException(message)

    /** What the module never sees: the host's own name for the HTTP requests it makes for it. */
    /**
     * See [AddonClientIdentity]: some module hosts gate on the product token, and the module's own
     * API calls are gated exactly like its manifest was.
     */
    private val MODULE_USER_AGENT = AddonClientIdentity.userAgent
}

/**
 * The JavaScript a module is given before it runs.
 *
 * Kept as one literal rather than assembled per call so the whole surface a module can see is
 * readable in one place: the CommonJS globals it exports through, a `fetch` that resolves with the
 * response shape it was written against, a `setTimeout` with no timer behind it, a bounded `console`,
 * and the two helpers this host uses to wait for a promise without blocking the engine.
 */
private const val PREAMBLE = """
var module = { exports: {} };
var exports = module.exports;
var __hushSlots = [];
function __hushSlotState(slot) { return __hushSlots[slot] ? __hushSlots[slot].state : 'pending'; }
function __hushSlotValue(slot) { return __hushSlots[slot] ? __hushSlots[slot].value : null; }
function __hushSlotError(slot) { return __hushSlots[slot] ? __hushSlots[slot].error : ''; }
function __hushAwait(promise, slot) {
  __hushSlots[slot] = { state: 'pending' };
  promise.then(
    function (value) { __hushSlots[slot] = { state: 'done', value: value }; },
    function (error) {
      __hushSlots[slot] = { state: 'failed', error: String((error && error.message) || error || 'failed') };
    }
  );
}
var fetch = function (url, options) {
  var call;
  try { call = Promise.resolve(__hushRequest(url, options)); }
  catch (error) { return Promise.reject(error); }
  return call.then(function (response) {
    return {
      ok: response.ok,
      status: response.status,
      statusText: '',
      headers: { get: function () { return null; } },
      text: function () { return response.text(); },
      json: function () { return JSON.parse(response.text()); }
    };
  });
};
var setTimeout = function () { return 0; };
var clearTimeout = function () { };
var console = { log: __hushLog, info: __hushLog, warn: __hushLog, error: __hushLog, debug: __hushLog };
"""

/**
 * Globals that must not exist in a module's scope, deleted rather than assumed absent.
 *
 * Every one of these is a route out of the sandbox on some Rhino version: `Packages`/`java`/
 * `JavaAdapter` reach the host's classes, `getClass` reaches reflection from any object, and `load`,
 * `readFile` and `runCommand` are the shell's own doors into the filesystem and the process.
 */
private val HIDDEN_GLOBALS =
    listOf(
        "getClass",
        "load",
        "loadClass",
        "importPackage",
        "importClass",
        "JavaAdapter",
        "JavaImporter",
        "Packages",
        "java",
        "javax",
        "org",
        "com",
        "edu",
        "net",
        "spawn",
        "sync",
        "quit",
        "readFile",
        "readUrl",
        "runCommand",
        "environment",
    )

/** Rows a module search is asked for when a caller does not name its own limit. */
internal const val DEFAULT_SEARCH_LIMIT = 20

/** Rows a module search may be asked for, capped so one search cannot become the whole queue. */
internal const val MAX_SEARCH_LIMIT = 50

/**
 * The answer a module gives a `fetch`, in the shape BitChord's sandbox answers in.
 *
 * `text` is a function and `json` is not implemented here on purpose, both matching the contract the
 * modules are written against; the JavaScript bridge above is what turns this into the object the
 * script actually reads, and that is where `json` is answered by the engine's own parser.
 */
private class SandboxResponse(
    private val status: Int,
    private val body: String,
) : ScriptableObject() {
    override fun getClassName(): String = "Response"

    override fun get(
        name: String,
        start: Scriptable,
    ): Any? =
        when (name) {
            "ok" -> status in 200..299
            "status" -> status
            "statusText" -> ""
            "text" ->
                object : BaseFunction() {
                    override fun call(
                        cx: JsContext,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>,
                    ): Any = body
                }

            "headers" ->
                object : ScriptableObject() {
                    override fun getClassName(): String = "Headers"

                    override fun get(
                        name: String,
                        start: Scriptable,
                    ): Any? =
                        when (name) {
                            "get" ->
                                object : BaseFunction() {
                                    override fun call(
                                        cx: JsContext,
                                        scope: Scriptable,
                                        thisObj: Scriptable,
                                        args: Array<Any?>,
                                    ): Any = Undefined.instance
                                }

                            else -> super.get(name, start)
                        }
                }

            else -> super.get(name, start)
        }

    override fun has(
        name: String,
        start: Scriptable,
    ): Boolean =
        name in setOf("ok", "status", "statusText", "text", "headers") || super.has(name, start)
}
