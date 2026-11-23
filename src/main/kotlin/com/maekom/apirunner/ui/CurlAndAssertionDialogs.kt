package com.maekom.apirunner.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.CurlExporter
import com.maekom.apirunner.engine.CurlParser
import com.maekom.apirunner.engine.JsonPathCandidate
import com.maekom.apirunner.engine.ParsedCurlResult
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.AssertionOperator
import com.maekom.apirunner.model.AssertionRule
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// [v0.4.0] [cURL] 통합 모달 다이얼로그(CurlManagerDialog: 내보내기 미리보기 + 스마트 가져오기) 및
//          응답 본문 검증 규칙 추가 다이얼로그(AddAssertionRuleDialog) 구현
// 변경 의도: cURL 복사 전 실시간 명령어 확인·시크릿 마스킹·스마트 파싱(BaseURL 제거/중복 공통 헤더 제외)과
//          No-Code JSONPath 응답 검증 규칙의 직관적인 팝업 등록을 지원

class CurlManagerDialog(
    project: Project,
    private val scenario: ApiScenario,
    private val activeStep: ApiStep?,
    private val runtimeContext: Map<String, String>,
    private val secrets: Map<String, String>,
    private val projectGlobalVariables: Map<String, String>,
    private val onAddNewStep: (ParsedCurlResult) -> Unit,
    private val onOverwriteCurrentStep: (ParsedCurlResult) -> Unit
) : DialogWrapper(project, true) {

    // Export Tab controls
    private val targetStepRadio = JBRadioButton(I18nManager.t("curl.export.targetStep"), activeStep != null).apply {
        isEnabled = activeStep != null
    }
    private val targetScenarioRadio = JBRadioButton(I18nManager.t("curl.export.targetScenario"), activeStep == null)
    private val resolveEnvCheckbox = JBCheckBox(I18nManager.t("curl.export.resolveEnv"), true)
    private val maskSecretsCheckbox = JBCheckBox(I18nManager.t("curl.export.maskSecrets"), true)
    private val exportPreviewArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = false
    }
    private val exportCopyBtn = JButton(I18nManager.t("curl.export.copyBtn"))
    private val exportStatusLabel = JBLabel(" ").apply {
        foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
    }

    // Import Tab controls
    private val importInputArea = JBTextArea(11, 60).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = false
    }
    private val stripBaseUrlCheckbox = JBCheckBox(I18nManager.t("curl.import.stripBaseUrl"), true)
    private val excludeCommonHeadersCheckbox = JBCheckBox(I18nManager.t("curl.import.excludeCommonHeaders"), true)
    private val importSummaryLabel = JBLabel(I18nManager.t("curl.import.summaryEmpty")).apply {
        foreground = JBColor.GRAY
    }
    private val addAsNewStepBtn = JButton(I18nManager.t("curl.import.addNewStep")).apply { isEnabled = false }
    private val overwriteStepBtn = JButton(I18nManager.t("curl.import.overwriteStep")).apply { isEnabled = false }

    private var lastParsedResult: ParsedCurlResult? = null

    init {
        title = I18nManager.t("curl.dialog.title")
        setCancelButtonText(I18nManager.t("curl.dialog.close"))
        val group = ButtonGroup()
        group.add(targetStepRadio)
        group.add(targetScenarioRadio)
        init()
        refreshExportPreview()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane().apply {
            preferredSize = Dimension(780, 480)
        }
        tabs.addTab(I18nManager.t("curl.tab.export"), buildExportTab())
        tabs.addTab(I18nManager.t("curl.tab.import"), buildImportTab())
        return tabs
    }

    private fun buildExportTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
        }

        val optionsPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        val targetRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel(I18nManager.t("curl.export.targetLabel")).apply { font = font.deriveFont(Font.BOLD) })
            add(targetStepRadio)
            add(targetScenarioRadio)
        }
        val checkRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(resolveEnvCheckbox)
            add(maskSecretsCheckbox)
        }
        gbc.gridx = 0; gbc.gridy = 0
        optionsPanel.add(targetRow, gbc)
        gbc.gridy = 1
        optionsPanel.add(checkRow, gbc)

        targetStepRadio.addActionListener { refreshExportPreview() }
        targetScenarioRadio.addActionListener { refreshExportPreview() }
        resolveEnvCheckbox.addActionListener { refreshExportPreview() }
        maskSecretsCheckbox.addActionListener { refreshExportPreview() }

        exportCopyBtn.addActionListener {
            val content = exportPreviewArea.text
            if (content.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                exportStatusLabel.text = I18nManager.t("curl.export.copiedStatus")
            }
        }

        val bottomBar = JPanel(BorderLayout(8, 0)).apply {
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(exportCopyBtn)
                add(exportStatusLabel)
            }
            add(left, BorderLayout.WEST)
        }

        panel.add(optionsPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(exportPreviewArea), BorderLayout.CENTER)
        panel.add(bottomBar, BorderLayout.SOUTH)
        return panel
    }

    private fun refreshExportPreview() {
        val resolveEnv = resolveEnvCheckbox.isSelected
        val maskSec = maskSecretsCheckbox.isSelected
        val output = if (targetStepRadio.isSelected && activeStep != null) {
            CurlExporter.exportStep(
                step = activeStep,
                scenario = scenario,
                runtimeContext = runtimeContext,
                secrets = secrets,
                projectGlobalVariables = projectGlobalVariables,
                resolveEnvironmentAndVars = resolveEnv,
                maskSecrets = maskSec
            )
        } else {
            if (scenario.steps.isEmpty()) {
                "# ${I18nManager.t("curl.noScenarioSteps.msg")}"
            } else {
                CurlExporter.exportScenario(
                    scenario = scenario,
                    runtimeContext = runtimeContext,
                    secrets = secrets,
                    projectGlobalVariables = projectGlobalVariables,
                    resolveEnvironmentAndVars = resolveEnv,
                    maskSecrets = maskSec
                )
            }
        }
        exportPreviewArea.text = output
        exportPreviewArea.caretPosition = 0
        exportStatusLabel.text = " "
    }

    private fun buildImportTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
        }

        val topHint = JBLabel(I18nManager.t("curl.import.hint")).apply {
            foreground = JBColor.GRAY
        }

        val optionsBox = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
        }
        gbc.gridy = 0
        optionsBox.add(stripBaseUrlCheckbox, gbc)
        gbc.gridy = 1
        optionsBox.add(excludeCommonHeadersCheckbox, gbc)
        gbc.gridy = 2
        gbc.insets = JBUI.insetsTop(6)
        optionsBox.add(importSummaryLabel, gbc)

        val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(overwriteStepBtn)
            add(addAsNewStepBtn)
        }

        val southPanel = JPanel(BorderLayout(0, 6)).apply {
            add(optionsBox, BorderLayout.CENTER)
            add(actionBar, BorderLayout.SOUTH)
        }

        val reparse = { reparseCurlInput() }
        importInputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = reparse()
            override fun removeUpdate(e: DocumentEvent?) = reparse()
            override fun changedUpdate(e: DocumentEvent?) = reparse()
        })
        stripBaseUrlCheckbox.addActionListener { reparse() }
        excludeCommonHeadersCheckbox.addActionListener { reparse() }

        addAsNewStepBtn.addActionListener {
            val parsed = lastParsedResult ?: return@addActionListener
            onAddNewStep(parsed)
            close(OK_EXIT_CODE)
        }
        overwriteStepBtn.addActionListener {
            val parsed = lastParsedResult ?: return@addActionListener
            onOverwriteCurrentStep(parsed)
            close(OK_EXIT_CODE)
        }

        panel.add(topHint, BorderLayout.NORTH)
        panel.add(JBScrollPane(importInputArea), BorderLayout.CENTER)
        panel.add(southPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun reparseCurlInput() {
        val raw = importInputArea.text.trim()
        if (raw.isEmpty()) {
            lastParsedResult = null
            importSummaryLabel.text = I18nManager.t("curl.import.summaryEmpty")
            importSummaryLabel.foreground = JBColor.GRAY
            addAsNewStepBtn.isEnabled = false
            overwriteStepBtn.isEnabled = false
            return
        }

        val effectiveGlobals = linkedMapOf<String, String>().apply {
            putAll(scenario.globalVariables)
            putAll(projectGlobalVariables)
        }
        // Resolve enabled common headers so duplicate matching catches resolved values as well
        val resolvedCommonHeaders = VariableResolver.resolveMap(
            source = scenario.effectiveEnabledHeadersMap(),
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )
        val combinedCommonHeaders = linkedMapOf<String, String>().apply {
            putAll(scenario.effectiveEnabledHeadersMap())
            putAll(resolvedCommonHeaders)
        }
        val resolvedBaseUrl = VariableResolver.resolve(
            template = scenario.baseUrl,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = effectiveGlobals
        )

        val result = CurlParser.parse(
            rawCurl = raw,
            activeBaseUrl = resolvedBaseUrl,
            enabledCommonHeaders = combinedCommonHeaders,
            stripMatchingBaseUrl = stripBaseUrlCheckbox.isSelected,
            excludeMatchingCommonHeaders = excludeCommonHeadersCheckbox.isSelected
        )

        if (result.isSuccess) {
            val parsed = result.getOrThrow()
            lastParsedResult = parsed
            val bodyLabel = if (parsed.body.isNotBlank()) I18nManager.t("curl.import.bodyYes") else I18nManager.t("curl.import.bodyNo")
            val notes = mutableListOf<String>()
            if (parsed.strippedBaseUrl) notes.add(I18nManager.t("curl.import.noteBaseStripped"))
            if (parsed.removedCommonHeaderCount > 0) {
                notes.add(I18nManager.t("curl.import.noteHeadersRemoved", parsed.removedCommonHeaderCount))
            }
            val noteSuffix = if (notes.isNotEmpty()) I18nManager.t("curl.import.summaryNote", notes.joinToString(", ")) else ""
            importSummaryLabel.text = I18nManager.t(
                "curl.import.summaryOk",
                parsed.method,
                parsed.url,
                parsed.queryParams.size,
                parsed.headers.size,
                bodyLabel
            ) + noteSuffix
            importSummaryLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            addAsNewStepBtn.isEnabled = true
            overwriteStepBtn.isEnabled = activeStep != null
        } else {
            lastParsedResult = null
            importSummaryLabel.text = "⚠️ ${result.exceptionOrNull()?.message ?: "Parse error"}"
            importSummaryLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
            addAsNewStepBtn.isEnabled = false
            overwriteStepBtn.isEnabled = false
        }
    }
}

class AddAssertionRuleDialog(
    project: Project,
    private val candidates: List<JsonPathCandidate> = emptyList(),
    initialCandidate: JsonPathCandidate? = null
) : DialogWrapper(project, true) {

    private val candidateCombo = ComboBox<JsonPathCandidate>(
        DefaultComboBoxModel(candidates.toTypedArray())
    )
    private val jsonPathField = JBTextField(initialCandidate?.jsonPath ?: "$.code", 34)
    private val operatorCombo = ComboBox(AssertionOperator.ALL_SYMBOLS)
    private val expectedValueField = JBTextField(initialCandidate?.valuePreview.orEmpty(), 34)
    private val enabledCheckbox = JBCheckBox(I18nManager.t("assert.dialog.enabled"), true)

    init {
        title = I18nManager.t("assert.dialog.title")
        setOKButtonText(I18nManager.t("extractDialog.add"))
        setCancelButtonText(I18nManager.t("extractDialog.cancel"))

        if (initialCandidate != null) {
            candidateCombo.selectedItem = initialCandidate
        } else if (candidates.isNotEmpty()) {
            candidateCombo.selectedIndex = 0
            applyCandidate(candidates.first())
        }

        candidateCombo.addActionListener {
            val sel = candidateCombo.selectedItem as? JsonPathCandidate ?: return@addActionListener
            applyCandidate(sel)
        }
        operatorCombo.addActionListener { syncExpectedEnabledState() }
        syncExpectedEnabledState()
        init()
    }

    private fun applyCandidate(candidate: JsonPathCandidate) {
        jsonPathField.text = candidate.jsonPath
        expectedValueField.text = candidate.valuePreview
    }

    private fun syncExpectedEnabledState() {
        val op = AssertionOperator.fromSymbol(operatorCombo.selectedItem?.toString())
        expectedValueField.isEnabled = op.requiresExpectedValue()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(6)
        }
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0
        if (candidates.isNotEmpty()) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            panel.add(JBLabel(I18nManager.t("assert.dialog.fieldSelect")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(candidateCombo, gbc)
            row++
        }

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("assert.dialog.jsonPath")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(jsonPathField, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("assert.dialog.operator")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(operatorCombo, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("assert.dialog.expected")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(expectedValueField, gbc)
        row++

        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        panel.add(enabledCheckbox, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (jsonPathField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.t("assert.dialog.invalidPath"), jsonPathField)
        }
        return null
    }

    fun toAssertionRule(): AssertionRule {
        val op = AssertionOperator.fromSymbol(operatorCombo.selectedItem?.toString())
        return AssertionRule(
            enabled = enabledCheckbox.isSelected,
            jsonPath = jsonPathField.text.trim(),
            operator = op.symbol,
            expectedValue = if (op.requiresExpectedValue()) expectedValueField.text else ""
        )
    }
}
