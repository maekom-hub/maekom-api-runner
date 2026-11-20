package com.maekom.apirunner.engine

import com.maekom.apirunner.model.QueryParamItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// [v0.1.2] 내장 동적 시간·랜덤 변수({{$isoTimestamp}}, {{$kstNow}}, {{$uuid}} 등) 실시간 평가 기능 추가
// 변경 의도: 별도 변수 선언 없이 URL/Params/Headers/Body 어디서나 실시간 타임스탬프·거래번호·UUID를 즉시 치환
data class DynamicVariableSpec(
    val name: String,
    val token: String,
    val descKey: String,
    val evaluator: () -> String
)

/**
 * Resolves {{variableName}} and built-in dynamic {{$...}} placeholders with priority:
 * 1. Built-in dynamic variables ({{$isoTimestamp}}, {{$timestamp}}, {{$kstNow}}, {{$uuid}}, etc.)
 * 2. runtimeContext (variables extracted during scenario execution)
 * 3. secrets (.maekom/secrets.local.json)
 * 4. globalVariables (scenario global variables)
 */
object VariableResolver {
    private val PLACEHOLDER_REGEX = Regex("""\{\{\s*(\$?[a-zA-Z0-9_.-]+)\s*}}""")

    private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private val UTC_ISO_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val KST_NOW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val KST_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val KST_COMPACT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val KST_ISO_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    val DYNAMIC_VARIABLES: List<DynamicVariableSpec> = listOf(
        DynamicVariableSpec(
            name = "\$isoTimestamp",
            token = "{{\$isoTimestamp}}",
            descKey = "dynvar.isoTimestamp",
            evaluator = { UTC_ISO_FMT.format(Instant.now()) }
        ),
        DynamicVariableSpec(
            name = "\$timestamp",
            token = "{{\$timestamp}}",
            descKey = "dynvar.timestamp",
            evaluator = { Instant.now().epochSecond.toString() }
        ),
        DynamicVariableSpec(
            name = "\$timestampMs",
            token = "{{\$timestampMs}}",
            descKey = "dynvar.timestampMs",
            evaluator = { Instant.now().toEpochMilli().toString() }
        ),
        DynamicVariableSpec(
            name = "\$kstNow",
            token = "{{\$kstNow}}",
            descKey = "dynvar.kstNow",
            evaluator = { ZonedDateTime.now(KST_ZONE).format(KST_NOW_FMT) }
        ),
        DynamicVariableSpec(
            name = "\$kstDate",
            token = "{{\$kstDate}}",
            descKey = "dynvar.kstDate",
            evaluator = { ZonedDateTime.now(KST_ZONE).format(KST_DATE_FMT) }
        ),
        DynamicVariableSpec(
            name = "\$kstCompact",
            token = "{{\$kstCompact}}",
            descKey = "dynvar.kstCompact",
            evaluator = { ZonedDateTime.now(KST_ZONE).format(KST_COMPACT_FMT) }
        ),
        DynamicVariableSpec(
            name = "\$kstIso",
            token = "{{\$kstIso}}",
            descKey = "dynvar.kstIso",
            evaluator = { ZonedDateTime.now(KST_ZONE).format(KST_ISO_FMT) }
        ),
        DynamicVariableSpec(
            name = "\$uuid",
            token = "{{\$uuid}}",
            descKey = "dynvar.uuid",
            evaluator = { UUID.randomUUID().toString() }
        )
    )

    private val DYNAMIC_VAR_MAP: Map<String, DynamicVariableSpec> =
        DYNAMIC_VARIABLES.associateBy { it.name }

    fun evaluateDynamicVariable(key: String): String? {
        return DYNAMIC_VAR_MAP[key]?.evaluator?.invoke()
    }

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

    // [v0.3.1] 개별 스텝 헤더 값이 null/빈 값(isBlank)일 때 활성 공통 헤더의 유효 값으로 자동 폴백(Fallback) 병합
    // 변경 의도: 스텝 헤더에 키만 존재하고 값이 비어 있을 때 공통 헤더 값이 빈 문자열로 덮어써지는 문제를 방지하며,
    //          단건 실행·전체 시나리오 실행·cURL 복사 모두 동일한 단일 병합 규칙(대소문자 무관 키 비교)을 적용
    fun mergeHeaders(
        globalHeaders: Map<String, String?>,
        stepHeaders: Map<String, String?>
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

        globalHeaders.forEach { (k, v) -> putHeader(k, v.orEmpty()) }
        stepHeaders.forEach { (k, v) ->
            val trimmedKey = k.trim()
            if (trimmedKey.isEmpty()) return@forEach
            val lower = trimmedKey.lowercase()
            val existingKey = lowerToActualKey[lower]
            val existingGlobalValue = existingKey?.let { merged[it] }
            if (!v.isNullOrBlank()) {
                putHeader(trimmedKey, v)
            } else if (existingKey != null && !existingGlobalValue.isNullOrBlank()) {
                // 개별 스텝 헤더 값이 비어 있고 활성 공통 헤더에 유효한 값이 있으면 공통 헤더 값으로 폴백 유지
            } else {
                putHeader(trimmedKey, v.orEmpty())
            }
        }
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
        if (key.startsWith("$")) {
            evaluateDynamicVariable(key)?.let { return it }
        }
        return runtimeContext[key]
            ?: secrets[key]
            ?: globalVariables[key]
    }
}
