package com.maekom.apirunner.engine

/**
 * Resolves {{variableName}} placeholders with strict priority:
 * 1. runtimeContext (variables extracted during scenario execution)
 * 2. secrets (.maekom/secrets.local.json)
 * 3. globalVariables (scenario global variables)
 */
object VariableResolver {
    private val PLACEHOLDER_REGEX = Regex("""\{\{\s*([a-zA-Z0-9_.-]+)\s*}}""")

    fun resolve(
        template: String,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        globalVariables: Map<String, String> = emptyMap()
    ): String {
        if (template.isEmpty() || !template.contains("{{")) {
            return template
        }
        return PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            lookup(key, runtimeContext, secrets, globalVariables) ?: match.value
        }
    }

    fun resolveMap(
        source: Map<String, String>,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        globalVariables: Map<String, String> = emptyMap()
    ): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        for ((k, v) in source) {
            val resolvedKey = resolve(k, runtimeContext, secrets, globalVariables).trim()
            if (resolvedKey.isNotEmpty()) {
                result[resolvedKey] = resolve(v, runtimeContext, secrets, globalVariables)
            }
        }
        return result
    }

    /**
     * Merges global headers with step headers. Step headers override global headers (case-insensitively).
     */
    fun mergeHeaders(
        globalHeaders: Map<String, String>,
        stepHeaders: Map<String, String>
    ): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        val lowerToActualKey = mutableMapOf<String, String>()

        fun putHeader(k: String, v: String) {
            val trimmedKey = k.trim()
            if (trimmedKey.isEmpty()) return
            val lower = trimmedKey.lowercase()
            val existingKey = lowerToActualKey[lower]
            if (existingKey != null && existingKey != trimmedKey) {
                merged.remove(existingKey)
            }
            lowerToActualKey[lower] = trimmedKey
            merged[trimmedKey] = v
        }

        globalHeaders.forEach { (k, v) -> putHeader(k, v) }
        stepHeaders.forEach { (k, v) -> putHeader(k, v) }
        return merged
    }

    fun combineUrl(baseUrl: String, stepUrl: String): String {
        val trimmedStep = stepUrl.trim()
        if (trimmedStep.startsWith("http://", ignoreCase = true) ||
            trimmedStep.startsWith("https://", ignoreCase = true)
        ) {
            return trimmedStep
        }
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isEmpty()) {
            return trimmedStep
        }
        if (trimmedStep.isEmpty() || trimmedStep == "/") {
            return "$cleanBase/"
        }
        val normalizedStep = if (trimmedStep.startsWith("/") || trimmedStep.startsWith("?")) {
            trimmedStep
        } else {
            "/$trimmedStep"
        }
        return cleanBase + normalizedStep
    }

    fun lookup(
        key: String,
        runtimeContext: Map<String, String>,
        secrets: Map<String, String>,
        globalVariables: Map<String, String>
    ): String? {
        return runtimeContext[key]
            ?: secrets[key]
            ?: globalVariables[key]
    }
}
