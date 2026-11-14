package blog.writer.ai

data class AiHistoryEntry(
    val action: String,
    val promptVersion: Int,
    val profileId: String,
    val model: String,
    val createdAtEpochMs: Long,
)

fun historyEntry(action: String, profileId: String, model: String, atEpochMs: Long): AiHistoryEntry =
    AiHistoryEntry(action, Prompts.version, profileId, model, atEpochMs)
