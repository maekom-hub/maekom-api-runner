package com.maekom.apirunner.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.ScenarioRunListener
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.ExtractedEndpoint
import com.maekom.apirunner.model.HttpMethod
import com.maekom.apirunner.model.ScenarioExecutionSummary
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepExecutionResult
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
import com.maekom.apirunner.storage.ScenarioStorageService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 3-Pane Split ToolWindow UI for Maekom API Runner:
 * - Top Toolbar: Scenario selector, Save, New, Import/Export, Global Headers/Secrets, BaseURL, Ignore SSL, Run All, Stop
 * - Left Pane: Step list with active checkboxes, status icons, Method+Path, reorder/duplicate/delete/add buttons
 * - Center Pane: Step editor (Method, URL, Expected Status, Single Run, Headers / Body JSON / Extract Rules tabs)
 * - Right Pane: Result viewer (HTTP Status, Duration ms, Extracted Variables, Formatted Response JSON/Raw Text, Error Logs)
 */
class ScenarioToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val storage: ScenarioStorageService = project.service()
    private val scenarioRunner = ScenarioRunner()
    private val jsonFormatter = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private var currentScenario: ApiScenario = ApiScenario()
    private var quickSingleStep: ApiStep? = null
    private var isQuickSingleMode: Boolean = false
    private var isRunning: Boolean = false
    private var isUpdatingUi: Boolean = false

    private val stepStatuses = mutableMapOf<String, StepStatus>()
    private val stepResults = mutableMapOf<String, StepExecutionResult>()
    private val lastRuntimeContext = linkedMapOf<String, String>()

    // --- Top Toolbar Controls ---
    private val scenarioComboModel = DefaultComboBoxModel<String>()
    private val scenarioComboBox = ComboBox(scenarioComboModel)
    private val saveBtn = JButton("저장")
    private val newScenarioBtn = JButton("새 시나리오")
    private val importExportBtn = JButton("불러오기/내보내기 ▾")
    private val globalConfigBtn = JButton("공통 헤더/시크릿")
    private val baseUrlField = JBTextField(20)
    private val ignoreSslCheckbox = JBCheckBox("SSL 무시")
    private val runAllBtn = JButton("▶ 전체 시나리오 실행")
    private val stopBtn = JButton("■ 중지")

    // Quick-Single Mode Banner
    private val quickModeBanner = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
    private val quickModeLabel = JBLabel("🌶️ 임시 단건 실행 모드")
    private val promoteToScenarioBtn = JButton("➕ 현재 시나리오에 추가")
    private val exitQuickModeBtn = JButton("시나리오 모드로 복귀")

    // --- Left Pane: Step List ---
    private val stepTableModel = StepListTableModel()
    private val stepTable = JBTable(stepTableModel)
    private val moveUpBtn = JButton("▲")
    private val moveDownBtn = JButton("▼")
    private val duplicateStepBtn = JButton("복제")
    private val deleteStepBtn = JButton("삭제")
    private val addEmptyStepBtn = JButton("+ 빈 스텝")

    // --- Center Pane: Step Detail Editor ---
    private val methodComboBox = ComboBox(HttpMethod.entries.map { it.name }.toTypedArray())
    private val urlField = JBTextField()
    private val expectedStatusField = JBTextField("2xx", 6)
    private val runSingleBtn = JButton("▶ 단건 실행")
    private val editorTabs = JBTabbedPane()

    private val headersTableModel = object : DefaultTableModel(arrayOf("Header Key", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val headersTable = JBTable(headersTableModel)

    private val bodyTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
    }

    private val extractRulesTableModel = object : DefaultTableModel(arrayOf("변수명 (varName)", "JsonPath", "필수"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 2) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val extractRulesTable = JBTable(extractRulesTableModel)

    // --- Right Pane: Result Viewer ---
    private val statusBadgeLabel = JBLabel("대기 중")
    private val durationLabel = JBLabel("0 ms")
    private val scenarioStatusLabel = JBLabel("")
    private val resultTabs = JBTabbedPane()

    private val extractedVarsModel = object : DefaultTableModel(arrayOf("추출 변수명", "값"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val extractedVarsTable = JBTable(extractedVarsModel)

    private val responseBodyArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = false
    }

    private val detailLogArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = false
    }

    init {
        buildLayout()
        wireEvents()
        initializeScenarios()
    }

    private fun buildLayout() {
        val northContainer = JPanel(BorderLayout())
        northContainer.add(buildTopToolbar(), BorderLayout.NORTH)
        northContainer.add(buildQuickModeBanner(), BorderLayout.SOUTH)
        add(northContainer, BorderLayout.NORTH)

        val centerRightSplitter = OnePixelSplitter(false, 0.52f).apply {
            firstComponent = buildCenterEditorPane()
            secondComponent = buildRightResultPane()
        }

        val mainSplitter = OnePixelSplitter(false, 0.25f).apply {
            firstComponent = buildLeftStepListPane()
            secondComponent = centerRightSplitter
        }

        add(mainSplitter, BorderLayout.CENTER)
    }

    private fun buildTopToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        toolbar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        scenarioComboBox.preferredSize = Dimension(160, 28)
        baseUrlField.preferredSize = Dimension(190, 28)
        baseUrlField.toolTipText = "시나리오 전역 Base URL (예: http://localhost:8080)"
        ignoreSslCheckbox.toolTipText = "로컬 개발용 자가서명(Self-signed) HTTPS 인증서 검증 무시"
        stopBtn.isEnabled = false

        runAllBtn.foreground = JBColor(Color(0x1B, 0x7F, 0x3A), Color(0x6C, 0xD9, 0x7E))
        stopBtn.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))

        toolbar.add(JBLabel("시나리오:"))
        toolbar.add(scenarioComboBox)
        toolbar.add(saveBtn)
        toolbar.add(newScenarioBtn)
        toolbar.add(importExportBtn)
        toolbar.add(globalConfigBtn)
        toolbar.add(JBLabel("BaseURL:"))
        toolbar.add(baseUrlField)
        toolbar.add(ignoreSslCheckbox)
        toolbar.add(runAllBtn)
        toolbar.add(stopBtn)

        return toolbar
    }

    private fun buildQuickModeBanner(): JPanel {
        quickModeBanner.background = JBColor(Color(0xFF, 0xF3, 0xE0), Color(0x3E, 0x27, 0x23))
        quickModeBanner.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        quickModeLabel.font = quickModeLabel.font.deriveFont(Font.BOLD)
        quickModeBanner.add(quickModeLabel)
        quickModeBanner.add(promoteToScenarioBtn)
        quickModeBanner.add(exitQuickModeBtn)
        quickModeBanner.isVisible = false
        return quickModeBanner
    }

    private fun buildLeftStepListPane(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(4)

        val header = JBLabel("실행 스텝 목록").apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4, 6, 4)
        }
        panel.add(header, BorderLayout.NORTH)

        stepTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            setShowGrid(false)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                maxWidth = 30
                minWidth = 28
                preferredWidth = 28
            }
            columnModel.getColumn(1).apply {
                maxWidth = 32
                minWidth = 28
                preferredWidth = 30
                cellRenderer = StatusIconCellRenderer()
            }
            columnModel.getColumn(2).cellRenderer = MethodPathCellRenderer()
        }
        panel.add(JBScrollPane(stepTable), BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 3, 4))
        moveUpBtn.margin = JBUI.insets(2, 6)
        moveDownBtn.margin = JBUI.insets(2, 6)
        duplicateStepBtn.margin = JBUI.insets(2, 6)
        deleteStepBtn.margin = JBUI.insets(2, 6)
        addEmptyStepBtn.margin = JBUI.insets(2, 6)

        bottomBar.add(moveUpBtn)
        bottomBar.add(moveDownBtn)
        bottomBar.add(duplicateStepBtn)
        bottomBar.add(deleteStepBtn)
        bottomBar.add(addEmptyStepBtn)
        panel.add(bottomBar, BorderLayout.SOUTH)

        return panel
    }

    private fun buildCenterEditorPane(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(4)

        val topBar = JPanel(BorderLayout(6, 0))
        methodComboBox.preferredSize = Dimension(92, 28)

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        expectedStatusField.toolTipText = "기대 HTTP 상태코드 (예: 2xx, 200, 200,201, 200..299)"
        expectedStatusField.preferredSize = Dimension(64, 28)
        rightControls.add(JBLabel("기대 상태:"))
        rightControls.add(expectedStatusField)
        rightControls.add(runSingleBtn)

        topBar.add(methodComboBox, BorderLayout.WEST)
        topBar.add(urlField, BorderLayout.CENTER)
        topBar.add(rightControls, BorderLayout.EAST)

        panel.add(topBar, BorderLayout.NORTH)

        // Tab 1: Headers
        headersTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
        }
        val headersPanel = JPanel(BorderLayout(0, 4))
        headersPanel.add(JBScrollPane(headersTable), BorderLayout.CENTER)
        val headersBtnBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val addHeaderBtn = JButton("+ 헤더 추가").apply {
            addActionListener {
                stopTableEditing(headersTable)
                headersTableModel.addRow(arrayOf("", ""))
                syncEditorToCurrentStep()
            }
        }
        val removeHeaderBtn = JButton("- 삭제").apply {
            addActionListener {
                stopTableEditing(headersTable)
                val rows = headersTable.selectedRows
                for (i in rows.indices.reversed()) {
                    headersTableModel.removeRow(rows[i])
                }
                syncEditorToCurrentStep()
            }
        }
        headersBtnBar.add(addHeaderBtn)
        headersBtnBar.add(removeHeaderBtn)
        headersPanel.add(headersBtnBar, BorderLayout.SOUTH)

        // Tab 2: Body (JSON)
        val bodyPanel = JPanel(BorderLayout(0, 4))
        bodyPanel.add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)
        val bodyBtnBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val formatJsonBtn = JButton("JSON 포맷팅").apply {
            addActionListener {
                val raw = bodyTextArea.text.trim()
                if (raw.isNotEmpty()) {
                    try {
                        val tree = jsonFormatter.readTree(raw)
                        bodyTextArea.text = jsonFormatter.writeValueAsString(tree)
                    } catch (e: Exception) {
                        Messages.showWarningDialog(project, "유효한 JSON 형식이 아닙니다:\n${e.message}", "JSON 포맷 오류")
                    }
                }
            }
        }
        bodyBtnBar.add(formatJsonBtn)
        bodyBtnBar.add(JBLabel("  💡 {{변수명}} 치환 지원 (컨텍스트 > 시크릿 > 전역 변수)").apply {
            foreground = JBColor.GRAY
        })
        bodyPanel.add(bodyBtnBar, BorderLayout.SOUTH)

        // Tab 3: Extract Rules
        extractRulesTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            columnModel.getColumn(2).apply {
                maxWidth = 54
                minWidth = 44
                preferredWidth = 48
            }
        }
        val extractPanel = JPanel(BorderLayout(0, 4))
        extractPanel.add(JBScrollPane(extractRulesTable), BorderLayout.CENTER)
        val extractBtnBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val addRuleBtn = JButton("+ 규칙 추가").apply {
            addActionListener {
                stopTableEditing(extractRulesTable)
                extractRulesTableModel.addRow(arrayOf("token", "$.data.token", true))
                syncEditorToCurrentStep()
            }
        }
        val removeRuleBtn = JButton("- 삭제").apply {
            addActionListener {
                stopTableEditing(extractRulesTable)
                val rows = extractRulesTable.selectedRows
                for (i in rows.indices.reversed()) {
                    extractRulesTableModel.removeRow(rows[i])
                }
                syncEditorToCurrentStep()
            }
        }
        extractBtnBar.add(addRuleBtn)
        extractBtnBar.add(removeRuleBtn)
        extractBtnBar.add(JBLabel("  ⚠️ 필수(체크) 추출 실패 시 즉시 Fail-Fast 중단").apply {
            foreground = JBColor.GRAY
        })
        extractPanel.add(extractBtnBar, BorderLayout.SOUTH)

        editorTabs.addTab("Headers", headersPanel)
        editorTabs.addTab("Body (JSON)", bodyPanel)
        editorTabs.addTab("응답 변수 추출 규칙", extractPanel)

        panel.add(editorTabs, BorderLayout.CENTER)
        return panel
    }

    private fun buildRightResultPane(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(4)

        val summaryBar = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4))
        summaryBar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        statusBadgeLabel.font = statusBadgeLabel.font.deriveFont(Font.BOLD)
        durationLabel.foreground = JBColor.GRAY
        scenarioStatusLabel.font = scenarioStatusLabel.font.deriveFont(Font.BOLD)

        summaryBar.add(JBLabel("상태:"))
        summaryBar.add(statusBadgeLabel)
        summaryBar.add(JBLabel("| 소요:"))
        summaryBar.add(durationLabel)
        summaryBar.add(scenarioStatusLabel)
        panel.add(summaryBar, BorderLayout.NORTH)

        // Tab 1: Response + Extracted Variables vertical split
        extractedVarsTable.rowHeight = 22
        val extractedPanel = JPanel(BorderLayout())
        extractedPanel.add(
            JBLabel("추출된 변수 목록").apply {
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(2, 4)
            },
            BorderLayout.NORTH
        )
        extractedPanel.add(JBScrollPane(extractedVarsTable), BorderLayout.CENTER)

        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(
            JBLabel("응답 본문 (JSON / Raw Text)").apply {
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(2, 4)
            },
            BorderLayout.NORTH
        )
        responsePanel.add(JBScrollPane(responseBodyArea), BorderLayout.CENTER)

        val resultSplitter = OnePixelSplitter(true, 0.26f).apply {
            firstComponent = extractedPanel
            secondComponent = responsePanel
        }

        // Tab 2: Detailed Request/Response & Failure Log
        val logScroll = JBScrollPane(detailLogArea)

        resultTabs.addTab("응답 / 추출 변수", resultSplitter)
        resultTabs.addTab("실행 및 에러 로그", logScroll)

        panel.add(resultTabs, BorderLayout.CENTER)
        return panel
    }

    private fun wireEvents() {
        // Scenario ComboBox change
        scenarioComboBox.addActionListener {
            if (isUpdatingUi) return@addActionListener
            val selectedName = scenarioComboBox.selectedItem as? String ?: return@addActionListener
            if (selectedName != currentScenario.name || isQuickSingleMode) {
                loadScenarioByName(selectedName)
            }
        }

        saveBtn.addActionListener { saveCurrentScenario(showFeedback = true) }
        newScenarioBtn.addActionListener { createNewScenarioDialog() }
        importExportBtn.addActionListener { showImportExportMenu() }
        globalConfigBtn.addActionListener { openGlobalConfigDialog() }

        baseUrlField.document.addDocumentListener(simpleDocListener {
            if (!isUpdatingUi) {
                currentScenario.baseUrl = baseUrlField.text.trim()
            }
        })

        ignoreSslCheckbox.addActionListener {
            if (!isUpdatingUi) {
                currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected
            }
        }

        runAllBtn.addActionListener { executeFullScenario() }
        stopBtn.addActionListener { stopExecution() }
        runSingleBtn.addActionListener { executeCurrentSingleStep() }

        // Quick single-run banner buttons
        promoteToScenarioBtn.addActionListener {
            val quick = quickSingleStep ?: return@addActionListener
            syncEditorToCurrentStep()
            val copy = quick.deepCopy()
            isQuickSingleMode = false
            quickSingleStep = null
            quickModeBanner.isVisible = false
            currentScenario.steps.add(copy)
            stepTableModel.fireTableDataChanged()
            selectStepRow(currentScenario.steps.lastIndex)
            saveCurrentScenario(showFeedback = false)
        }
        exitQuickModeBtn.addActionListener {
            isQuickSingleMode = false
            quickSingleStep = null
            quickModeBanner.isVisible = false
            refreshEditorFromSelection()
        }

        // Left Pane Step selection & actions
        stepTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !isUpdatingUi) {
                if (isQuickSingleMode) {
                    isQuickSingleMode = false
                    quickModeBanner.isVisible = false
                }
                refreshEditorFromSelection()
                val step = getSelectedScenarioStep()
                if (step != null) {
                    displayStepResult(stepResults[step.id])
                }
            }
        }

        moveUpBtn.addActionListener { moveSelectedStep(-1) }
        moveDownBtn.addActionListener { moveSelectedStep(1) }
        duplicateStepBtn.addActionListener { duplicateSelectedStep() }
        deleteStepBtn.addActionListener { deleteSelectedStep() }
        addEmptyStepBtn.addActionListener { addEmptyStep() }

        // Center Pane auto-sync to step model
        methodComboBox.addActionListener { if (!isUpdatingUi) syncEditorToCurrentStep() }
        urlField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        expectedStatusField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        bodyTextArea.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })

        headersTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                syncEditorToCurrentStep()
            }
        }
        extractRulesTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                syncEditorToCurrentStep()
            }
        }
    }

    private fun initializeScenarios() {
        val detectedBaseUrl = PsiEndpointExtractor.detectServerBaseUrl(project)
        val existingNames = storage.listScenarios()

        isUpdatingUi = true
        try {
            scenarioComboModel.removeAllElements()
            if (existingNames.isEmpty()) {
                currentScenario = ApiScenario(
                    name = "default",
                    baseUrl = detectedBaseUrl
                )
                scenarioComboModel.addElement(currentScenario.name)
                scenarioComboBox.selectedItem = currentScenario.name
            } else {
                existingNames.forEach { scenarioComboModel.addElement(it) }
                val first = existingNames.first()
                currentScenario = storage.loadScenario(first) ?: ApiScenario(name = first, baseUrl = detectedBaseUrl)
                scenarioComboBox.selectedItem = currentScenario.name
            }

            if (currentScenario.baseUrl.isBlank()) {
                currentScenario.baseUrl = detectedBaseUrl
            }
            baseUrlField.text = currentScenario.baseUrl
            ignoreSslCheckbox.isSelected = currentScenario.ignoreSsl
            stepTableModel.fireTableDataChanged()
        } finally {
            isUpdatingUi = false
        }

        if (currentScenario.steps.isNotEmpty()) {
            selectStepRow(0)
        } else {
            refreshEditorFromSelection()
        }
    }

    // --- Public API invoked by Gutter Marker via ScenarioService ---

    fun loadQuickSingleRun(endpoint: ExtractedEndpoint) {
        val step = endpoint.toApiStep()
        quickSingleStep = step
        isQuickSingleMode = true

        isUpdatingUi = true
        try {
            if (currentScenario.baseUrl.isBlank() || currentScenario.baseUrl == "http://localhost:8080") {
                currentScenario.baseUrl = endpoint.baseUrl
                baseUrlField.text = endpoint.baseUrl
            }
            quickModeLabel.text = "🌶️ 임시 단건 실행 모드: ${endpoint.displayName} (${endpoint.method} ${endpoint.path})"
            quickModeBanner.isVisible = true
            populateEditorFields(step)
        } finally {
            isUpdatingUi = false
        }

        displayStepResult(null)
        statusBadgeLabel.text = "단건 준비 완료"
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        scenarioStatusLabel.text = ""
    }

    fun addStepFromEndpoint(endpoint: ExtractedEndpoint) {
        isQuickSingleMode = false
        quickSingleStep = null
        quickModeBanner.isVisible = false

        val step = endpoint.toApiStep()
        if (currentScenario.baseUrl.isBlank() || currentScenario.baseUrl == "http://localhost:8080") {
            currentScenario.baseUrl = endpoint.baseUrl
            isUpdatingUi = true
            baseUrlField.text = currentScenario.baseUrl
            isUpdatingUi = false
        }

        currentScenario.steps.add(step)
        stepStatuses[step.id] = StepStatus.PENDING
        stepTableModel.fireTableDataChanged()
        selectStepRow(currentScenario.steps.lastIndex)
        saveCurrentScenario(showFeedback = false)
    }

    // --- Scenario CRUD & Import/Export ---

    private fun loadScenarioByName(name: String) {
        val loaded = storage.loadScenario(name)
        if (loaded == null) {
            Messages.showErrorDialog(project, "시나리오 '$name'을(를) 불러오지 못했습니다.", "불러오기 실패")
            return
        }
        isQuickSingleMode = false
        quickSingleStep = null
        quickModeBanner.isVisible = false

        currentScenario = loaded
        stepStatuses.clear()
        stepResults.clear()
        lastRuntimeContext.clear()

        isUpdatingUi = true
        try {
            baseUrlField.text = currentScenario.baseUrl
            ignoreSslCheckbox.isSelected = currentScenario.ignoreSsl
            scenarioComboBox.selectedItem = currentScenario.name
            stepTableModel.fireTableDataChanged()
        } finally {
            isUpdatingUi = false
        }

        if (currentScenario.steps.isNotEmpty()) {
            selectStepRow(0)
        } else {
            refreshEditorFromSelection()
        }
        displayStepResult(null)
    }

    private fun saveCurrentScenario(showFeedback: Boolean) {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val savedFile = storage.saveScenario(currentScenario)
        refreshScenarioComboBox(currentScenario.name)
        if (showFeedback) {
            scenarioStatusLabel.text = "💾 저장됨 (${savedFile.name})"
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
    }

    private fun createNewScenarioDialog() {
        val inputName = Messages.showInputDialog(
            project,
            "새 시나리오 이름을 입력하세요 (파일명으로 저장됩니다):",
            "새 시나리오 생성",
            Messages.getQuestionIcon()
        )?.trim()
        if (inputName.isNullOrBlank()) return

        val safeName = storage.sanitizeFileName(inputName)
        val detectedBaseUrl = PsiEndpointExtractor.detectServerBaseUrl(project)
        val newScenario = ApiScenario(name = safeName, baseUrl = detectedBaseUrl)
        storage.saveScenario(newScenario)

        refreshScenarioComboBox(safeName)
        loadScenarioByName(safeName)
    }

    private fun showImportExportMenu() {
        val actions = listOf(
            "📥 외부 JSON 불러오기 (Import)",
            "📤 JSON 파일로 내보내기 (Export)",
            "📋 현재 시나리오 복제",
            "🗑️ 현재 시나리오 삭제"
        )
        val popupStep = object : BaseListPopupStep<String>("시나리오 관리", actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    when (selectedValue) {
                        actions[0] -> importExternalScenario()
                        actions[1] -> exportCurrentScenario()
                        actions[2] -> duplicateCurrentScenario()
                        actions[3] -> deleteCurrentScenario()
                    }
                }
            }
        }
        JBPopupFactory.getInstance()
            .createListPopup(popupStep)
            .showUnderneathOf(importExportBtn)
    }

    private fun importExternalScenario() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").apply {
            title = "시나리오 JSON 파일 선택"
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        try {
            val imported = storage.importScenario(File(chosen.path))
            refreshScenarioComboBox(imported.name)
            loadScenarioByName(imported.name)
            Messages.showInfoMessage(project, "시나리오 '${imported.name}'을(를) 불러왔습니다.", "불러오기 완료")
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "JSON 불러오기 실패:\n${e.message}", "오류")
        }
    }

    private fun exportCurrentScenario() {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        val saverDescriptor = FileSaverDescriptor("시나리오 JSON 내보내기", "저장할 JSON 파일 경로를 선택하세요", "json")
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(saverDescriptor, project)
        val wrapper = saveDialog.save("${currentScenario.name}.json") ?: return
        try {
            storage.exportScenario(currentScenario, wrapper.file)
            Messages.showInfoMessage(project, "시나리오를 내보냈습니다:\n${wrapper.file.absolutePath}", "내보내기 완료")
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "내보내기 실패:\n${e.message}", "오류")
        }
    }

    private fun duplicateCurrentScenario() {
        saveCurrentScenario(showFeedback = false)
        val newName = Messages.showInputDialog(
            project,
            "복제할 새 시나리오 이름을 입력하세요:",
            "시나리오 복제",
            Messages.getQuestionIcon(),
            "${currentScenario.name}_copy",
            null
        )?.trim()
        if (newName.isNullOrBlank()) return

        val duplicated = storage.duplicateScenario(currentScenario.name, newName)
        if (duplicated != null) {
            refreshScenarioComboBox(duplicated.name)
            loadScenarioByName(duplicated.name)
        }
    }

    private fun deleteCurrentScenario() {
        val name = currentScenario.name
        val confirm = Messages.showYesNoDialog(
            project,
            "시나리오 '$name'을(를) 정말 삭제하시겠습니까?",
            "시나리오 삭제",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        storage.deleteScenario(name)
        initializeScenarios()
    }

    private fun openGlobalConfigDialog() {
        val currentSecrets = storage.loadSecrets()
        val dialog = GlobalConfigDialog(
            project = project,
            initialHeaders = currentScenario.globalHeaders,
            initialVariables = currentScenario.globalVariables,
            initialSecrets = currentSecrets
        )
        if (dialog.showAndGet()) {
            currentScenario.globalHeaders = dialog.getGlobalHeaders()
            currentScenario.globalVariables = dialog.getGlobalVariables()
            storage.saveSecrets(dialog.getSecrets())
            saveCurrentScenario(showFeedback = false)
            scenarioStatusLabel.text = "🔒 공통 설정 및 시크릿 저장됨"
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
    }

    private fun refreshScenarioComboBox(selectedName: String) {
        val names = storage.listScenarios().toMutableList()
        if (selectedName !in names) {
            names.add(selectedName)
            names.sortWith(String.CASE_INSENSITIVE_ORDER)
        }
        isUpdatingUi = true
        try {
            scenarioComboModel.removeAllElements()
            names.forEach { scenarioComboModel.addElement(it) }
            scenarioComboBox.selectedItem = selectedName
        } finally {
            isUpdatingUi = false
        }
    }

    // --- Step List Actions ---

    private fun addEmptyStep() {
        isQuickSingleMode = false
        quickModeBanner.isVisible = false
        val newStep = ApiStep(
            name = "Step ${currentScenario.steps.size + 1}",
            method = "GET",
            url = "/api/example",
            expectedStatus = "2xx"
        )
        currentScenario.steps.add(newStep)
        stepStatuses[newStep.id] = StepStatus.PENDING
        stepTableModel.fireTableDataChanged()
        selectStepRow(currentScenario.steps.lastIndex)
    }

    private fun duplicateSelectedStep() {
        val idx = stepTable.selectedRow
        if (idx !in currentScenario.steps.indices) return
        stopAllTableEdits()
        syncEditorToCurrentStep()
        val copy = currentScenario.steps[idx].deepCopy()
        copy.name = "${copy.displayTitle()} (copy)"
        currentScenario.steps.add(idx + 1, copy)
        stepStatuses[copy.id] = StepStatus.PENDING
        stepTableModel.fireTableDataChanged()
        selectStepRow(idx + 1)
    }

    private fun deleteSelectedStep() {
        val idx = stepTable.selectedRow
        if (idx !in currentScenario.steps.indices) return
        val removed = currentScenario.steps.removeAt(idx)
        stepStatuses.remove(removed.id)
        stepResults.remove(removed.id)
        stepTableModel.fireTableDataChanged()
        if (currentScenario.steps.isNotEmpty()) {
            selectStepRow(idx.coerceAtMost(currentScenario.steps.lastIndex))
        } else {
            refreshEditorFromSelection()
            displayStepResult(null)
        }
    }

    private fun moveSelectedStep(delta: Int) {
        val idx = stepTable.selectedRow
        val target = idx + delta
        if (idx !in currentScenario.steps.indices || target !in currentScenario.steps.indices) return
        stopAllTableEdits()
        syncEditorToCurrentStep()
        val step = currentScenario.steps.removeAt(idx)
        currentScenario.steps.add(target, step)
        stepTableModel.fireTableDataChanged()
        selectStepRow(target)
    }

    private fun selectStepRow(rowIndex: Int) {
        if (rowIndex in 0 until stepTable.rowCount) {
            stepTable.setRowSelectionInterval(rowIndex, rowIndex)
            stepTable.scrollRectToVisible(stepTable.getCellRect(rowIndex, 0, true))
            refreshEditorFromSelection()
        }
    }

    private fun getSelectedScenarioStep(): ApiStep? {
        val row = stepTable.selectedRow
        return currentScenario.steps.getOrNull(row)
    }

    private fun getActiveEditableStep(): ApiStep? {
        return if (isQuickSingleMode) quickSingleStep else getSelectedScenarioStep()
    }

    private fun refreshEditorFromSelection() {
        val step = getActiveEditableStep()
        isUpdatingUi = true
        try {
            if (step == null) {
                methodComboBox.selectedItem = "GET"
                urlField.text = ""
                expectedStatusField.text = "2xx"
                headersTableModel.rowCount = 0
                bodyTextArea.text = ""
                extractRulesTableModel.rowCount = 0
                setEditorEnabled(false)
            } else {
                setEditorEnabled(true)
                populateEditorFields(step)
            }
        } finally {
            isUpdatingUi = false
        }
    }

    private fun populateEditorFields(step: ApiStep) {
        methodComboBox.selectedItem = HttpMethod.fromString(step.method).name
        urlField.text = step.url
        expectedStatusField.text = step.expectedStatus.ifBlank { "2xx" }

        headersTableModel.rowCount = 0
        for ((k, v) in step.headers) {
            headersTableModel.addRow(arrayOf(k, v))
        }

        bodyTextArea.text = step.body
        bodyTextArea.caretPosition = 0

        extractRulesTableModel.rowCount = 0
        for (rule in step.extractRules) {
            extractRulesTableModel.addRow(arrayOf(rule.varName, rule.jsonPath, rule.required))
        }
    }

    private fun setEditorEnabled(enabled: Boolean) {
        methodComboBox.isEnabled = enabled
        urlField.isEnabled = enabled
        expectedStatusField.isEnabled = enabled
        runSingleBtn.isEnabled = enabled && !isRunning
        headersTable.isEnabled = enabled
        bodyTextArea.isEnabled = enabled
        extractRulesTable.isEnabled = enabled
    }

    private fun syncEditorToCurrentStep() {
        val step = getActiveEditableStep() ?: return
        step.method = methodComboBox.selectedItem?.toString() ?: "GET"
        step.url = urlField.text
        step.expectedStatus = expectedStatusField.text.trim().ifEmpty { "2xx" }
        step.body = bodyTextArea.text

        val headers = linkedMapOf<String, String>()
        for (i in 0 until headersTableModel.rowCount) {
            val k = (headersTableModel.getValueAt(i, 0)?.toString() ?: "").trim()
            val v = headersTableModel.getValueAt(i, 1)?.toString() ?: ""
            if (k.isNotEmpty()) headers[k] = v
        }
        step.headers = headers

        val rules = mutableListOf<ExtractRule>()
        for (i in 0 until extractRulesTableModel.rowCount) {
            val varName = (extractRulesTableModel.getValueAt(i, 0)?.toString() ?: "").trim()
            val jsonPath = (extractRulesTableModel.getValueAt(i, 1)?.toString() ?: "").trim()
            val required = (extractRulesTableModel.getValueAt(i, 2) as? Boolean) ?: true
            if (varName.isNotEmpty() || jsonPath.isNotEmpty()) {
                rules.add(ExtractRule(varName = varName, jsonPath = jsonPath, required = required))
            }
        }
        step.extractRules = rules

        if (!isQuickSingleMode) {
            val selectedRow = stepTable.selectedRow
            if (selectedRow in currentScenario.steps.indices) {
                stepTableModel.fireTableRowsUpdated(selectedRow, selectedRow)
            }
        }
    }

    private fun stopAllTableEdits() {
        stopTableEditing(stepTable)
        stopTableEditing(headersTable)
        stopTableEditing(extractRulesTable)
    }

    private fun stopTableEditing(table: JBTable) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    // --- Execution Engine (Background Thread -> EDT invokeLater) ---

    private fun executeFullScenario() {
        if (isRunning) return
        stopAllTableEdits()
        syncEditorToCurrentStep()

        if (currentScenario.steps.isEmpty()) {
            Messages.showInfoMessage(project, "실행할 스텝이 없습니다. 컨트롤러의 고추 아이콘 또는 [+ 빈 스텝]으로 추가하세요.", "Maekom API Runner")
            return
        }

        isQuickSingleMode = false
        quickModeBanner.isVisible = false

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val scenarioSnapshot = currentScenario.deepCopy()
        // Preserve step IDs so UI status map lines up with original steps
        for (i in currentScenario.steps.indices) {
            scenarioSnapshot.steps[i].id = currentScenario.steps[i].id
        }
        val secrets = storage.loadSecrets()

        stepStatuses.clear()
        stepResults.clear()
        lastRuntimeContext.clear()
        currentScenario.steps.forEach { stepStatuses[it.id] = StepStatus.PENDING }
        stepTableModel.fireTableDataChanged()

        setRunningState(true)
        scenarioStatusLabel.text = "🔄 시나리오 실행 중..."
        scenarioStatusLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))

        ApplicationManager.getApplication().executeOnPooledThread {
            scenarioRunner.runScenario(
                scenario = scenarioSnapshot,
                secrets = secrets,
                listener = object : ScenarioRunListener {
                    override fun onStepStarted(stepIndex: Int, step: ApiStep) {
                        ApplicationManager.getApplication().invokeLater {
                            stepStatuses[step.id] = StepStatus.RUNNING
                            stepTableModel.fireTableDataChanged()
                            selectStepRow(stepIndex)
                            statusBadgeLabel.text = "실행 중..."
                            statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
                        }
                    }

                    override fun onStepCompleted(result: StepExecutionResult) {
                        ApplicationManager.getApplication().invokeLater {
                            stepStatuses[result.stepId] = result.status
                            stepResults[result.stepId] = result
                            lastRuntimeContext.putAll(result.extractedVariables)
                            stepTableModel.fireTableDataChanged()
                            if (result.status == StepStatus.FAILED || stepTable.selectedRow == result.stepIndex) {
                                if (result.status == StepStatus.FAILED) {
                                    selectStepRow(result.stepIndex)
                                }
                                displayStepResult(result)
                            }
                        }
                    }

                    override fun onScenarioCompleted(summary: ScenarioExecutionSummary) {
                        ApplicationManager.getApplication().invokeLater {
                            lastRuntimeContext.clear()
                            lastRuntimeContext.putAll(summary.finalContext)
                            setRunningState(false)

                            when (summary.scenarioStatus) {
                                ScenarioStatus.COMPLETED -> {
                                    scenarioStatusLabel.text = "✅ 완료 (${summary.totalDurationMs} ms)"
                                    scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                                }
                                ScenarioStatus.ABORTED -> {
                                    scenarioStatusLabel.text = "⛔ ABORTED (${summary.totalDurationMs} ms)"
                                    scenarioStatusLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                                    val failedResult = summary.stepResults.firstOrNull { it.status == StepStatus.FAILED }
                                    if (failedResult != null) {
                                        selectStepRow(failedResult.stepIndex)
                                        displayStepResult(failedResult)
                                        resultTabs.selectedIndex = 1 // Focus detailed failure log immediately
                                    }
                                }
                                else -> {
                                    scenarioStatusLabel.text = ""
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun executeCurrentSingleStep() {
        if (isRunning) return
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val activeStep = getActiveEditableStep() ?: return
        val stepIndex = if (isQuickSingleMode) 0 else stepTable.selectedRow.coerceAtLeast(0)

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val scenarioSnapshot = currentScenario.deepCopy()
        val stepSnapshot = activeStep.deepCopy().apply { id = activeStep.id }
        val secrets = storage.loadSecrets()
        val contextCopy = LinkedHashMap(lastRuntimeContext)

        setRunningState(true)
        if (!isQuickSingleMode) {
            stepStatuses[activeStep.id] = StepStatus.RUNNING
            stepTableModel.fireTableDataChanged()
        }
        statusBadgeLabel.text = "단건 실행 중..."
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = scenarioRunner.runSingleStep(
                step = stepSnapshot,
                stepIndex = stepIndex,
                scenario = scenarioSnapshot,
                secrets = secrets,
                runtimeContext = contextCopy
            )

            ApplicationManager.getApplication().invokeLater {
                lastRuntimeContext.putAll(result.extractedVariables)
                if (!isQuickSingleMode) {
                    stepStatuses[result.stepId] = result.status
                    stepResults[result.stepId] = result
                    stepTableModel.fireTableDataChanged()
                }
                setRunningState(false)
                displayStepResult(result)
                if (result.status == StepStatus.FAILED) {
                    resultTabs.selectedIndex = 1
                } else {
                    resultTabs.selectedIndex = 0
                }
            }
        }
    }

    private fun stopExecution() {
        if (!isRunning) return
        stopBtn.isEnabled = false
        scenarioStatusLabel.text = "⏹ 중단 요청 중..."
        scenarioRunner.stop()
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        runAllBtn.isEnabled = !running
        runSingleBtn.isEnabled = !running && getActiveEditableStep() != null
        stopBtn.isEnabled = running
        scenarioComboBox.isEnabled = !running
    }

    // --- Result Rendering ---

    private fun displayStepResult(result: StepExecutionResult?) {
        extractedVarsModel.rowCount = 0

        if (result == null) {
            statusBadgeLabel.text = "대기 중"
            statusBadgeLabel.foreground = JBColor.GRAY
            durationLabel.text = "0 ms"
            responseBodyArea.text = ""
            detailLogArea.text = ""
            return
        }

        val resp = result.response
        if (resp?.statusCode != null) {
            statusBadgeLabel.text = "${resp.statusCode} ${resp.statusMessage}"
            statusBadgeLabel.foreground = when (result.status) {
                StepStatus.SUCCESS -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                StepStatus.FAILED -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                else -> JBColor.GRAY
            }
            durationLabel.text = "${resp.durationMs} ms"
        } else {
            statusBadgeLabel.text = when (result.status) {
                StepStatus.SKIPPED -> "SKIPPED"
                StepStatus.FAILED -> "FAILED (${resp?.statusMessage ?: "ERROR"})"
                StepStatus.RUNNING -> "RUNNING"
                else -> "대기 중"
            }
            statusBadgeLabel.foreground = when (result.status) {
                StepStatus.FAILED -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                else -> JBColor.GRAY
            }
            durationLabel.text = "${resp?.durationMs ?: 0} ms"
        }

        for ((k, v) in result.extractedVariables) {
            extractedVarsModel.addRow(arrayOf(k, v))
        }

        responseBodyArea.text = when {
            resp != null && resp.formattedBody.isNotEmpty() -> resp.formattedBody
            resp != null && resp.rawBody.isNotEmpty() -> resp.rawBody
            result.failureReason != null -> "[오류] ${result.failureReason}"
            else -> ""
        }
        responseBodyArea.caretPosition = 0

        detailLogArea.text = buildDetailLog(result)
        detailLogArea.caretPosition = 0
    }

    private fun buildDetailLog(result: StepExecutionResult): String = buildString {
        appendLine("==================== 실행 요약 ====================")
        appendLine("스텝 상태 : ${result.status}")
        if (result.failureReason != null) {
            appendLine("실패 원인 : ${result.failureReason}")
        }
        if (result.extractionWarnings.isNotEmpty()) {
            appendLine("추출 경고 :")
            result.extractionWarnings.forEach { appendLine("  - $it") }
        }
        appendLine()

        val req = result.requestSnapshot
        if (req != null) {
            appendLine("==================== 요청 전문 ====================")
            appendLine("${req.method} ${req.url}")
            if (req.headers.isNotEmpty()) {
                appendLine("-- Request Headers --")
                req.headers.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (req.body.isNotBlank()) {
                appendLine("-- Request Body --")
                appendLine(req.body)
            }
            appendLine()
        }

        val resp = result.response
        if (resp != null) {
            appendLine("==================== 응답 전문 ====================")
            appendLine("Status   : ${resp.statusCode ?: "N/A"} ${resp.statusMessage}")
            appendLine("Duration : ${resp.durationMs} ms")
            appendLine("Format   : ${if (resp.isJson) "JSON" else "Raw Text / Non-JSON"}")
            if (resp.responseHeaders.isNotEmpty()) {
                appendLine("-- Response Headers --")
                resp.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (resp.rawBody.isNotEmpty()) {
                appendLine("-- Response Body --")
                appendLine(resp.formattedBody.ifEmpty { resp.rawBody })
            }
        }
    }

    override fun dispose() {
        scenarioRunner.stop()
    }

    private fun simpleDocListener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = onChange()
        override fun removeUpdate(e: DocumentEvent?) = onChange()
        override fun changedUpdate(e: DocumentEvent?) = onChange()
    }

    // --- Inner Table Model & Renderers ---

    private inner class StepListTableModel : AbstractTableModel() {
        private val columns = arrayOf("✓", "상태", "Method & Path")

        override fun getRowCount(): Int = currentScenario.steps.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            1 -> StepStatus::class.java
            else -> ApiStep::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val step = currentScenario.steps[rowIndex]
            return when (columnIndex) {
                0 -> step.enabled
                1 -> stepStatuses[step.id] ?: StepStatus.PENDING
                else -> step
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && rowIndex in currentScenario.steps.indices) {
                currentScenario.steps[rowIndex].enabled = (aValue as? Boolean) ?: true
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }
    }

    private class StatusIconCellRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.CENTER
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
            val status = value as? StepStatus ?: StepStatus.PENDING
            icon = statusToIcon(status)
            toolTipText = status.name
            return this
        }

        private fun statusToIcon(status: StepStatus): Icon = when (status) {
            StepStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
            StepStatus.RUNNING -> AllIcons.Process.Step_1
            StepStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
            StepStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
            StepStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
        }
    }

    private class MethodPathCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val step = value as? ApiStep
            val html = if (step != null) {
                val method = step.method.uppercase()
                val colorHex = when (method) {
                    "GET" -> "#2E7D32"
                    "POST" -> "#1565C0"
                    "PUT" -> "#EF6C00"
                    "DELETE" -> "#C62828"
                    "PATCH" -> "#6A1B9A"
                    else -> "#546E7A"
                }
                val pathEscaped = step.url
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                if (!step.enabled) {
                    "<html><span style='color:#9E9E9E'><s><b>$method</b> $pathEscaped</s></span></html>"
                } else {
                    "<html><b style='color:$colorHex'>$method</b> $pathEscaped</html>"
                }
            } else {
                ""
            }
            super.getTableCellRendererComponent(table, html, isSelected, hasFocus, row, column)
            toolTipText = step?.let { "${it.displayTitle()} — ${it.method} ${it.url}" }
            return this
        }
    }
}
