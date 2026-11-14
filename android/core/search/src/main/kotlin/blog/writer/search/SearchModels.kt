package blog.writer.search

import java.time.Instant
import java.time.LocalDate

enum class Freshness { ANY, DAY, WEEK, MONTH, YEAR }

enum class SearchSourceKind { BUILTIN, CUSTOM_HTTP, MCP, MODEL_BUILTIN }

enum class SearchMode { AUTO, SINGLE, MERGE }

data class SearchRequest(
    val query: String,
    val maxResults: Int = 5,
    val language: String? = null,
    val region: String? = null,
    val freshness: Freshness = Freshness.ANY,
    val since: LocalDate? = null,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val wantFullContent: Boolean = false,
)

data class SearchOrigin(
    val profileId: String,
    val profileName: String,
    val adapter: String,
    val originalRank: Int,
    val alsoFrom: List<String> = emptyList(),
)

data class SearchResult(
    val rank: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val content: String?,
    val publishedAt: Instant?,
    val siteName: String?,
    val language: String?,
    val score: Double?,
    val origin: SearchOrigin,
    val incompleteExcerpt: Boolean = false,
)

data class SearchResponse(
    val results: List<SearchResult>,
    val ignoredOptions: Set<String>,
    val warnings: List<String>,
    val latencyMs: Long,
    val detail: String? = null,
    val rawBody: String? = null,
)

data class SearchCapabilities(
    val freshness: Boolean = false,
    val since: Boolean = false,
    val includeDomains: Boolean = false,
    val excludeDomains: Boolean = false,
    val language: Boolean = false,
    val region: Boolean = false,
    val fullContent: Boolean = false,
    val maxResultsCap: Int = 10,
)

interface SearchAdapter {
    val id: String
    val capabilities: SearchCapabilities
    suspend fun search(request: SearchRequest): SearchResponse
}

data class RawHit(
    val title: String?,
    val url: String?,
    val snippet: String?,
    val content: String?,
    val publishedRaw: String?,
    val siteName: String?,
    val language: String?,
    val score: Double?,
)

data class SearchProfile(
    val id: String,
    val name: String,
    val kind: SearchSourceKind,
    val host: String,
    val enabled: Boolean,
    val priority: Int,
    val needsInternationalNetwork: Boolean,
    val lastTestFailed: Boolean,
    val dailyLimit: Int?,
    val dailyUsed: Int,
    val unitPrice: Double?,
    val adapter: SearchAdapter,
)

data class FactCheckSearchSettings(
    val mode: SearchMode = SearchMode.AUTO,
    val singleProfileId: String? = null,
    val mergeCount: Int = 2,
    val resultsPerClaim: Int = 5,
    val maxClaims: Int = 10,
    val preferredDomains: List<String> = emptyList(),
    val costCap: Double? = null,
)

data class SearchAttempt(
    val profileId: String,
    val profileName: String,
    val failure: String?,
)

data class RoutedSearch(
    val results: List<SearchResult>,
    val attempts: List<SearchAttempt>,
    val failedAll: Boolean,
    val warnings: List<String>,
    val ignoredOptions: Set<String>,
    val estimatedCost: Double,
)

class SearchException(message: String, val status: Int? = null) : RuntimeException(message)
