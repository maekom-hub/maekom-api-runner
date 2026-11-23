package com.maekom.apirunner.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.BatchExecutionReport
import com.maekom.apirunner.engine.BatchScenarioRunResult
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.StepStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// [v0.4.1] 폴더 단위 일괄 회귀 실행 결과 리포트 다이얼로그(BatchRunReportDialog) 신규 구현
// 변경 의도: 시나리오별 성공/실패·스텝별 HTTP 상태·Assertion 실패 사유를 한눈에 확인하고,
//          실패 항목 더블클릭 또는 [해당 시나리오 열기]로 메인 화면에 실패 스텝까지 즉시 로드해 디버깅 연결

class BatchRunReportDialog(
    project: Project,
    private val report: BatchExecutionReport
) : DialogWrapper(project, true) {

    var openTargetScenarioPath: String? = null
        private set

    var openTargetStepIndex: Int? = null
        private set

    private val scenarioTableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("batch.report.col.status"),
            I18nManager.t("batch.report.col.scenario"),
            I18nManager.t("batch.report.col.steps"),
            I18nManager.t("batch.report.col.duration"),
            I18nManager.t("batch.report.col.summary")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val stepTableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("batch.report.stepCol.no"),
            I18nManager.t("batch.report.stepCol.status"),
            I18nManager.t("batch.report.stepCol.endpoint"),
            I18nManager.t("batch.report.stepCol.http"),
            I18nManager.t("batch.report.stepCol.duration"),
            I18nManager.t("batch.report.stepCol.detail")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val scenarioTable = JBTable(scenarioTableModel)
    private val stepTable = JBTable(stepTableModel)

    private val openScenarioAction = object : AbstractAction(I18nManager.t("batch.report.openScenario")) {
        override fun actionPerformed(e: ActionEvent?) {
            openSelectedScenario(preferredStepIndex = null)
        }
    }

    init {
        title = I18nManager.t("batch.report.title")
        setCancelButtonText(I18nManager.t("batch.report.close"))
        openScenarioAction.isEnabled = report.scenarioResults.isNotEmpty()
        populateScenarioRows()
        init()
        if (report.scenarioResults.isNotEmpty()) {
            // 실패 시나리오가 있으면 첫 실패 시나리오를 우선 선택해 바로 원인을 확인하도록 유도
            val firstFailIdx = report.scenarioResults.indexOfFirst { !it.passed }.takeIf { it >= 0 } ?: 0
            scenarioTable.setRowSelectionInterval(firstFailIdx, firstFailIdx)
            showStepDetails(report.scenarioResults[firstFailIdx])
        }
    }

    override fun createActions(): Array<Action> = arrayOf(openScenarioAction, cancelAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(920, 560)
            border = JBUI.Borders.empty(4)
        }

        val cancelTag = if (report.cancelled) I18nManager.t("batch.report.cancelledTag") else ""
        val summaryLabel = JBLabel(
            I18nManager.t(
                "batch.report.summary",
                report.folderDisplayName,
                report.environmentName,
                report.effectiveBaseUrl,
                report.totalCount,
                report.passedCount,
                report.failedCount,
                report.totalDurationMs,
                cancelTag
            )
        ).apply {
            font = font.deriveFont(Font.BOLD, 12.5f)
            foreground = if (report.failedCount > 0) {
                JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
            } else {
                JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            }
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(4, 6, 8, 6)
            )
        }

        scenarioTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 86; minWidth = 72; preferredWidth = 80; cellRenderer = PassFailRenderer() }
            columnModel.getColumn(1).preferredWidth = 240
            columnModel.getColumn(2).apply { maxWidth = 78; minWidth = 58; preferredWidth = 68 }
            columnModel.getColumn(3).apply { maxWidth = 90; minWidth = 64; preferredWidth = 76 }
            columnModel.getColumn(4).preferredWidth = 380

            selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val row = selectedRow
                    if (row in report.scenarioResults.indices) {
                        showStepDetails(report.scenarioResults[row])
                        openScenarioAction.isEnabled = true
                    } else {
                        openScenarioAction.isEnabled = false
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && selectedRow in report.scenarioResults.indices) {
                        openSelectedScenario(preferredStepIndex = null)
                    }
                }
            })
        }

        stepTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 25
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 40; minWidth = 32; preferredWidth = 36 }
            columnModel.getColumn(1).apply { maxWidth = 86; minWidth = 70; preferredWidth = 78; cellRenderer = StepStatusRenderer() }
            columnModel.getColumn(2).preferredWidth = 260
            columnModel.getColumn(3).apply { maxWidth = 110; minWidth = 70; preferredWidth = 90 }
            columnModel.getColumn(4).apply { maxWidth = 80; minWidth = 60; preferredWidth = 72 }
            columnModel.getColumn(5).preferredWidth = 340

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && selectedRow >= 0) {
                        openSelectedScenario(preferredStepIndex = selectedRow)
                    }
                }
            })
        }

        val splitter = JBSplitter(true, 0.50f).apply {
            firstComponent = JBScrollPane(scenarioTable)
            secondComponent = JBScrollPane(stepTable)
        }

        root.add(summaryLabel, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        return root
    }

    private fun populateScenarioRows() {
        scenarioTableModel.rowCount = 0
        for (res in report.scenarioResults) {
            val statusLabel = if (res.passed) I18nManager.t("batch.report.pass") else I18nManager.t("batch.report.fail")
            scenarioTableModel.addRow(
                arrayOf<Any>(
                    statusLabel,
                    res.displayPath,
                    "${res.succeededSteps}/${res.totalSteps}",
                    "${res.totalDurationMs} ms",
                    res.failureSummary ?: "OK"
                )
            )
        }
    }

    private fun showStepDetails(scenarioResult: BatchScenarioRunResult) {
        stepTableModel.rowCount = 0
        for (step in scenarioResult.stepSummaries) {
            stepTableModel.addRow(
                arrayOf<Any>(
                    step.stepIndex + 1,
                    step.status.name,
                    "${step.method} ${step.url}",
                    step.httpStatusText,
                    "${step.durationMs} ms",
                    step.detailMessage
                )
            )
        }
    }

    private fun openSelectedScenario(preferredStepIndex: Int?) {
        val row = scenarioTable.selectedRow
        if (row !in report.scenarioResults.indices) return
        val selected = report.scenarioResults[row]
        openTargetScenarioPath = selected.relativePath
        openTargetStepIndex = preferredStepIndex ?: selected.firstFailedStepIndex ?: 0
        close(OK_EXIT_CODE)
    }

    private class PassFailRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            font = font.deriveFont(Font.BOLD)
            if (!isSelected) {
                val text = value?.toString().orEmpty()
                foreground = if (text.contains("❌")) {
                    JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                } else {
                    JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                }
            }
            return this
        }
    }

    private class StepStatusRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            font = font.deriveFont(Font.BOLD)
            if (!isSelected) {
                foreground = when (value?.toString()) {
                    StepStatus.SUCCESS.name -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                    StepStatus.FAILED.name -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                    else -> JBColor.GRAY
                }
            }
            return this
        }
    }
}
