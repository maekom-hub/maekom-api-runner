package com.maekom.apirunner.engine

import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep

// [v0.1.2] 단건 API 스텝 및 전체 시나리오를 표준 cURL 명령어로 변환·복사하는 내보내기 유틸리티 추가
// 변경 의도: 터미널·동료 공유·장애 재현 시 활성 Params·동적/추출 변수가 반영된 즉시 실행 가능한 cURL 제공
object CurlExporter {

    /**
     * Builds a runnable POSIX `curl` command for a single [ApiStep], resolving built-in dynamic variables,
     * runtime context variables, secrets, and global variables.
     */
    fun exportStep(
        step: ApiStep,
        scenario: ApiScenario,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap()
    ): String {
        val resolvedBaseUrl = VariableResolver.resolve(
            template = scenario.baseUrl,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = scenario.globalVariables
        )
        val resolvedStepUrl = VariableResolver.resolve(
            template = step.url,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = scenario.globalVariables
        )
        val combinedUrl = VariableResolver.combineUrl(resolvedBaseUrl, resolvedStepUrl)
        val fullUrl = VariableResolver.appendQueryParams(
            url = combinedUrl,
            queryParams = step.queryParams,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = scenario.globalVariables
        )

        val mergedHeaders = VariableResolver.mergeHeaders(scenario.globalHeaders, step.headers)
        val resolvedHeaders = VariableResolver.resolveMap(
            source = mergedHeaders,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = scenario.globalVariables
        )
        val resolvedBody = VariableResolver.resolve(
            template = step.body,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = scenario.globalVariables
        )

        val method = step.method.trim().uppercase().ifEmpty { "GET" }
        val parts = mutableListOf<String>()

        val firstLine = buildString {
            append("curl")
            if (scenario.ignoreSsl) append(" -k")
            append(" -X ").append(method)
            append(" ").append(shellQuote(fullUrl))
        }
        parts.add(firstLine)

        for ((k, v) in resolvedHeaders) {
            parts.add("  -H ${shellQuote("$k: $v")}")
        }

        val includeBody = method !in setOf("GET", "HEAD") && resolvedBody.isNotBlank()
        if (includeBody) {
            parts.add("  -d ${shellQuote(resolvedBody)}")
        }

        return parts.joinToString(" \\\n")
    }

    /**
     * Builds a sequential multi-step shell script containing cURL commands for all steps in [scenario].
     */
    fun exportScenario(
        scenario: ApiScenario,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap()
    ): String {
        return buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("# Maekom API Runner — Scenario: ${scenario.name}")
            appendLine("# Base URL: ${scenario.baseUrl}")
            appendLine()

            for ((index, step) in scenario.steps.withIndex()) {
                val stepNo = index + 1
                val title = step.displayTitle()
                val method = step.method.trim().uppercase().ifEmpty { "GET" }

                if (!step.enabled) {
                    appendLine("# Step $stepNo: [$method] $title (DISABLED / 비활성 스텝)")
                    val cmd = exportStep(step, scenario, runtimeContext, secrets)
                    cmd.lines().forEach { appendLine("# $it") }
                    appendLine()
                } else {
                    appendLine("# Step $stepNo: [$method] $title")
                    appendLine(exportStep(step, scenario, runtimeContext, secrets))
                    appendLine()
                }
            }
        }.trimEnd()
    }

    internal fun shellQuote(value: String): String {
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }
}
