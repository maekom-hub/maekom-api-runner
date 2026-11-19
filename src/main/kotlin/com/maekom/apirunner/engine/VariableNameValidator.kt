package com.maekom.apirunner.engine

import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.GlobalVariableItem

// [v0.1.3] 전역 변수·응답 추출 변수명 중복 검증 및 추천 변수명 자동 생성기(VariableNameValidator) 구현
// 변경 의도: 전역 변수, 시나리오 내 모든 스텝 추출 변수, 내장 예약 접두사($...) 간 이름 충돌을 사전 방지하고 안전한 추천명을 자동 제안

enum class VariableConflictType {
    NONE,
    RESERVED_DYNAMIC_PREFIX,
    GLOBAL_VARIABLE,
    SCENARIO_EXTRACT_RULE
}

data class VariableValidationResult(
    val isValid: Boolean,
    val conflictType: VariableConflictType = VariableConflictType.NONE,
    val conflictSourceLabel: String = "",
    val recommendedName: String = ""
)

object VariableNameValidator {

    /**
     * Validates [candidateName] against:
     * 1. Built-in reserved dynamic prefix (`$...`)
     * 2. Registered project global variables ([globalVariables])
     * 3. All extract rules defined across all steps in [scenario]
     *
     * Pass [ignoreGlobalKey] when editing an existing global variable, or
     * [ignoreStepIndex] + [ignoreRuleIndex] when editing an existing extract rule.
     */
    fun validate(
        candidateName: String,
        globalVariables: List<GlobalVariableItem>,
        scenario: ApiScenario,
        ignoreGlobalKey: String? = null,
        ignoreStepIndex: Int = -1,
        ignoreRuleIndex: Int = -1,
        jsonPathForSuggestion: String? = null
    ): VariableValidationResult {
        val trimmed = candidateName.trim()
        if (trimmed.isEmpty()) {
            return VariableValidationResult(isValid = true)
        }

        val allExistingNames = collectAllExistingNames(
            globalVariables = globalVariables,
            scenario = scenario,
            ignoreGlobalKey = ignoreGlobalKey,
            ignoreStepIndex = ignoreStepIndex,
            ignoreRuleIndex = ignoreRuleIndex
        )

        // 1. Built-in dynamic variable reserved prefix ($...)
        if (trimmed.startsWith("$")) {
            val base = trimmed.trimStart('$').ifEmpty { "customVar" }
            val suggested = generateRecommendedName(
                baseName = base,
                existingNames = allExistingNames,
                parentJsonKey = extractParentJsonKey(jsonPathForSuggestion)
            )
            return VariableValidationResult(
                isValid = false,
                conflictType = VariableConflictType.RESERVED_DYNAMIC_PREFIX,
                conflictSourceLabel = I18nManager.t("validator.source.reserved"),
                recommendedName = suggested
            )
        }

        // 2. Registered Global Variables
        val conflictsWithGlobal = globalVariables.any { item ->
            val key = item.key.trim()
            key.isNotEmpty() && key == trimmed && key != ignoreGlobalKey
        }
        if (conflictsWithGlobal) {
            val suggested = generateRecommendedName(
                baseName = trimmed,
                existingNames = allExistingNames,
                parentJsonKey = extractParentJsonKey(jsonPathForSuggestion)
            )
            return VariableValidationResult(
                isValid = false,
                conflictType = VariableConflictType.GLOBAL_VARIABLE,
                conflictSourceLabel = I18nManager.t("validator.source.global"),
                recommendedName = suggested
            )
        }

        // 3. Extract Rules across all steps in the current scenario
        for ((sIdx, step) in scenario.steps.withIndex()) {
            for ((rIdx, rule) in step.extractRules.withIndex()) {
                if (sIdx == ignoreStepIndex && rIdx == ignoreRuleIndex) continue
                val existingVar = rule.varName.trim()
                if (existingVar.isNotEmpty() && existingVar == trimmed) {
                    val suggested = generateRecommendedName(
                        baseName = trimmed,
                        existingNames = allExistingNames,
                        parentJsonKey = extractParentJsonKey(jsonPathForSuggestion)
                    )
                    return VariableValidationResult(
                        isValid = false,
                        conflictType = VariableConflictType.SCENARIO_EXTRACT_RULE,
                        conflictSourceLabel = I18nManager.t("validator.source.step", sIdx + 1),
                        recommendedName = suggested
                    )
                }
            }
        }

        return VariableValidationResult(isValid = true, recommendedName = trimmed)
    }

    /**
     * Generates a guaranteed unique recommended variable name:
     * 1) If [parentJsonKey] is present (e.g. `deal` for `$.deal.apiDealNumber`), tries `<parent>_<base>` first
     * 2) Otherwise appends incrementing suffix `_2`, `_3`, ... until unused
     */
    fun generateRecommendedName(
        baseName: String,
        existingNames: Set<String>,
        parentJsonKey: String? = null
    ): String {
        val cleanBase = baseName.trim().trimStart('$').ifEmpty { "var" }

        val cleanParent = parentJsonKey
            ?.replace(Regex("""[^a-zA-Z0-9_]"""), "")
            ?.trim('_')
            ?.takeIf { it.isNotEmpty() && !it.equals("data", ignoreCase = true) && !it.equals(cleanBase, ignoreCase = true) }

        if (cleanParent != null) {
            val combined = "${cleanParent}_${cleanBase}"
            if (combined !in existingNames && !combined.startsWith("$")) {
                return combined
            }
        }

        if (cleanBase !in existingNames && !cleanBase.startsWith("$")) {
            return cleanBase
        }

        var suffix = 2
        while (true) {
            val candidate = "${cleanBase}_$suffix"
            if (candidate !in existingNames) {
                return candidate
            }
            suffix++
        }
    }

    /**
     * Extracts the parent JSON object key from a JsonPath such as `$.deal.apiDealNumber` -> `"deal"`,
     * or `$.orders[0].dealNo` -> `"orders"`.
     */
    fun extractParentJsonKey(jsonPath: String?): String? {
        if (jsonPath.isNullOrBlank()) return null
        val cleaned = jsonPath.trim().removePrefix("$").removePrefix(".")
        if (cleaned.isEmpty()) return null
        val segments = cleaned
            .split('.')
            .map { seg -> seg.substringBefore('[').trim('\'', '"', ' ') }
            .filter { it.isNotEmpty() }
        return if (segments.size >= 2) segments[segments.size - 2] else null
    }

    fun collectAllExistingNames(
        globalVariables: List<GlobalVariableItem>,
        scenario: ApiScenario,
        ignoreGlobalKey: String? = null,
        ignoreStepIndex: Int = -1,
        ignoreRuleIndex: Int = -1
    ): Set<String> {
        val names = mutableSetOf<String>()
        for (item in globalVariables) {
            val key = item.key.trim()
            if (key.isNotEmpty() && key != ignoreGlobalKey) {
                names.add(key)
            }
        }
        for ((sIdx, step) in scenario.steps.withIndex()) {
            for ((rIdx, rule) in step.extractRules.withIndex()) {
                if (sIdx == ignoreStepIndex && rIdx == ignoreRuleIndex) continue
                val v = rule.varName.trim()
                if (v.isNotEmpty()) {
                    names.add(v)
                }
            }
        }
        return names
    }
}
