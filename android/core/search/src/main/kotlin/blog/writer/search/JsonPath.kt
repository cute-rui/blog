package blog.writer.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object JsonPath {
    fun read(root: JsonElement, path: String): JsonElement? = walk(root, tokens(path))

    fun readText(root: JsonElement, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val value = read(root, path) as? JsonPrimitive ?: return null
        return value.contentOrNull
    }

    fun readArray(root: JsonElement, path: String): List<JsonElement> {
        val value = read(root, path) ?: return emptyList()
        return when (value) {
            is JsonArray -> value
            else -> listOf(value)
        }
    }

    private fun walk(current: JsonElement?, tokens: List<Token>): JsonElement? {
        if (current == null) return null
        if (tokens.isEmpty()) return current
        val token = tokens.first()
        val rest = tokens.drop(1)
        return when (token) {
            is Token.Field -> {
                val next = (current as? JsonObject)?.get(token.name)
                walk(next, rest)
            }
            Token.Wildcard -> when (current) {
                is JsonArray -> current
                else -> current
            }
            is Token.Index -> walk((current as? JsonArray)?.getOrNull(token.index), rest)
        }
    }

    private fun tokens(path: String): List<Token> {
        val body = path.trim().removePrefix("$").removePrefix(".")
        if (body.isEmpty()) return emptyList()
        val out = mutableListOf<Token>()
        for (part in body.split('.')) {
            val name = part.substringBefore('[')
            if (name.isNotEmpty() && name != "*") out += Token.Field(name)
            if (name == "*") out += Token.Wildcard
            Regex("\\[(\\d+|\\*)]").findAll(part).forEach { match ->
                val token = match.groupValues[1]
                out += if (token == "*") Token.Wildcard else Token.Index(token.toInt())
            }
        }
        return out
    }

    private sealed interface Token {
        data class Field(val name: String) : Token
        data class Index(val index: Int) : Token
        data object Wildcard : Token
    }
}
