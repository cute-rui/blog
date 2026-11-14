package blog.writer.search

import java.time.Instant

fun interface ReachabilityProbe {
    suspend fun reachable(host: String): Boolean
}

class FailureMemory {
    private val consecutive = mutableMapOf<String, Int>()
    private val openUntil = mutableMapOf<String, Instant>()

    fun isOpen(id: String, now: Instant): Boolean = openUntil[id]?.let { now.isBefore(it) } == true

    fun recordFailure(id: String, now: Instant) {
        val count = (consecutive[id] ?: 0) + 1
        consecutive[id] = count
        if (count >= 3) openUntil[id] = now.plusSeconds(600)
    }

    fun recordSuccess(id: String) {
        consecutive[id] = 0
    }
}

class SearchRouter(
    private val probe: ReachabilityProbe = ReachabilityProbe { true },
    private val memory: FailureMemory = FailureMemory(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun search(
        query: String,
        profiles: List<SearchProfile>,
        settings: FactCheckSearchSettings,
        spent: Double = 0.0,
    ): RoutedSearch {
        val request = SearchRequest(
            query = query,
            maxResults = settings.resultsPerClaim,
            includeDomains = emptyList(),
        )
        val candidates = orderedCandidates(profiles, settings)
        if (candidates.isEmpty()) {
            return RoutedSearch(emptyList(), emptyList(), failedAll = true, emptyList(), emptySet(), spent)
        }
        if (settings.costCap != null && spent >= settings.costCap) {
            return RoutedSearch(
                emptyList(),
                emptyList(),
                failedAll = true,
                warnings = listOf("已达费用上限"),
                ignoredOptions = emptySet(),
                estimatedCost = spent,
            )
        }
        return when (settings.mode) {
            SearchMode.AUTO, SearchMode.SINGLE -> walk(candidates, request, settings, spent)
            SearchMode.MERGE -> merge(candidates.take(settings.mergeCount.coerceAtLeast(1)), request, settings, spent)
        }
    }

    fun orderedCandidates(profiles: List<SearchProfile>, settings: FactCheckSearchSettings): List<SearchProfile> {
        val enabled = profiles.filter { profile ->
            profile.enabled && !profile.lastTestFailed && !overDaily(profile) && !memory.isOpen(profile.id, now())
        }
        val ordered = enabled.sortedBy { it.priority }
        return when (settings.mode) {
            SearchMode.SINGLE -> ordered.filter { it.id == settings.singleProfileId }
            SearchMode.AUTO, SearchMode.MERGE -> ordered
        }
    }

    private suspend fun walk(
        candidates: List<SearchProfile>,
        request: SearchRequest,
        settings: FactCheckSearchSettings,
        spent: Double,
    ): RoutedSearch {
        val attempts = mutableListOf<SearchAttempt>()
        var cost = spent
        val warnings = mutableListOf<String>()
        val ignored = mutableSetOf<String>()
        for (profile in candidates) {
            if (settings.costCap != null && cost + (profile.unitPrice ?: 0.0) > settings.costCap) {
                warnings += "已达费用上限"
                break
            }
            if (!reachable(profile)) {
                attempts += SearchAttempt(profile.id, profile.name, "当前不可达")
                continue
            }
            val outcome = runOne(profile, request, settings)
            cost += profile.unitPrice ?: 0.0
            ignored += outcome.ignored
            warnings += outcome.warnings
            if (outcome.failure == null && outcome.results.isNotEmpty()) {
                memory.recordSuccess(profile.id)
                attempts += SearchAttempt(profile.id, profile.name, null)
                return RoutedSearch(outcome.results, attempts, false, warnings, ignored, cost)
            }
            memory.recordFailure(profile.id, now())
            attempts += SearchAttempt(profile.id, profile.name, outcome.failure ?: "0 条结果")
        }
        return RoutedSearch(emptyList(), attempts, true, warnings, ignored, cost)
    }

    private suspend fun merge(
        candidates: List<SearchProfile>,
        request: SearchRequest,
        settings: FactCheckSearchSettings,
        spent: Double,
    ): RoutedSearch {
        val pieces = mutableListOf<SearchResult>()
        val attempts = mutableListOf<SearchAttempt>()
        var cost = spent
        val warnings = mutableListOf<String>()
        val ignored = mutableSetOf<String>()
        for (profile in candidates) {
            if (!reachable(profile)) {
                attempts += SearchAttempt(profile.id, profile.name, "当前不可达")
                continue
            }
            val outcome = runOne(profile, request, settings)
            cost += profile.unitPrice ?: 0.0
            ignored += outcome.ignored
            warnings += outcome.warnings
            if (outcome.failure == null && outcome.results.isNotEmpty()) {
                memory.recordSuccess(profile.id)
                pieces += outcome.results
                attempts += SearchAttempt(profile.id, profile.name, null)
            } else {
                memory.recordFailure(profile.id, now())
                attempts += SearchAttempt(profile.id, profile.name, outcome.failure ?: "0 条结果")
            }
        }
        val merged = SearchNormalizer.normalize(
            hits = pieces.map { result ->
                RawHit(result.title, result.url, result.snippet, result.content, result.publishedAt?.toString(), result.siteName, result.language, result.score)
            },
            request = request,
            capabilities = SearchCapabilities(freshness = true, includeDomains = true, fullContent = true),
            origin = { rank -> pieces.getOrNull(rank - 1)?.origin ?: SearchOrigin("", "", "merge", rank) },
            now = now(),
            preferredDomains = settings.preferredDomains,
        )
        return RoutedSearch(merged.results, attempts, merged.results.isEmpty(), warnings + merged.warnings, ignored, cost)
    }

    private suspend fun reachable(profile: SearchProfile): Boolean {
        if (!profile.needsInternationalNetwork) return true
        return probe.reachable(profile.host)
    }

    private suspend fun runOne(
        profile: SearchProfile,
        request: SearchRequest,
        settings: FactCheckSearchSettings,
    ): OneShot {
        return try {
            val response = profile.adapter.search(request.copy(maxResults = settings.resultsPerClaim))
            val normalized = if (settings.preferredDomains.isEmpty()) {
                response
            } else {
                SearchNormalizer.normalize(
                    response.results.map { RawHit(it.title, it.url, it.snippet, it.content, it.publishedAt?.toString(), it.siteName, it.language, it.score) },
                    request,
                    SearchCapabilities(freshness = true, includeDomains = true, fullContent = true, language = true, region = true),
                    origin = { rank -> response.results.getOrNull(rank - 1)?.origin ?: SearchOrigin(profile.id, profile.name, profile.adapter.id, rank) },
                    now = now(),
                    preferredDomains = settings.preferredDomains,
                )
            }
            OneShot(normalized.results, null, response.ignoredOptions, response.warnings)
        } catch (error: SearchException) {
            OneShot(emptyList(), error.message ?: "检索失败", emptySet(), emptyList())
        }
    }

    private fun overDaily(profile: SearchProfile): Boolean =
        profile.dailyLimit != null && profile.dailyUsed >= profile.dailyLimit

    private data class OneShot(
        val results: List<SearchResult>,
        val failure: String?,
        val ignored: Set<String>,
        val warnings: List<String>,
    )
}
