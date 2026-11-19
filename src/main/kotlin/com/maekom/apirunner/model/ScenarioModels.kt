package com.maekom.apirunner.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

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
        val label = name.takeIf { it.isNotBlank() } ?: url.ifBlank { "/" }
        return label
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiScenario(
    var name: String = "default",
    var baseUrl: String = "http://localhost:8080",
    var ignoreSsl: Boolean = false,
    var globalHeaders: MutableMap<String, String> = linkedMapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json"
    ),
    var globalVariables: MutableMap<String, String> = linkedMapOf(),
    var steps: MutableList<ApiStep> = mutableListOf()
) {
    fun deepCopy(newName: String = name): ApiScenario = ApiScenario(
        name = newName,
        baseUrl = baseUrl,
        ignoreSsl = ignoreSsl,
        globalHeaders = LinkedHashMap(globalHeaders),
        globalVariables = LinkedHashMap(globalVariables),
        steps = steps.map { it.deepCopy() }.toMutableList()
    )
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
