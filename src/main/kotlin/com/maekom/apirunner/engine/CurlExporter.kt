package com.maekom.apirunner.engine

import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// [v0.4.5] cURL 내보내기 Secret 마스킹(****) 완벽 적용 (개별 스텝 Secret 헤더, 활성 공통 헤더 Secret 및 빈 값 폴백,
//          로컬 시크릿 {{secretKey}}의 URL/Query/Header/Body 치환 및 URL 인코딩 값까지 예외 없이 마스킹)
// 변경 의도: 단일 공통 생성 경로에서 대소문자 무관(equalsIgnoreCase) Secret 헤더 판별과 시크릿 치환·후처리 마스킹을 일관 보장
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
        maskSecrets: Boolean = false,
        includeEmptyHeaders: Boolean = false,
        stepSecretHeaderKeys: Set<String> = emptySet(),
        extraSecretValues: Set<String> = emptySet()
    ): String {
        val effectiveGlobals = linkedMapOf<String, String>().apply {
            putAll(scenario.globalVariables)
            putAll(projectGlobalVariables)
        }
        // [v0.4.5] 마스킹 활성화 시 로컬 시크릿({{secretKey}})을 치환 단계부터 ****로 대체하여 URL/Query/Header/Body 어디서든 누출 차단
        val effectiveSecrets = if (maskSecrets) {
            secrets.mapValues { (_, v) -> if (v.isNotEmpty()) "****" else "" }
        } else {
            secrets
        }

        val fullUrl: String
        val finalHeaders: LinkedHashMap<String, String>
        val finalBody: String

        if (resolveEnvironmentAndVars) {
            val resolvedBaseUrl = VariableResolver.resolve(
                template = scenario.baseUrl,
                runtimeContext = runtimeContext,
                secrets = effectiveSecrets,
                globalVariables = effectiveGlobals
            )
            val resolvedStepUrl = VariableResolver.resolve(
                template = step.url,
                runtimeContext = runtimeContext,
                secrets = effectiveSecrets,
                globalVariables = effectiveGlobals
            )
            val combinedUrl = VariableResolver.combineUrl(resolvedBaseUrl, resolvedStepUrl)
            fullUrl = VariableResolver.appendQueryParams(
                url = combinedUrl,
                queryParams = step.queryParams,
                runtimeContext = runtimeContext,
                secrets = effectiveSecrets,
                globalVariables = effectiveGlobals,
                skipBlankValues = true
            )
            val mergedHeaders = VariableResolver.mergeHeaders(scenario.effectiveEnabledHeadersMap(), step.headers)
            finalHeaders = VariableResolver.resolveMap(
                source = mergedHeaders,
                runtimeContext = runtimeContext,
                secrets = effectiveSecrets,
                globalVariables = effectiveGlobals
            )
            finalBody = VariableResolver.resolve(
                template = step.body,
                runtimeContext = runtimeContext,
                secrets = effectiveSecrets,
                globalVariables = effectiveGlobals
            )
        } else {
            fullUrl = VariableResolver.appendQueryParams(
                url = step.url,
                queryParams = step.queryParams,
                skipBlankValues = true
            )
            finalHeaders = LinkedHashMap(step.headers)
            finalBody = step.body
        }

        // [v0.4.5] 대소문자 무관(lowercase) Secret 헤더 키 집합: 활성 공통 헤더 Secret + 개별 스텝 Secret 헤더 키
        val secretHeaderKeys = if (maskSecrets) {
            buildSet {
                scenario.commonHeaders
                    .filter { it.enabled && it.secret && it.key.trim().isNotEmpty() }
                    .forEach { add(it.key.trim().lowercase()) }
                stepSecretHeaderKeys
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .forEach { add(it) }
            }
        } else {
            emptySet()
        }

        // [v0.4.5] 잔여 민감 값 후처리 마스킹 목록(원본 + URL 인코딩 + 변수 치환된 Secret 공통 헤더 값 포함)
        val secretValues = if (maskSecrets) {
            buildList {
                secrets.values.filter { it.isNotEmpty() }.forEach { raw ->
                    add(raw)
                    add(URLEncoder.encode(raw, StandardCharsets.UTF_8))
                }
                extraSecretValues.filter { it.isNotEmpty() }.forEach { raw ->
                    add(raw)
                    add(URLEncoder.encode(raw, StandardCharsets.UTF_8))
                }
                scenario.commonHeaders
                    .filter { it.enabled && it.secret && it.value.isNotEmpty() }
                    .forEach { item ->
                        add(item.value)
                        val resolved = VariableResolver.resolve(
                            template = item.value,
                            runtimeContext = runtimeContext,
                            secrets = secrets,
                            globalVariables = effectiveGlobals
                        )
                        if (resolved.isNotEmpty()) {
                            add(resolved)
                            add(URLEncoder.encode(resolved, StandardCharsets.UTF_8))
                        }
                    }
            }.distinct().sortedByDescending { it.length }
        } else {
            emptyList()
        }

        fun maskIfNeeded(raw: String): String {
            if (!maskSecrets || secretValues.isEmpty()) return raw
            var out = raw
            for (sec in secretValues) {
                if (sec.isNotEmpty() && out.contains(sec)) out = out.replace(sec, "****")
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
            val isBlankVal = v.isBlank()
            if (isBlankVal && !includeEmptyHeaders) {
                continue
            }
            val displayVal = when {
                isBlankVal -> ""
                maskSecrets && k.trim().lowercase() in secretHeaderKeys -> "****"
                else -> maskIfNeeded(v)
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
        maskSecrets: Boolean = false,
        includeEmptyHeaders: Boolean = false,
        extraSecretValues: Set<String> = emptySet()
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
                        maskSecrets = maskSecrets,
                        includeEmptyHeaders = includeEmptyHeaders,
                        extraSecretValues = extraSecretValues
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
                            maskSecrets = maskSecrets,
                            includeEmptyHeaders = includeEmptyHeaders,
                            extraSecretValues = extraSecretValues
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
