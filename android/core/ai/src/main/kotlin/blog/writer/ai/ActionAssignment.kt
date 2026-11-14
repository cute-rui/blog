package blog.writer.ai

data class ModelChoice(
    val profileId: String?,
    val model: String?,
    val followDefault: Boolean,
    val temperature: Double,
)

data class ResolvedModel(
    val profileId: String,
    val model: String,
    val temperature: Double,
)

data class ActionAssignments(
    val defaultProfileId: String? = null,
    val defaultModel: String? = null,
    val continueWriting: ModelChoice = ModelChoice(
        profileId = null,
        model = null,
        followDefault = true,
        temperature = 0.9,
    ),
) {
    fun resolveContinue(): ResolvedModel? {
        val profile = if (continueWriting.followDefault) defaultProfileId else continueWriting.profileId
        val model = if (continueWriting.followDefault) defaultModel else continueWriting.model
        if (profile.isNullOrBlank() || model.isNullOrBlank()) return null
        return ResolvedModel(profile, model, continueWriting.temperature)
    }
}
