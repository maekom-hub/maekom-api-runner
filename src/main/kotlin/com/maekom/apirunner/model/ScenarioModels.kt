package com.maekom.apirunner.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

// [v0.3.2] 환경 프로필 보호 모드(confirmBeforeRun), 스텝 별칭(alias) 및 실행 전 대기 시간(delayMs) 선택 필드 추가
// 변경 의도: 기존 .maekom/scenarios/*.json 및 environments.json과 100% 하위 호환(기본값 + ignoreUnknown)을 유지하면서
//          PRD 실수 실행 방지, 스텝 목록 가독성 향상 및 스텝 간 지연 실행을 지원

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS;

    companion object {
        fun fromString(value: String?): HttpMethod {
            if (value.isNullOrBlank()) return GET
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: GET
        }
    }
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}

enum class ScenarioStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    ABORTED
}

// [v0.2.0] BaseURL 환경 프로필(Local/DEV/STG/PRD) 영속 모델(EnvironmentProfile, EnvironmentConfig) 추가
// 변경 의도: 시나리오를 전환해도 프로젝트별 환경(BaseURL) 매핑 설정이 유지되고 즉시 전환할 수 있도록 지원

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvironmentProfile(
    var name: String = "Local",
    var baseUrl: String = "http://localhost:8080",
    var builtInLocal: Boolean = false,
    var confirmBeforeRun: Boolean = false
) {
    fun deepCopy(): EnvironmentProfile = copy()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvironmentConfig(
    var selectedEnvironment: String = "Local",
    var profiles: MutableList<EnvironmentProfile> = mutableListOf()
) {
    fun deepCopy(): EnvironmentConfig = EnvironmentConfig(
        selectedEnvironment = selectedEnvironment,
        profiles = profiles.map { it.deepCopy() }.toMutableList()
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonHeaderItem(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
    var secret: Boolean = false
) {
    fun deepCopy(): CommonHeaderItem = copy()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GlobalVariableItem(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
    var secret: Boolean = false
) {
    fun deepCopy(): GlobalVariableItem = copy()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class QueryParamItem(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
    var description: String = ""
) {
    fun deepCopy(): QueryParamItem = copy()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractRule(
    var varName: String = "",
    var jsonPath: String = "",
    var required: Boolean = true
) {
    fun deepCopy(): ExtractRule = copy()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiStep(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var alias: String = "",
    var delayMs: Long = 0L,
    var enabled: Boolean = true,
    var method: String = "GET",
    var url: String = "/",
    var expectedStatus: String = "2xx",
    var queryParams: MutableList<QueryParamItem> = mutableListOf(),
    var headers: MutableMap<String, String> = linkedMapOf(),
    var body: String = "",
    var extractRules: MutableList<ExtractRule> = mutableListOf()
) {
    fun deepCopy(): ApiStep = ApiStep(
        id = UUID.randomUUID().toString(),
        name = name,
        alias = alias,
        delayMs = delayMs,
        enabled = enabled,
        method = method,
        url = url,
        expectedStatus = expectedStatus,
        queryParams = queryParams.map { it.deepCopy() }.toMutableList(),
        headers = LinkedHashMap(headers),
        body = body,
        extractRules = extractRules.map { it.deepCopy() }.toMutableList()
    )

    @JsonIgnore
    fun displayTitle(): String {
        return alias.takeIf { it.isNotBlank() }
            ?: name.takeIf { it.isNotBlank() }
            ?: url.ifBlank { "/" }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FolderMetadata(
    var description: String = "",
    var order: MutableList<String> = mutableListOf()
)

data class ScenarioEntryInfo(
    val relativePath: String,
    val folderPath: String,
    val name: String,
    val description: String = "",
    val primaryMethod: String? = null,
    val primaryUrl: String? = null,
    val stepCount: Int = 0,
    val baseUrl: String = ""
) {
    fun displayLabel(): String {
        return if (folderPath.isBlank()) {
            name
        } else {
            "${folderPath.replace("/", " / ")} / $name"
        }
    }
}

data class FolderEntryInfo(
    val relativePath: String,
    val parentPath: String,
    val name: String,
    val description: String = "",
    val scenarioCount: Int = 0
)

data class ScenarioTreeSnapshot(
    val folders: List<FolderEntryInfo>,
    val scenarios: List<ScenarioEntryInfo>,
    val folderOrders: Map<String, List<String>> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiScenario(
    var name: String = "default",
    var description: String = "",
    var baseUrl: String = "http://localhost:8080",
    var ignoreSsl: Boolean = false,
    var globalHeaders: MutableMap<String, String> = linkedMapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json"
    ),
    var commonHeaders: MutableList<CommonHeaderItem> = mutableListOf(),
    var globalVariables: MutableMap<String, String> = linkedMapOf(),
    var steps: MutableList<ApiStep> = mutableListOf()
) {
    fun deepCopy(newName: String = name): ApiScenario = ApiScenario(
        name = newName,
        description = description,
        baseUrl = baseUrl,
        ignoreSsl = ignoreSsl,
        globalHeaders = LinkedHashMap(globalHeaders),
        commonHeaders = commonHeaders.map { it.deepCopy() }.toMutableList(),
        globalVariables = LinkedHashMap(globalVariables),
        steps = steps.map { it.deepCopy() }.toMutableList()
    )

    /**
     * Returns structured common header items, falling back seamlessly to legacy [globalHeaders]
     * when loading older scenario JSON files that do not contain [commonHeaders].
     */
    @JsonIgnore
    fun effectiveCommonHeaderItems(): MutableList<CommonHeaderItem> {
        if (commonHeaders.isNotEmpty()) {
            return commonHeaders.map { it.deepCopy() }.toMutableList()
        }
        return globalHeaders.entries.map { (k, v) ->
            CommonHeaderItem(enabled = true, key = k, value = v, secret = false)
        }.toMutableList()
    }

    /**
     * Returns only enabled common headers as a Map for HTTP execution and cURL export.
     */
    @JsonIgnore
    fun effectiveEnabledHeadersMap(): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (commonHeaders.isNotEmpty()) {
            for (item in commonHeaders) {
                val k = item.key.trim()
                if (item.enabled && k.isNotEmpty()) {
                    result[k] = item.value
                }
            }
            return result
        }
        for ((k, v) in globalHeaders) {
            val trimmed = k.trim()
            if (trimmed.isNotEmpty()) {
                result[trimmed] = v
            }
        }
        return result
    }

    /**
     * Synchronizes both [commonHeaders] and legacy [globalHeaders] from edited header items.
     */
    @JsonIgnore
    fun applyCommonHeaderItems(items: List<CommonHeaderItem>) {
        commonHeaders = items
            .filter { it.key.trim().isNotEmpty() }
            .map { it.copy(key = it.key.trim()) }
            .toMutableList()
        val syncedMap = linkedMapOf<String, String>()
        for (item in commonHeaders) {
            if (item.enabled) {
                syncedMap[item.key] = item.value
            }
        }
        globalHeaders = syncedMap
    }
}

data class ExtractedEndpoint(
    val method: String,
    val path: String,
    val baseUrl: String,
    val displayName: String,
    val queryParams: MutableList<QueryParamItem> = mutableListOf(),
    val headers: MutableMap<String, String> = linkedMapOf(),
    val sampleBody: String = ""
) {
    fun toApiStep(): ApiStep {
        val stepHeaders = LinkedHashMap(headers)
        if (sampleBody.isNotBlank() && stepHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            stepHeaders["Content-Type"] = "application/json"
        }
        return ApiStep(
            name = displayName,
            enabled = true,
            method = method,
            url = path,
            expectedStatus = "2xx",
            queryParams = queryParams.map { it.deepCopy() }.toMutableList(),
            headers = stepHeaders,
            body = sampleBody,
            extractRules = mutableListOf()
        )
    }
}

data class HttpRequestSnapshot(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

data class HttpExecutionResponse(
    val statusCode: Int?,
    val statusMessage: String,
    val durationMs: Long,
    val responseHeaders: Map<String, String>,
    val rawBody: String,
    val formattedBody: String,
    val isJson: Boolean,
    val errorMessage: String? = null,
    val cancelled: Boolean = false
)

data class StepExecutionResult(
    val stepId: String,
    val stepIndex: Int,
    val status: StepStatus,
    val requestSnapshot: HttpRequestSnapshot? = null,
    val response: HttpExecutionResponse? = null,
    val extractedVariables: Map<String, String> = emptyMap(),
    val extractionWarnings: List<String> = emptyList(),
    val failureReason: String? = null
)

data class ScenarioExecutionSummary(
    val scenarioStatus: ScenarioStatus,
    val stepResults: List<StepExecutionResult>,
    val finalContext: Map<String, String>,
    val totalDurationMs: Long,
    val abortReason: String? = null
)
