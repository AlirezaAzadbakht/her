package com.her.scenario

import com.her.data.secure.LlmSettings
import java.io.File

object ScenarioEnv {
    const val BASE_URL = "LLM_API_BASE_URL"
    const val MODEL = "LLM_MODEL_IDENTIFIER"
    const val API_KEY = "LLM_API_SECRET_KEY"
    const val JUDGE_MODEL = "HER_SCENARIO_JUDGE_MODEL"

    fun load(root: File = ScenarioPaths.repoRoot()): LlmSettings {
        val fileValues = parseDotEnv(ScenarioPaths.envFile(root))
        fun pick(name: String): String =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fileValues[name]?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
        val settings = LlmSettings(
            baseUrl = pick(BASE_URL),
            apiKey = pick(API_KEY),
            model = pick(MODEL),
        )
        if (!settings.isConfigured) {
            error(
                "Scenario engine needs an LLM. Set $BASE_URL, $MODEL, and $API_KEY " +
                    "in the repo-root .env or the environment.",
            )
        }
        return settings
    }

    fun judgeModel(fallback: String): String =
        System.getenv(JUDGE_MODEL)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    fun parseDotEnv(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val values = linkedMapOf<String, String>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val body = line.removePrefix("export ").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) return@forEach
            val key = body.substring(0, eq).trim()
            var value = body.substring(eq + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))
            ) {
                value = value.substring(1, value.length - 1)
            }
            values[key] = value
        }
        return values
    }
}
