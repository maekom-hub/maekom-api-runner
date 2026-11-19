package com.maekom.apirunner.engine

import com.maekom.apirunner.model.QueryParamItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    /**
     * Appends only enabled QueryParamItems onto the URL while merging cleanly with
     * any inline query parameters already present in the URL.
     * If a key exists in both inline URL and enabled Params, the Params entry overrides the inline one.
     */
    fun appendQueryParams(
        url: String,
        queryParams: List<QueryParamItem>,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        globalVariables: Map<String, String> = emptyMap()
    ): String {
        val resolvedParams = queryParams
            .filter { it.enabled }
            .mapNotNull { item ->
                val resolvedKey = resolve(item.key, runtimeContext, secrets, globalVariables).trim()
                if (resolvedKey.isEmpty()) {
                    null
                } else {
                    val resolvedVal = resolve(item.value, runtimeContext, secrets, globalVariables)
                    resolvedKey to resolvedVal
                }
            }

        if (resolvedParams.isEmpty()) {
            return url
        }

        // Preserve optional #fragment
        val hashIndex = url.indexOf('#')
        val urlWithoutFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""

        val queryIndex = urlWithoutFragment.indexOf('?')
        val basePart = if (queryIndex >= 0) urlWithoutFragment.substring(0, queryIndex) else urlWithoutFragment
        val existingQuery = if (queryIndex >= 0) urlWithoutFragment.substring(queryIndex + 1) else ""

        val paramKeys = resolvedParams.map { it.first }.toSet()
        val finalParts = mutableListOf<String>()

        // Keep existing inline query parameters unless overridden by an enabled Params item
        if (existingQuery.isNotBlank()) {
            for (segment in existingQuery.split('&')) {
                if (segment.isBlank()) continue
                val eqIdx = segment.indexOf('=')
                val rawKey = if (eqIdx >= 0) segment.substring(0, eqIdx) else segment
                val decodedOrRawKey = rawKey.trim()
                if (decodedOrRawKey !in paramKeys && encodeQueryComponent(decodedOrRawKey) !in paramKeys) {
                    finalParts.add(segment)
                }
            }
        }

        for ((k, v) in resolvedParams) {
            finalParts.add("${encodeQueryComponent(k)}=${encodeQueryComponent(v)}")
        }

        return if (finalParts.isEmpty()) {
            basePart + fragment
        } else {
            "$basePart?${finalParts.joinToString("&")}$fragment"
        }
    }

    private fun encodeQueryComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
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
