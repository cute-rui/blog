package blog.writer.search

import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class TemplateMode { URL, JSON }

data class TemplateContext(
    val request: SearchRequest,
    val secrets: Map<String, String>,
)

object HttpTemplate {
    private val placeholder = Regex("""\{\{\s*([A-Za-z0-9_.]+)(?:\|([^}]+))?\s*\}\}""")

    fun render(template: String, context: TemplateContext, mode: TemplateMode): String {
        val rendered = placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            val filter = match.groupValues[2]
            val raw = resolve(name, context)
            val value = applyFilter(raw, filter, context)
            if (value.isEmpty()) return@replace ""
            val quoted = match.range.first > 0 && template[match.range.first - 1] == '"'
            when {
                mode == TemplateMode.URL -> URLEncoder.encode(value, Charsets.UTF_8)
                quoted -> jsonEscape(value)
                else -> value
            }
        }
        return if (mode == TemplateMode.JSON) dropEmptyJsonFields(rendered) else rendered
    }

    fun unknownPlaceholders(template: String): List<String> {
        val known = setOf(
            "query", "maxResults", "language", "region", "freshness", "since",
            "includeDomains", "excludeDomains",
        )
        return placeholder.findAll(template).map { it.groupValues[1] }.filter { name ->
            name != "secret" && !name.startsWith("secret.") && name !in known
        }.toList()
    }

    private fun resolve(name: String, context: TemplateContext): String {
        val request = context.request
        return when {
            name == "query" -> request.query
            name == "maxResults" -> request.maxResults.toString()
            name == "language" -> request.language.orEmpty()
            name == "region" -> request.region.orEmpty()
            name == "freshness" -> request.freshness.name
            name == "since" -> request.since?.toString().orEmpty()
            name == "includeDomains" -> request.includeDomains.joinToString(",")
            name == "excludeDomains" -> request.excludeDomains.joinToString(",")
            name.startsWith("secret.") -> context.secrets[name.removePrefix("secret.")].orEmpty()
            else -> ""
        }
    }

    private fun applyFilter(value: String, filter: String, context: TemplateContext): String {
        if (filter.isBlank()) return value
        val name = filter.substringBefore(':').trim()
        val argument = filter.substringAfter(':', "")
        return when (name) {
            "default" -> value.ifBlank { unquote(argument) }
            "join" -> value.split(',').filter { it.isNotEmpty() }.joinToString(argument.ifBlank { "," })
            "urlencode" -> URLEncoder.encode(value, Charsets.UTF_8)
            "date" -> formatDate(value, context.request.since, argument.ifBlank { "yyyy-MM-dd" })
            "map" -> mapValue(value, argument)
            else -> value
        }
    }

    private fun mapValue(value: String, argument: String): String {
        val body = argument.trim().removePrefix("{").removeSuffix("}")
        val pairs = body.split(',').mapNotNull { part ->
            val key = part.substringBefore(':').trim()
            val mapped = unquote(part.substringAfter(':', ""))
            if (key.isBlank()) null else key to mapped
        }.toMap()
        return pairs[value] ?: ""
    }

    private fun formatDate(value: String, since: LocalDate?, pattern: String): String {
        val date = since ?: runCatching { LocalDate.parse(value) }.getOrNull() ?: return value
        return runCatching { date.format(DateTimeFormatter.ofPattern(pattern)) }.getOrDefault(date.toString())
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("'").removeSurrounding("\"")

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun dropEmptyJsonFields(json: String): String {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() ?: return json
        return kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), prune(element))
    }

    private fun prune(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        return when (element) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(
                element.mapValues { (_, value) -> prune(value) }
                    .filterValues { value ->
                        when (value) {
                            is kotlinx.serialization.json.JsonPrimitive -> value.isString && value.content.isNotEmpty() || !value.isString
                            is kotlinx.serialization.json.JsonArray -> value.isNotEmpty()
                            else -> true
                        }
                    },
            )
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map(::prune))
            else -> element
        }
    }
}
