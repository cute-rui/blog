package blog.writer.search
import blog.writer.copy.Copy

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object SearchNormalizer {
    const val SNIPPET_LIMIT = 500
    const val CONTENT_LIMIT = 4_000

    fun normalize(
        hits: List<RawHit>,
        request: SearchRequest,
        capabilities: SearchCapabilities,
        origin: (rank: Int) -> SearchOrigin,
        now: Instant,
        preferredDomains: List<String> = emptyList(),
        latencyMs: Long = 0,
    ): SearchResponse {
        val ignored = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        if (request.language != null && !capabilities.language) ignored += "language"
        if (request.region != null && !capabilities.region) ignored += "region"
        if (request.wantFullContent && !capabilities.fullContent) {
            warnings += Copy["warning_no_body"]
        }
        val prepared = hits.mapIndexedNotNull { index, hit ->
            val url = canonicalize(hit.url) ?: return@mapIndexedNotNull null
            val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
            val title = stripHtml(hit.title.orEmpty()).ifBlank { host.ifBlank { url } }
            val published = parsePublished(hit.publishedRaw, now)
            SearchResult(
                rank = index + 1,
                title = title.take(SNIPPET_LIMIT),
                url = url,
                snippet = limit(stripHtml(hit.snippet.orEmpty()), SNIPPET_LIMIT),
                content = hit.content?.let { limit(stripHtml(it), CONTENT_LIMIT) }?.ifBlank { null },
                publishedAt = published,
                siteName = hit.siteName?.takeIf { it.isNotBlank() } ?: host.ifBlank { null },
                language = hit.language,
                score = hit.score,
                origin = origin(index + 1),
            )
        }
        val filtered = prepared.filter { result ->
            val host = hostOf(result.url)
            val includeOk = request.includeDomains.isEmpty() || request.includeDomains.any { domain -> matchesDomain(host, domain) }
            val excludeOk = request.excludeDomains.none { domain -> matchesDomain(host, domain) }
            val freshnessOk = withinFreshness(result.publishedAt, request.freshness, now)
            val sinceOk = request.since == null || result.publishedAt == null ||
                !result.publishedAt.isBefore(request.since.atStartOfDay(ZoneOffset.UTC).toInstant())
            includeOk && excludeOk && freshnessOk && sinceOk
        }
        if (!capabilities.freshness && request.freshness != Freshness.ANY) {
            warnings += Copy["warning_freshness"]
        }
        if (!capabilities.includeDomains && request.includeDomains.isNotEmpty()) {
            warnings += Copy["warning_domains"]
        }
        val deduped = dedupe(filtered)
        val ordered = floatPreferred(deduped, preferredDomains).mapIndexed { index, result ->
            result.copy(rank = index + 1)
        }
        return SearchResponse(ordered, ignored, warnings, latencyMs)
    }

    fun mergeSources(groups: List<List<SearchResult>>, preferredDomains: List<String>): List<SearchResult> {
        val merged = dedupe(groups.flatten())
        return floatPreferred(merged, preferredDomains).mapIndexed { index, result ->
            result.copy(rank = index + 1)
        }
    }

    fun canonicalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        val query = uri.rawQuery
            ?.split("&")
            ?.filter { part ->
                val key = part.substringBefore("=").lowercase()
                key.isNotEmpty() && !key.startsWith("utm_") && key != "fbclid" && key != "gclid"
            }
            ?.joinToString("&")
            ?.ifBlank { null }
        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return buildString {
            append(scheme).append("://").append(uri.host.lowercase()).append(port).append(path)
            if (query != null) append('?').append(query)
        }
    }

    fun stripHtml(value: String): String {
        val noTags = Regex("<[^>]+>").replace(value, " ")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun parsePublished(raw: String?, now: Instant): Instant? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant() }
            .getOrNull()?.let { return it }
        if (text == "昨天" || text.equals("yesterday", ignoreCase = true)) return now.minus(1, ChronoUnit.DAYS)
        val zh = Regex("(\\d+)\\s*天前").find(text)
        if (zh != null) return now.minus(zh.groupValues[1].toLong(), ChronoUnit.DAYS)
        val en = Regex("(\\d+)\\s*days?\\s*ago", RegexOption.IGNORE_CASE).find(text)
        if (en != null) return now.minus(en.groupValues[1].toLong(), ChronoUnit.DAYS)
        return null
    }

    private fun limit(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max)

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.lowercase().orEmpty()

    private fun matchesDomain(host: String, domain: String): Boolean {
        val needle = domain.removePrefix("https://").removePrefix("http://").substringBefore("/").removePrefix("www.").lowercase()
        return host == needle || host.endsWith(".$needle")
    }

    private fun withinFreshness(published: Instant?, freshness: Freshness, now: Instant): Boolean {
        if (freshness == Freshness.ANY || published == null) return true
        val days = when (freshness) {
            Freshness.DAY -> 1L
            Freshness.WEEK -> 7L
            Freshness.MONTH -> 30L
            Freshness.YEAR -> 365L
            Freshness.ANY -> return true
        }
        return !published.isBefore(now.minus(days, ChronoUnit.DAYS))
    }

    private fun dedupe(results: List<SearchResult>): List<SearchResult> {
        val kept = linkedMapOf<String, SearchResult>()
        for (result in results) {
            val existing = kept[result.url]
            if (existing == null) {
                kept[result.url] = result
            } else if (richness(result) > richness(existing)) {
                kept[result.url] = absorb(result, existing)
            } else {
                kept[result.url] = absorb(existing, result)
            }
        }
        return kept.values.toList()
    }

    private fun absorb(primary: SearchResult, other: SearchResult): SearchResult {
        val names = (primary.origin.alsoFrom + other.origin.profileName + other.origin.alsoFrom)
            .filter { it.isNotBlank() && it != primary.origin.profileName }
            .distinct()
        return primary.copy(origin = primary.origin.copy(alsoFrom = names))
    }

    private fun richness(result: SearchResult): Int =
        (result.content?.length ?: 0) + result.snippet.length + if (result.publishedAt != null) 100 else 0

    private fun floatPreferred(results: List<SearchResult>, domains: List<String>): List<SearchResult> {
        if (domains.isEmpty()) return results
        return results.sortedWith(compareByDescending { result ->
            domains.any { domain -> matchesDomain(hostOf(result.url), domain) }
        })
    }
}
