package com.maekom.apirunner.engine

import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep

// [v0.4.0] CurlManagerDialog 내보내기 미리보기 옵션(환경 BaseURL·공통 헤더·변수 치환 토글 및 Secret 마스킹 옵션) 지원
// 변경 의도: v0.3.1 빈 스텝 헤더 공통 헤더 폴백 병합 규칙을 그대로 유지하면서 원문/마스킹/치환 여부 선택 복사 지원
object CurlExporter {

    /**
     * Builds a runnable POSIX `curl` command for a single [ApiStep], resolving built-in dynamic variables,
     * runtime context variables, secrets, and global variables.
     */
    fun exportStep(
        step: ApiStep,
        scenario: ApiScenario,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        projectGlobalVariables: Map<String, String> = emptyMap(),
        resolveEnvironmentAndVars: Boolean = true,
        maskSecrets: Boolean = false
    ): String {
        val effectiveGlobals = linkedMapOf<String, String>().apply {
            putAll(scenario.globalVariables)
            putAll(projectGlobalVariables)
        }

        val fullUrl: String
        val finalHeaders: LinkedHashMap<String, String>
        val finalBody: String

        if (resolveEnvironmentAndVars) {
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
            fullUrl = VariableResolver.appendQueryParams(
                url = combinedUrl,
                queryParams = step.queryParams,
                runtimeContext = runtimeContext,
                secrets = secrets,
                globalVariables = effectiveGlobals
            )
            val mergedHeaders = VariableResolver.mergeHeaders(scenario.effectiveEnabledHeadersMap(), step.headers)
            finalHeaders = VariableResolver.resolveMap(
                source = mergedHeaders,
                runtimeContext = runtimeContext,
                secrets = secrets,
                globalVariables = effectiveGlobals
            )
            finalBody = VariableResolver.resolve(
                template = step.body,
                runtimeContext = runtimeContext,
                secrets = secrets,
                globalVariables = effectiveGlobals
            )
        } else {
            fullUrl = VariableResolver.appendQueryParams(
                url = step.url,
                queryParams = step.queryParams
            )
            finalHeaders = LinkedHashMap(step.headers)
            finalBody = step.body
        }

        val secretHeaderKeys = if (maskSecrets) {
            scenario.commonHeaders
                .filter { it.enabled && it.secret && it.key.trim().isNotEmpty() }
                .map { it.key.trim().lowercase() }
                .toSet()
        } else {
            emptySet()
        }
        val secretValues = if (maskSecrets) {
            buildList {
                secrets.values.filter { it.isNotEmpty() }.forEach { add(it) }
                scenario.commonHeaders
                    .filter { it.enabled && it.secret && it.value.isNotEmpty() }
                    .forEach { add(it.value) }
            }.distinct().sortedByDescending { it.length }
        } else {
            emptyList()
        }

        fun maskIfNeeded(raw: String): String {
            if (!maskSecrets || secretValues.isEmpty()) return raw
            var out = raw
            for (sec in secretValues) {
                if (out.contains(sec)) out = out.replace(sec, "****")
            }
            return out
        }

        val method = step.method.trim().uppercase().ifEmpty { "GET" }
        val parts = mutableListOf<String>()

        val firstLine = buildString {
            append("curl")
            if (scenario.ignoreSsl) append(" -k")
            append(" -X ").append(method)
            append(" ").append(shellQuote(maskIfNeeded(fullUrl)))
        }
        parts.add(firstLine)

        for ((k, v) in finalHeaders) {
            val displayVal = if (maskSecrets && k.trim().lowercase() in secretHeaderKeys) {
                "****"
            } else {
                maskIfNeeded(v)
            }
            parts.add("  -H ${shellQuote("$k: $displayVal")}")
        }

        val includeBody = method !in setOf("GET", "HEAD") && finalBody.isNotBlank()
        if (includeBody) {
            parts.add("  -d ${shellQuote(maskIfNeeded(finalBody))}")
        }

        return parts.joinToString(" \\\n")
    }

    /**
     * Builds a sequential multi-step shell script containing cURL commands for all steps in [scenario].
     */
    fun exportScenario(
        scenario: ApiScenario,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        projectGlobalVariables: Map<String, String> = emptyMap(),
        resolveEnvironmentAndVars: Boolean = true,
        maskSecrets: Boolean = false
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
                    val cmd = exportStep(
                        step = step,
                        scenario = scenario,
                        runtimeContext = runtimeContext,
                        secrets = secrets,
                        projectGlobalVariables = projectGlobalVariables,
                        resolveEnvironmentAndVars = resolveEnvironmentAndVars,
                        maskSecrets = maskSecrets
                    )
                    cmd.lines().forEach { appendLine("# $it") }
                    appendLine()
                } else {
                    appendLine("# Step $stepNo: [$method] $title")
                    appendLine(
                        exportStep(
                            step = step,
                            scenario = scenario,
                            runtimeContext = runtimeContext,
                            secrets = secrets,
                            projectGlobalVariables = projectGlobalVariables,
                            resolveEnvironmentAndVars = resolveEnvironmentAndVars,
                            maskSecrets = maskSecrets
                        )
                    )
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
