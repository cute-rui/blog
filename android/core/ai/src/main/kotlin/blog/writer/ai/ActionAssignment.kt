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
    val fillMiddle: ModelChoice = ModelChoice(
        profileId = null,
        model = null,
        followDefault = true,
        temperature = 0.7,
    ),
    val fillTargetWords: Int = 600,
    val factCheck: ModelChoice = ModelChoice(
        profileId = null,
        model = null,
        followDefault = true,
        temperature = 0.1,
    ),
    val factMode: String = "AUTO",
    val factMergeCount: Int = 2,
    val factPerClaim: Int = 5,
    val factMaxClaims: Int = 10,
    val factPreferredDomains: String = "",
    val factCostCap: Double? = null,
    val factUseBuiltin: Boolean = true,
    val factSingleProfileId: String? = null,
) {
    fun resolveContinue(): ResolvedModel? = resolve(continueWriting, continueWriting.temperature)

    fun resolveFill(): ResolvedModel? = resolve(fillMiddle, fillMiddle.temperature)

    fun resolveFactCheck(): ResolvedModel? = resolve(factCheck, 0.1)

    private fun resolve(choice: ModelChoice, temperature: Double): ResolvedModel? {
        val profile = if (choice.followDefault) defaultProfileId else choice.profileId
        val model = if (choice.followDefault) defaultModel else choice.model
        if (profile.isNullOrBlank() || model.isNullOrBlank()) return null
        return ResolvedModel(profile, model, temperature)
    }
}
