package app.hush.music.eq

import app.hush.music.eq.data.FilterType
import app.hush.music.eq.data.ParametricEQBand
import app.hush.music.eq.data.SavedEQProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.round
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object AutoEqImporter {
    private const val INDEX_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/index.txt"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    data class HeadphoneModel(
        val name: String,
        val path: String,
    )

    suspend fun fetchAvailableModels(): Result<List<HeadphoneModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(INDEX_URL)
                .get()
                .header("User-Agent", "Hush")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw Exception("Failed to fetch AutoEq index: HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw Exception("Empty AutoEq index response")

                body.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val parts = line.split("|")
                        if (parts.size < 2) return@mapNotNull null
                        val path = parts[0].trim()
                        val name = parts.getOrNull(1)?.trim() ?: path
                        if (path.isBlank()) return@mapNotNull null
                        HeadphoneModel(name = name, path = path)
                    }
            }
        }
    }

    suspend fun fetchFrequencyResponse(path: String): Result<List<Triple<Double, Double, Double>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val csvUrl = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/$path/$path.csv"
                val request = Request.Builder()
                    .url(csvUrl)
                    .get()
                    .header("User-Agent", "Hush")
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) throw Exception("Failed to fetch CSV: HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw Exception("Empty CSV response")

                    body.lines()
                        .drop(1)
                        .mapNotNull { line ->
                            val parts = line.split(",")
                            if (parts.size < 3) return@mapNotNull null
                            val freq = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                            val left = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                            val right = parts[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                            Triple(freq, left, right)
                        }
                }
            }
        }

    suspend fun generateEQProfile(
        name: String,
        path: String,
        maxBands: Int = 10,
        preampTarget: Double = 0.0,
    ): Result<SavedEQProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val frData = fetchFrequencyResponse(path).getOrThrow()
            if (frData.isEmpty()) throw Exception("No frequency response data found")

            val averaged = frData.map { (freq, left, right) ->
                freq to (left + right) / 2.0
            }

            val gainsAdjusted = adjustToTarget(averaged, preampTarget)
            val bands = extractParametricBands(gainsAdjusted, maxBands)

            SavedEQProfile(
                id = UUID.randomUUID().toString(),
                name = "AutoEQ: $name",
                bands = bands,
                preamp = -(gainsAdjusted.maxOfOrNull { it.second }?.coerceAtMost(0.0) ?: 0.0),
                isCustom = false,
            )
        }
    }

    private fun adjustToTarget(
        data: List<Pair<Double, Double>>,
        targetPreamp: Double,
    ): List<Pair<Double, Double>> {
        if (data.isEmpty()) return data

        val bassAvg = data
            .filter { it.first <= 200.0 }
            .map { it.second }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: data.first().second

        return data.map { (freq, gain) ->
            freq to (gain - bassAvg + targetPreamp)
        }
    }

    private fun extractParametricBands(
        data: List<Pair<Double, Double>>,
        maxBands: Int,
    ): List<ParametricEQBand> {
        val standardFrequencies = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        val bandMap = mutableMapOf<Double, MutableList<Double>>()
        for ((freq, gain) in data) {
            val nearest = standardFrequencies.minByOrNull { kotlin.math.abs(it - freq) } ?: freq
            bandMap.getOrPut(nearest) { mutableListOf() }.add(gain)
        }

        return standardFrequencies
            .take(maxBands)
            .map { freq ->
                val gains = bandMap[freq]
                val avgGain = gains?.let { it.sum() / it.size }?.coerceIn(-12.0, 12.0) ?: 0.0
                ParametricEQBand(
                    frequency = freq,
                    gain = kotlin.math.round(avgGain * 10.0) / 10.0,
                    q = 1.41,
                    filterType = FilterType.PK,
                    enabled = Math.abs(avgGain) > 0.5,
                )
            }
    }

    suspend fun searchModels(
        query: String,
    ): Result<List<HeadphoneModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val allModels = fetchAvailableModels().getOrThrow()
            val normalizedQuery = query.lowercase(Locale.US).trim()
            allModels.filter { model ->
                model.name.lowercase(Locale.US).contains(normalizedQuery) ||
                    model.path.lowercase(Locale.US).contains(normalizedQuery)
            }.take(50)
        }
    }
}
