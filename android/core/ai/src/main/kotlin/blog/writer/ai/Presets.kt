package blog.writer.ai
import blog.writer.copy.Copy

data class LlmPreset(
    val id: String,
    val name: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val domesticDirect: Boolean?,
    val builtinSearch: Boolean,
    val contextLength: Int,
    val maxOutput: Int = 2048,
    val stream: Boolean = true,
    val jsonOutput: Boolean = true,
)

object LlmPresets {
    val deepSeek = preset(
        id = "deepseek",
        name = Copy["preset_deepseek"],
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
        domestic = true,
        search = false,
        context = 65_536,
    )
    val qwen = preset(
        id = "qwen",
        name = Copy["preset_qwen"],
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        domestic = true,
        search = true,
        context = 131_072,
    )
    val kimi = preset(
        id = "kimi",
        name = Copy["preset_kimi"],
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k2",
        domestic = true,
        search = true,
        context = 131_072,
    )
    val glm = preset(
        id = "glm",
        name = Copy["preset_glm"],
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-4.5",
        domestic = true,
        search = true,
        context = 128_000,
    )
    val doubao = preset(
        id = "doubao",
        name = Copy["preset_doubao"],
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        model = "doubao-seed-1-6",
        domestic = true,
        search = false,
        context = 32_000,
    )
    val siliconFlow = preset(
        id = "siliconflow",
        name = Copy["preset_siliconflow"],
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "deepseek-ai/DeepSeek-V3",
        domestic = true,
        search = false,
        context = 65_536,
    )
    val magpie = preset(
        id = "magpie",
        name = Copy["preset_magpie"],
        baseUrl = "http://127.0.0.1:3425/v1",
        model = "deepseek/deepseek-v4-pro",
        domestic = true,
        search = false,
        context = 131_072,
    )
    val openAi = preset(
        id = "openai",
        name = Copy["preset_openai"],
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4.1-mini",
        domestic = false,
        search = false,
        context = 128_000,
    )
    val anthropic = LlmPreset(
        id = "anthropic",
        name = Copy["anthropic"],
        protocol = LlmProtocol.Anthropic,
        baseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-5",
        domesticDirect = false,
        builtinSearch = true,
        contextLength = 200_000,
    )
    val gemini = LlmPreset(
        id = "gemini",
        name = Copy["preset_gemini"],
        protocol = LlmProtocol.Gemini,
        baseUrl = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        domesticDirect = false,
        builtinSearch = true,
        contextLength = 1_048_576,
    )
    val openRouter = preset(
        id = "openrouter",
        name = Copy["preset_openrouter"],
        baseUrl = "https://openrouter.ai/api/v1",
        model = "deepseek/deepseek-chat",
        domestic = false,
        search = false,
        context = 128_000,
    )
    val custom = LlmPreset(
        id = "custom",
        name = Copy["custom"],
        protocol = LlmProtocol.OpenAiCompatible,
        baseUrl = "",
        defaultModel = "",
        domesticDirect = null,
        builtinSearch = false,
        contextLength = 8_192,
        jsonOutput = false,
    )

    val domestic: List<LlmPreset> = listOf(deepSeek, qwen, kimi, glm, doubao, siliconFlow, magpie)
    val overseas: List<LlmPreset> = listOf(openAi, anthropic, gemini, openRouter)
    val all: List<LlmPreset> = domestic + overseas + custom

    fun byId(id: String): LlmPreset? = all.firstOrNull { it.id == id }

    private fun preset(
        id: String,
        name: String,
        baseUrl: String,
        model: String,
        domestic: Boolean,
        search: Boolean,
        context: Int,
    ) = LlmPreset(
        id = id,
        name = name,
        protocol = LlmProtocol.OpenAiCompatible,
        baseUrl = baseUrl,
        defaultModel = model,
        domesticDirect = domestic,
        builtinSearch = search,
        contextLength = context,
    )
}
