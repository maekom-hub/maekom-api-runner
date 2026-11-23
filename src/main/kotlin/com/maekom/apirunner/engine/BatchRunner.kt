package com.maekom.apirunner.engine

import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepExecutionResult
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.storage.ScenarioFileRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// [v0.4.1] 폴더 단위 일괄 회귀 실행(BatchRunner) 엔진 구현
// 변경 의도: 시나리오 파일을 읽기 전용으로 로드해 시나리오 간 추출 변수 세션을 완전히 격리(Isolate)하고,
//          공통 헤더 폴백·delayMs·No-Code Assertion 파이프라인을 100% 동일하게 수행하며 취소·리포트를 지원

data class BatchStepSummary(
    val stepIndex: Int,
    val stepTitle: String,
    val method: String,
    val url: String,
    val status: StepStatus,
    val httpStatusText: String,
    val durationMs: Long,
    val detailMessage: String
)

data class BatchScenarioRunResult(
    val relativePath: String,
    val displayPath: String,
    val scenarioStatus: ScenarioStatus,
    val totalDurationMs: Long,
    val stepSummaries: List<BatchStepSummary>,
    val firstFailedStepIndex: Int?,
    val failureSummary: String?
) {
    val passed: Boolean get() = scenarioStatus == ScenarioStatus.COMPLETED
    val succeededSteps: Int get() = stepSummaries.count { it.status == StepStatus.SUCCESS }
    val totalSteps: Int get() = stepSummaries.size
}

data class BatchExecutionReport(
    val folderPath: String,
    val folderDisplayName: String,
    val environmentName: String,
    val effectiveBaseUrl: String,
    val totalDurationMs: Long,
    val cancelled: Boolean,
    val scenarioResults: List<BatchScenarioRunResult>
) {
    val totalCount: Int get() = scenarioResults.size
    val passedCount: Int get() = scenarioResults.count { it.passed }
    val failedCount: Int get() = scenarioResults.count { !it.passed }
}

class BatchRunner {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeScenarioRunner: ScenarioRunner? = null

    fun stop() {
        cancelled.set(true)
        activeScenarioRunner?.stop()
    }

    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Runs [scenarios] (relativePath to read-only loaded ApiScenario) sequentially with completely isolated
     * runtime contexts per scenario. Never writes to or mutates disk files.
     */
    fun runFolderScenarios(
        folderPath: String,
        environmentName: String,
        activeBaseUrl: String,
        scenarios: List<Pair<String, ApiScenario>>,
        secrets: Map<String, String> = emptyMap(),
        projectGlobalVariables: Map<String, String> = emptyMap(),
        isCancelledCheck: () -> Boolean = { false },
        onScenarioProgress: (index: Int, total: Int, displayPath: String) -> Unit = { _, _, _ -> }
    ): BatchExecutionReport {
        cancelled.set(false)
        val batchStartNanos = System.nanoTime()
        val results = mutableListOf<BatchScenarioRunResult>()
        val total = scenarios.size

        for ((idx, pair) in scenarios.withIndex()) {
            if (cancelled.get() || isCancelledCheck()) {
                cancelled.set(true)
                break
            }

            val (relPath, rawScenario) = pair
            val displayPath = ScenarioFileRepository.formatDisplayPath(relPath)
            onScenarioProgress(idx, total, displayPath)

            // Deep copy so disk/in-memory objects are never mutated; apply active environment BaseURL
            val isolatedScenario = rawScenario.deepCopy()
            if (activeBaseUrl.isNotBlank()) {
                isolatedScenario.baseUrl = activeBaseUrl
            }

            val runner = ScenarioRunner()
            activeScenarioRunner = runner

            val summary = runner.runScenario(
                scenario = isolatedScenario,
                secrets = secrets,
                initialContext = emptyMap(), // Strict session isolation between scenarios
                projectGlobalVariables = projectGlobalVariables,
                listener = object : ScenarioRunListener {
                    override fun onStepStarted(stepIndex: Int, step: ApiStep) {
                        if (cancelled.get() || isCancelledCheck()) {
                            cancelled.set(true)
                            runner.stop()
                        }
                    }
                }
            )
            activeScenarioRunner = null

            val stepSummaries = buildStepSummaries(isolatedScenario.steps, summary.stepResults)
            val firstFailed = summary.stepResults.firstOrNull { it.status == StepStatus.FAILED }
            results.add(
                BatchScenarioRunResult(
                    relativePath = relPath,
                    displayPath = displayPath,
                    scenarioStatus = summary.scenarioStatus,
                    totalDurationMs = summary.totalDurationMs,
                    stepSummaries = stepSummaries,
                    firstFailedStepIndex = firstFailed?.stepIndex,
                    failureSummary = firstFailed?.failureReason ?: summary.abortReason
                )
            )

            if (cancelled.get() || isCancelledCheck()) {
                cancelled.set(true)
                break
            }
        }

        val totalBatchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStartNanos)
        return BatchExecutionReport(
            folderPath = folderPath,
            folderDisplayName = ScenarioFileRepository.formatDisplayPath(folderPath).ifBlank { folderPath },
            environmentName = environmentName,
            effectiveBaseUrl = activeBaseUrl,
            totalDurationMs = totalBatchMs,
            cancelled = cancelled.get(),
            scenarioResults = results
        )
    }

    private fun buildStepSummaries(
        steps: List<ApiStep>,
        stepResults: List<StepExecutionResult>
    ): List<BatchStepSummary> {
        val resultByIndex = stepResults.associateBy { it.stepIndex }
        return steps.mapIndexed { idx, step ->
            val res = resultByIndex[idx]
            val resp = res?.response
            val httpText = when {
                resp?.statusCode != null -> "${resp.statusCode} ${resp.statusMessage}".trim()
                res?.status == StepStatus.SKIPPED -> "SKIPPED"
                else -> "-"
            }
            val detail = buildString {
                if (!res?.failureReason.isNullOrBlank()) {
                    append(res!!.failureReason)
                } else if (res != null && res.assertionResults.isNotEmpty()) {
                    val passCount = res.assertionResults.count { it.passed }
                    append("Assertions $passCount/${res.assertionResults.size} PASS")
                } else if (res?.status == StepStatus.SUCCESS) {
                    append("OK")
                }
            }
            BatchStepSummary(
                stepIndex = idx,
                stepTitle = step.displayTitle(),
                method = step.method.uppercase(),
                url = step.url,
                status = res?.status ?: StepStatus.SKIPPED,
                httpStatusText = httpText,
                durationMs = resp?.durationMs ?: 0L,
                detailMessage = detail
            )
        }
    }
}
