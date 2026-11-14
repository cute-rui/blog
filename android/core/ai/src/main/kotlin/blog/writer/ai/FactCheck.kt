package blog.writer.ai

import blog.writer.search.FactCheckSearchSettings
import blog.writer.search.RoutedSearch
import blog.writer.search.SearchResult
import blog.writer.search.SearchRouter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Verdict { Supported, Uncertain, Unverifiable, Wrong }

data class ExtractedClaim(val claim: String, val query: String)

data class CheckedClaim(
    val claim: String,
    val query: String,
    val verdict: Verdict,
    val label: String,
    val reason: String,
    val sourceIndexes: List<Int>,
    val fix: String?,
    val results: List<SearchResult>,
    val attempts: List<String>,
    val searched: Boolean,
    val inSource: Boolean,
)

data class FactCheckReport(
    val checked: List<CheckedClaim>,
    val unmatched: List<CheckedClaim>,
    val promptVersion: Int,
)

data class RawJudgement(
    val verdict: String,
    val reason: String,
    val sources: List<Int>,
    val fix: String?,
)

object FactCheckRules {
    const val RETRIEVAL_FAILED = "无法核实（检索失败）"
    const val NOT_SEARCHED = "未检索"

    fun sanitize(
        claim: String,
        raw: RawJudgement,
        sourceText: String,
        resultCount: Int,
    ): CheckedClaim {
        val inSource = claim.isNotBlank() && sourceText.contains(claim)
        val sources = raw.sources.filter { it in 1..resultCount }.distinct()
        var verdict = parseVerdict(raw.verdict)
        if (verdict == Verdict.Supported && sources.isEmpty()) verdict = Verdict.Unverifiable
        return CheckedClaim(
            claim = claim,
            query = "",
            verdict = verdict,
            label = labelOf(verdict),
            reason = raw.reason,
            sourceIndexes = sources,
            fix = raw.fix,
            results = emptyList(),
            attempts = emptyList(),
            searched = true,
            inSource = inSource,
        )
    }

    fun retrievalFailure(claim: ExtractedClaim, routed: RoutedSearch): CheckedClaim {
        val reason = routed.attempts.joinToString(" · ") { attempt ->
            "${attempt.profileName}：${attempt.failure ?: "失败"}"
        }.ifBlank { "没有可用的搜索来源" }
        return CheckedClaim(
            claim = claim.claim,
            query = claim.query,
            verdict = Verdict.Unverifiable,
            label = RETRIEVAL_FAILED,
            reason = reason,
            sourceIndexes = emptyList(),
            fix = null,
            results = emptyList(),
            attempts = routed.attempts.map { "${it.profileName}：${it.failure ?: "失败"}" },
            searched = true,
            inSource = true,
        )
    }

    fun notSearched(claim: ExtractedClaim): CheckedClaim = CheckedClaim(
        claim = claim.claim,
        query = claim.query,
        verdict = Verdict.Unverifiable,
        label = NOT_SEARCHED,
        reason = "超出本次检索条数",
        sourceIndexes = emptyList(),
        fix = null,
        results = emptyList(),
        attempts = emptyList(),
        searched = false,
        inSource = true,
    )

    fun parseVerdict(value: String): Verdict = when (value.trim()) {
        "属实" -> Verdict.Supported
        "存疑" -> Verdict.Uncertain
        "错误" -> Verdict.Wrong
        else -> Verdict.Unverifiable
    }

    fun labelOf(verdict: Verdict): String = when (verdict) {
        Verdict.Supported -> "属实"
        Verdict.Uncertain -> "存疑"
        Verdict.Wrong -> "错误"
        Verdict.Unverifiable -> "无法核实"
    }
}

class FactChecker(
    private val router: SearchRouter,
    private val extract: suspend (title: String, text: String) -> String,
    private val judge: suspend (claim: String, results: List<SearchResult>) -> String,
) {
    suspend fun check(
        title: String,
        text: String,
        profiles: List<blog.writer.search.SearchProfile>,
        settings: FactCheckSearchSettings,
    ): FactCheckReport {
        if (profiles.none { it.enabled && !it.lastTestFailed }) {
            return FactCheckReport(
                checked = listOf(
                    CheckedClaim(
                        claim = "",
                        query = "",
                        verdict = Verdict.Unverifiable,
                        label = FactCheckRules.RETRIEVAL_FAILED,
                        reason = "还没有可用的搜索来源",
                        sourceIndexes = emptyList(),
                        fix = null,
                        results = emptyList(),
                        attempts = emptyList(),
                        searched = false,
                        inSource = false,
                    ),
                ),
                unmatched = emptyList(),
                promptVersion = Prompts.version,
            )
        }
        val extracted = parseClaims(extract(title, text))
        val limit = settings.maxClaims.coerceAtLeast(0)
        val searched = extracted.take(limit)
        val skipped = extracted.drop(limit)
        var spent = 0.0
        val checked = mutableListOf<CheckedClaim>()
        val unmatched = mutableListOf<CheckedClaim>()
        for (claim in searched) {
            if (!text.contains(claim.claim)) {
                unmatched += FactCheckRules.notSearched(claim).copy(inSource = false, reason = "原文里找不到这条摘录")
                continue
            }
            val routed = router.search(claim.query, profiles, settings, spent)
            spent = routed.estimatedCost
            if (routed.failedAll || routed.results.isEmpty()) {
                checked += FactCheckRules.retrievalFailure(claim, routed)
                continue
            }
            val raw = parseJudgement(judge(claim.claim, routed.results))
            val sanitized = FactCheckRules.sanitize(claim.claim, raw, text, routed.results.size).copy(
                query = claim.query,
                results = routed.results,
            )
            if (sanitized.inSource) checked += sanitized else unmatched += sanitized
        }
        checked += skipped.map { FactCheckRules.notSearched(it) }
        return FactCheckReport(checked, unmatched, Prompts.version)
    }
}

fun parseClaims(raw: String): List<ExtractedClaim> =
    jsonObjects(raw).mapNotNull { json ->
        val dto = runCatching { claimJson.decodeFromString<ClaimDto>(json) }.getOrNull() ?: return@mapNotNull null
        if (dto.claim.isBlank()) null else ExtractedClaim(dto.claim, dto.query.ifBlank { dto.claim })
    }

fun parseJudgement(raw: String): RawJudgement {
    val json = jsonObjects(raw).firstOrNull() ?: return RawJudgement("无法核实", "", emptyList(), null)
    val dto = runCatching { claimJson.decodeFromString<JudgementDto>(json) }.getOrNull()
        ?: return RawJudgement("无法核实", "", emptyList(), null)
    return RawJudgement(dto.verdict, dto.reason, dto.sources, dto.fix)
}

fun formatSearchResults(results: List<SearchResult>): String = results.joinToString("\n") { result ->
    val date = result.publishedAt?.toString() ?: "无日期"
    val site = result.siteName ?: result.url
    "[${result.rank}] ${result.title} / $site / $date / ${result.url}\n    ${result.snippet}"
}

@Serializable
private data class ClaimDto(val claim: String, val query: String = "")

@Serializable
private data class JudgementDto(
    val verdict: String = "无法核实",
    val reason: String = "",
    val sources: List<Int> = emptyList(),
    val fix: String? = null,
)

private val claimJson = Json { ignoreUnknownKeys = true }

internal fun jsonObjects(raw: String): List<String> {
    val text = raw.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
    val out = mutableListOf<String>()
    var depth = 0
    var start = -1
    var quote = false
    var escaped = false
    text.forEachIndexed { index, ch ->
        if (quote) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quote = false
            return@forEachIndexed
        }
        when (ch) {
            '"' -> quote = true
            '{' -> {
                if (depth == 0) start = index
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    out += text.substring(start, index + 1)
                    start = -1
                }
                if (depth < 0) depth = 0
            }
        }
    }
    return out
}
