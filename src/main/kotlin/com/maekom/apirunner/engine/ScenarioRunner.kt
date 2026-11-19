package com.maekom.apirunner.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.jayway.jsonpath.JsonPath
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.HttpRequestSnapshot
import com.maekom.apirunner.model.ScenarioExecutionSummary
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepExecutionResult
import com.maekom.apirunner.model.StepStatus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface ScenarioRunListener {
    fun onStepStarted(stepIndex: Int, step: ApiStep) {}
    fun onStepCompleted(result: StepExecutionResult) {}
    fun onScenarioCompleted(summary: ScenarioExecutionSummary) {}
}

/**
 * Executes multi-step API scenarios with:
 * - Global baseUrl/headers merging (step headers take precedence)
 * - {{variable}} resolution (runtime context > secrets.local.json > global variables)
 * - Jayway JsonPath extraction into runtime context
 * - Fail-Fast abort policy (network error, status mismatch, required extraction failure, or cancellation)
 */
class ScenarioRunner(
    private val httpExecutor: HttpExecutor = HttpExecutor()
) {
    private val cancelled = AtomicBoolean(false)
    private val objectMapper = ObjectMapper()

    fun stop() {
        cancelled.set(true)
        httpExecutor.cancelCurrent()
    }

    fun isCancelled(): Boolean = cancelled.get()

    // [v0.1.3] 프로젝트 공용 전역 변수(projectGlobalVariables) 병합 및 활성 공통 헤더(effectiveEnabledHeadersMap) 반영
    fun runScenario(
        scenario: ApiScenario,
        secrets: Map<String, String> = emptyMap(),
        initialContext: Map<String, String> = emptyMap(),
        projectGlobalVariables: Map<String, String> = emptyMap(),
        listener: ScenarioRunListener? = null
    ): ScenarioExecutionSummary {
        cancelled.set(false)
        val startNanos = System.nanoTime()
        val runtimeContext = LinkedHashMap<String, String>(initialContext)
        val effectiveGlobals = mergeGlobalVariables(scenario.globalVariables, projectGlobalVariables)
        val results = mutableListOf<StepExecutionResult>()
        var aborted = false
        var abortReason: String? = null

        for ((index, step) in scenario.steps.withIndex()) {
            if (aborted) {
                val skipped = StepExecutionResult(
                    stepId = step.id,
                    stepIndex = index,
                    status = StepStatus.SKIPPED,
                    failureReason = "이전 스텝 실패로 인해 실행이 생략되었습니다."
                )
                results.add(skipped)
                listener?.onStepCompleted(skipped)
                continue
            }

            if (cancelled.get()) {
                aborted = true
                abortReason = "사용자에 의해 시나리오 실행이 중단되었습니다."
                val skipped = StepExecutionResult(
                    stepId = step.id,
                    stepIndex = index,
                    status = StepStatus.SKIPPED,
                    failureReason = abortReason
                )
                results.add(skipped)
                listener?.onStepCompleted(skipped)
                continue
            }

            if (!step.enabled) {
                val skipped = StepExecutionResult(
                    stepId = step.id,
                    stepIndex = index,
                    status = StepStatus.SKIPPED
                )
                results.add(skipped)
                listener?.onStepCompleted(skipped)
                continue
            }

            listener?.onStepStarted(index, step)
            val result = executeStepInternal(
                step = step,
                stepIndex = index,
                scenario = scenario,
                runtimeContext = runtimeContext,
                secrets = secrets,
                effectiveGlobals = effectiveGlobals
            )
            results.add(result)
            listener?.onStepCompleted(result)

            if (result.status == StepStatus.FAILED) {
                aborted = true
                abortReason = result.failureReason ?: "스텝 #${index + 1} 실행 실패"
            }
        }

        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        val finalStatus = if (aborted || cancelled.get()) ScenarioStatus.ABORTED else ScenarioStatus.COMPLETED
        val summary = ScenarioExecutionSummary(
            scenarioStatus = finalStatus,
            stepResults = results,
            finalContext = LinkedHashMap(runtimeContext),
            totalDurationMs = totalMs,
            abortReason = abortReason
        )
        listener?.onScenarioCompleted(summary)
        return summary
    }

    fun runSingleStep(
        step: ApiStep,
        stepIndex: Int,
        scenario: ApiScenario,
        secrets: Map<String, String> = emptyMap(),
        runtimeContext: MutableMap<String, String> = linkedMapOf(),
        projectGlobalVariables: Map<String, String> = emptyMap()
    ): StepExecutionResult {
        cancelled.set(false)
        val effectiveGlobals = mergeGlobalVariables(scenario.globalVariables, projectGlobalVariables)
        return executeStepInternal(
            step = step,
            stepIndex = stepIndex,
            scenario = scenario,
            runtimeContext = runtimeContext,
            secrets = secrets,
            effectiveGlobals = effectiveGlobals
        )
    }

    private fun mergeGlobalVariables(
        scenarioGlobals: Map<String, String>,
        projectGlobals: Map<String, String>
    ): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(scenarioGlobals)
        merged.putAll(projectGlobals)
        return merged
    }

    private fun executeStepInternal(
        step: ApiStep,
        stepIndex: Int,
        scenario: ApiScenario,
        runtimeContext: MutableMap<String, String>,
        secrets: Map<String, String>,
        effectiveGlobals: Map<String, String>
    ): StepExecutionResult {
        val resolvedBaseUrl = VariableResolver.resolve(
            template = scenario.baseUrl,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )
        val resolvedStepUrl = VariableResolver.resolve(
            template = step.url,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )
        val combinedUrl = VariableResolver.combineUrl(resolvedBaseUrl, resolvedStepUrl)
        val fullUrl = VariableResolver.appendQueryParams(
            url = combinedUrl,
            queryParams = step.queryParams,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )

        val mergedHeaders = VariableResolver.mergeHeaders(scenario.effectiveEnabledHeadersMap(), step.headers)
        val resolvedHeaders = VariableResolver.resolveMap(
            source = mergedHeaders,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )
        val resolvedBody = VariableResolver.resolve(
            template = step.body,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )

        val snapshot = HttpRequestSnapshot(
            method = step.method.trim().uppercase().ifEmpty { "GET" },
            url = fullUrl,
            headers = resolvedHeaders,
            body = resolvedBody
        )

        val response = httpExecutor.execute(snapshot, ignoreSsl = scenario.ignoreSsl)

        // 1. Cancelled or network/client error -> Fail-Fast
        if (response.cancelled || cancelled.get()) {
            return StepExecutionResult(
                stepId = step.id,
                stepIndex = stepIndex,
                status = StepStatus.FAILED,
                requestSnapshot = snapshot,
                response = response,
                failureReason = response.errorMessage ?: "사용자에 의해 실행이 중단되었습니다."
            )
        }

        if (response.errorMessage != null || response.statusCode == null) {
            return StepExecutionResult(
                stepId = step.id,
                stepIndex = stepIndex,
                status = StepStatus.FAILED,
                requestSnapshot = snapshot,
                response = response,
                failureReason = response.errorMessage ?: "네트워크 오류로 응답을 받지 못했습니다."
            )
        }

        // 2. Expected HTTP Status validation -> Fail-Fast
        val expectedExpr = step.expectedStatus.trim().ifEmpty { "2xx" }
        if (!matchesExpectedStatus(response.statusCode, expectedExpr)) {
            return StepExecutionResult(
                stepId = step.id,
                stepIndex = stepIndex,
                status = StepStatus.FAILED,
                requestSnapshot = snapshot,
                response = response,
                failureReason = "기대 상태 코드 불일치: 기대값 [$expectedExpr], 실제 응답 [${response.statusCode} ${response.statusMessage}]"
            )
        }

        // 3. JsonPath variable extraction -> Fail-Fast if required == true
        val extracted = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()

        for (rule in step.extractRules) {
            val varName = rule.varName.trim()
            val jsonPath = rule.jsonPath.trim()
            if (varName.isEmpty() || jsonPath.isEmpty()) continue

            val extractOutcome = extractWithJsonPath(response.rawBody, rule)
            if (extractOutcome.isSuccess) {
                val value = extractOutcome.getOrThrow()
                extracted[varName] = value
                runtimeContext[varName] = value
            } else {
                val reason = extractOutcome.exceptionOrNull()?.message ?: "알 수 없는 추출 오류"
                if (rule.required) {
                    return StepExecutionResult(
                        stepId = step.id,
                        stepIndex = stepIndex,
                        status = StepStatus.FAILED,
                        requestSnapshot = snapshot,
                        response = response,
                        extractedVariables = extracted,
                        extractionWarnings = warnings,
                        failureReason = "필수 응답 변수 추출 실패 (var='$varName', jsonPath='$jsonPath'): $reason"
                    )
                } else {
                    warnings.add("선택 변수 추출 생략 (var='$varName', jsonPath='$jsonPath'): $reason")
                }
            }
        }

        return StepExecutionResult(
            stepId = step.id,
            stepIndex = stepIndex,
            status = StepStatus.SUCCESS,
            requestSnapshot = snapshot,
            response = response,
            extractedVariables = extracted,
            extractionWarnings = warnings
        )
    }

    private fun extractWithJsonPath(rawBody: String, rule: ExtractRule): Result<String> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalStateException("응답 본문이 비어 있어 JsonPath를 평가할 수 없습니다."))
        }
        return try {
            val extractedValue: Any? = JsonPath.read(trimmed, rule.jsonPath)
            if (extractedValue == null) {
                Result.failure(IllegalStateException("경로 '${rule.jsonPath}'의 값이 null입니다."))
            } else {
                val stringValue = when (extractedValue) {
                    is String -> extractedValue
                    is Number, is Boolean -> extractedValue.toString()
                    else -> objectMapper.writeValueAsString(extractedValue)
                }
                Result.success(stringValue)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
        }
    }

    companion object {
        fun matchesExpectedStatus(actualStatus: Int, expectedExpr: String): Boolean {
            val normalized = expectedExpr.trim()
            if (normalized.isEmpty()) {
                return actualStatus in 200..299
            }
            val tokens = normalized.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return actualStatus in 200..299

            for (token in tokens) {
                when {
                    token.equals("2xx", ignoreCase = true) -> if (actualStatus in 200..299) return true
                    token.equals("1xx", ignoreCase = true) -> if (actualStatus in 100..199) return true
                    token.equals("3xx", ignoreCase = true) -> if (actualStatus in 300..399) return true
                    token.equals("4xx", ignoreCase = true) -> if (actualStatus in 400..499) return true
                    token.equals("5xx", ignoreCase = true) -> if (actualStatus in 500..599) return true
                    token.contains("..") -> {
                        val parts = token.split("..")
                        val start = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val end = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (start != null && end != null && actualStatus in start..end) return true
                    }
                    token.contains("-") -> {
                        val parts = token.split("-")
                        val start = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val end = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (start != null && end != null && actualStatus in start..end) return true
                    }
                    else -> {
                        val exact = token.toIntOrNull()
                        if (exact != null && actualStatus == exact) return true
                    }
                }
            }
            return false
        }
    }
}
