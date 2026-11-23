package com.maekom.apirunner.engine

import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep

// [v0.4.3] 시나리오 변수 탭 ↔ 스텝별 응답 변수 추출 규칙 양방향 현행화 엔진(ScenarioVariableSyncEngine) 신규 구현
// 변경 의도: 기존 .maekom/scenarios/**/*.json 스키마를 100% 유지하면서, (stepIndex, extractorIndex) 고유 참조 기반으로
//          시나리오 정적 변수와 스텝 추출 변수를 통합 표시하고 이름 변경(참조 일괄 치환)·값 Override·삭제를 안전하게 동기화

enum class ScenarioVarOrigin {
    SCENARIO_STATIC,
    STEP_EXTRACTOR
}

data class ScenarioVarRow(
    val origin: ScenarioVarOrigin,
    val stepIndex: Int = -1,
    val extractorIndex: Int = -1,
    val originalKey: String,
    var key: String,
    var value: String,
    val sourceLabel: String,
    val initialValue: String = value
) {
    fun deepCopy(): ScenarioVarRow = copy()
}

data class ScenarioVarSyncResult(
    val scenarioModified: Boolean,
    val runtimeContextModified: Boolean,
    val extractorRulesChanged: Boolean,
    val replacedReferenceCount: Int
)

object ScenarioVariableSyncEngine {

    /**
     * Formats step origin label as `Step 1 · [Alias] POST /path` or `Step 1 · POST /path`.
     */
    fun formatStepSourceLabel(stepIndex: Int, step: ApiStep): String {
        val stepNo = stepIndex + 1
        val method = step.method.trim().uppercase().ifEmpty { "GET" }
        val path = step.url.trim().ifEmpty { "/" }
        val alias = step.alias.trim()
        return if (alias.isNotEmpty()) {
            "Step $stepNo · [$alias] $method $path"
        } else {
            "Step $stepNo · $method $path"
        }
    }

    /**
     * Builds the unified table rows combining static `scenario.globalVariables` and all step `extractRules`.
     * Static keys already owned by a step extractor are merged into that extractor row as its default value
     * so duplicate rows are never displayed.
     */
    fun buildUnifiedRows(
        scenario: ApiScenario,
        runtimeContext: Map<String, String>,
        staticSourceLabel: String
    ): MutableList<ScenarioVarRow> {
        val rows = mutableListOf<ScenarioVarRow>()

        val extractorKeysLower = scenario.steps
            .flatMap { it.extractRules }
            .map { it.varName.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 1) Static scenario variables (excluding keys represented as step extractor rows)
        for ((k, v) in scenario.globalVariables) {
            val trimmed = k.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase() in extractorKeysLower) continue
            rows.add(
                ScenarioVarRow(
                    origin = ScenarioVarOrigin.SCENARIO_STATIC,
                    originalKey = trimmed,
                    key = trimmed,
                    value = v,
                    sourceLabel = staticSourceLabel,
                    initialValue = v
                )
            )
        }

        // 2) Step extractor variables bound by exact (stepIndex, extractorIndex)
        for ((stepIdx, step) in scenario.steps.withIndex()) {
            val sourceLabel = formatStepSourceLabel(stepIdx, step)
            for ((ruleIdx, rule) in step.extractRules.withIndex()) {
                val varName = rule.varName.trim()
                if (varName.isEmpty()) continue
                val currentValue = runtimeContext[varName]
                    ?: scenario.globalVariables[varName]
                    ?: ""
                rows.add(
                    ScenarioVarRow(
                        origin = ScenarioVarOrigin.STEP_EXTRACTOR,
                        stepIndex = stepIdx,
                        extractorIndex = ruleIdx,
                        originalKey = varName,
                        key = varName,
                        value = currentValue,
                        sourceLabel = sourceLabel,
                        initialValue = currentValue
                    )
                )
            }
        }

        return rows
    }

    /**
     * Counts all `{{varName}}` references across the current scenario (BaseURL, common headers, and all steps).
     */
    fun countVariableReferences(scenario: ApiScenario, varName: String): Int {
        val trimmed = varName.trim()
        if (trimmed.isEmpty()) return 0
        val regex = buildPlaceholderRegex(trimmed)
        var count = 0

        fun countIn(text: String?) {
            if (text.isNullOrEmpty() || !text.contains("{{")) return
            count += regex.findAll(text).count()
        }

        countIn(scenario.baseUrl)
        for (h in scenario.commonHeaders) {
            countIn(h.key)
            countIn(h.value)
        }
        for ((k, v) in scenario.globalHeaders) {
            countIn(k)
            countIn(v)
        }
        for (step in scenario.steps) {
            countIn(step.url)
            for (p in step.queryParams) {
                countIn(p.key)
                countIn(p.value)
            }
            for ((k, v) in step.headers) {
                countIn(k)
                countIn(v)
            }
            countIn(step.body)
            for (a in step.assertions) {
                countIn(a.expectedValue)
            }
        }
        return count
    }

    /**
     * Replaces all `{{oldVarName}}` references across the current scenario with `{{newVarName}}`.
     */
    fun replaceVariableReferences(scenario: ApiScenario, oldVarName: String, newVarName: String): Int {
        val oldTrimmed = oldVarName.trim()
        val newTrimmed = newVarName.trim()
        if (oldTrimmed.isEmpty() || newTrimmed.isEmpty() || oldTrimmed == newTrimmed) return 0
        val regex = buildPlaceholderRegex(oldTrimmed)
        var replaced = 0

        fun replaceIn(text: String): String {
            if (text.isEmpty() || !text.contains("{{")) return text
            return regex.replace(text) {
                replaced++
                "{{$newTrimmed}}"
            }
        }

        scenario.baseUrl = replaceIn(scenario.baseUrl)
        for (h in scenario.commonHeaders) {
            h.key = replaceIn(h.key)
            h.value = replaceIn(h.value)
        }
        val newGlobalHeaders = linkedMapOf<String, String>()
        for ((k, v) in scenario.globalHeaders) {
            newGlobalHeaders[replaceIn(k)] = replaceIn(v)
        }
        scenario.globalHeaders = newGlobalHeaders

        for (step in scenario.steps) {
            step.url = replaceIn(step.url)
            for (p in step.queryParams) {
                p.key = replaceIn(p.key)
                p.value = replaceIn(p.value)
            }
            val newStepHeaders = linkedMapOf<String, String>()
            for ((k, v) in step.headers) {
                newStepHeaders[replaceIn(k)] = replaceIn(v)
            }
            step.headers = newStepHeaders
            step.body = replaceIn(step.body)
            for (a in step.assertions) {
                a.expectedValue = replaceIn(a.expectedValue)
            }
        }
        return replaced
    }

    /**
     * Synchronizes edited rows & deleted extractor references back into [scenario] and [runtimeContext].
     * Strictly scoped to the current scenario and its active runtime session.
     */
    fun applySync(
        scenario: ApiScenario,
        runtimeContext: MutableMap<String, String>,
        editedRows: List<ScenarioVarRow>,
        deletedExtractorRefs: Set<Pair<Int, Int>>,
        replaceReferencesForRenames: Boolean
    ): ScenarioVarSyncResult {
        var scenarioModified = false
        var runtimeContextModified = false
        var extractorRulesChanged = false
        var replacedReferenceCount = 0

        val oldGlobalVariables = LinkedHashMap(scenario.globalVariables)
        val newGlobalVariables = linkedMapOf<String, String>()
        val renamePairs = mutableListOf<Pair<String, String>>()

        // 1) Process static scenario variable rows
        for (row in editedRows) {
            if (row.origin != ScenarioVarOrigin.SCENARIO_STATIC) continue
            val newKey = row.key.trim()
            if (newKey.isEmpty()) continue
            newGlobalVariables[newKey] = row.value
            if (row.originalKey.isNotEmpty() && row.originalKey != newKey) {
                renamePairs.add(row.originalKey to newKey)
            }
        }

        // 2) Process surviving step extractor rows by exact (stepIndex, extractorIndex) BEFORE deletions
        val survivingExtractorKeys = mutableSetOf<String>()
        for (row in editedRows) {
            if (row.origin != ScenarioVarOrigin.STEP_EXTRACTOR) continue
            val newKey = row.key.trim()
            if (newKey.isEmpty()) continue
            survivingExtractorKeys.add(newKey)

            val step = scenario.steps.getOrNull(row.stepIndex)
            val rule = step?.extractRules?.getOrNull(row.extractorIndex)
            if (rule != null && rule.varName.trim() != newKey) {
                val oldKey = rule.varName.trim()
                rule.varName = newKey
                extractorRulesChanged = true
                scenarioModified = true
                if (oldKey.isNotEmpty() && oldKey != newKey) {
                    renamePairs.add(oldKey to newKey)
                    val migratedRuntime = runtimeContext.remove(oldKey)
                    if (migratedRuntime != null) {
                        runtimeContext[newKey] = migratedRuntime
                        runtimeContextModified = true
                    }
                }
            }

            // Propagate value edits to BOTH runtime override context and scenario variable default
            if (row.value != row.initialValue) {
                if (row.value.isEmpty()) {
                    if (runtimeContext.remove(newKey) != null) {
                        runtimeContextModified = true
                    }
                    // Do not keep empty default in globalVariables
                } else {
                    runtimeContext[newKey] = row.value
                    runtimeContextModified = true
                    newGlobalVariables[newKey] = row.value
                }
                scenarioModified = true
            } else {
                // Preserve existing scenario default value if any (migrating key if renamed)
                val existingDefault = oldGlobalVariables[row.originalKey]
                if (existingDefault != null && existingDefault.isNotEmpty()) {
                    newGlobalVariables[newKey] = existingDefault
                }
            }
        }

        // 3) Process deleted step extractor rules in descending extractorIndex order per step
        val deletedByStep = deletedExtractorRefs.groupBy({ it.first }, { it.second })
        for ((stepIdx, ruleIndices) in deletedByStep) {
            val step = scenario.steps.getOrNull(stepIdx) ?: continue
            for (ruleIdx in ruleIndices.distinct().sortedDescending()) {
                if (ruleIdx in step.extractRules.indices) {
                    val removed = step.extractRules.removeAt(ruleIdx)
                    extractorRulesChanged = true
                    scenarioModified = true
                    val removedKey = removed.varName.trim()
                    if (removedKey.isNotEmpty() && removedKey !in survivingExtractorKeys) {
                        if (runtimeContext.remove(removedKey) != null) {
                            runtimeContextModified = true
                        }
                    }
                }
            }
        }

        // 4) Update scenario.globalVariables if changed
        if (newGlobalVariables != oldGlobalVariables) {
            scenario.globalVariables = newGlobalVariables
            scenarioModified = true
        }

        // 5) Optional bulk reference replacement across the current scenario
        if (replaceReferencesForRenames && renamePairs.isNotEmpty()) {
            for ((oldKey, newKey) in renamePairs.distinct()) {
                val count = replaceVariableReferences(scenario, oldKey, newKey)
                if (count > 0) {
                    replacedReferenceCount += count
                    scenarioModified = true
                }
            }
        }

        return ScenarioVarSyncResult(
            scenarioModified = scenarioModified,
            runtimeContextModified = runtimeContextModified,
            extractorRulesChanged = extractorRulesChanged,
            replacedReferenceCount = replacedReferenceCount
        )
    }

    private fun buildPlaceholderRegex(varName: String): Regex =
        Regex("""\{\{\s*${Regex.escape(varName)}\s*}}""")
}
