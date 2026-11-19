package com.maekom.apirunner.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.maekom.apirunner.engine.CurlExporter
import com.maekom.apirunner.engine.JsonPathLocator
import com.maekom.apirunner.engine.ScenarioRunListener
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.i18n.PluginLanguage
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.ExtractedEndpoint
import com.maekom.apirunner.model.HttpMethod
import com.maekom.apirunner.model.QueryParamItem
import com.maekom.apirunner.model.ScenarioExecutionSummary
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepExecutionResult
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
import com.maekom.apirunner.storage.RenameStatus
import com.maekom.apirunner.storage.ScenarioStorageService
import com.maekom.apirunner.update.UpdateCheckerService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// [v0.1.3] 시나리오 관리 드롭다운 통합, 30초 디바운스 스마트 자동 저장(Alarm), 프로젝트 전역 변수 3탭 허브,
//          변수명 중복 검증기 연동, 상단 업데이트 확인/새 버전 배지 버튼 통합
// 변경 의도: 툴바를 정돈하고 작업 유실·변수명 충돌·업데이트 캐싱 지연을 근본적으로 해소
class ScenarioToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val TAB_PARAMS = 0
        private const val TAB_BODY = 1
        private const val TAB_HEADERS = 2
        private const val TAB_EXTRACT = 3
        private const val AUTO_SAVE_DEBOUNCE_MS = 30_000
        private val AUTO_SAVE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    private val storage: ScenarioStorageService = project.service()
    private val updateService: UpdateCheckerService = UpdateCheckerService.getInstance()
    private val scenarioRunner = ScenarioRunner()
    private val jsonFormatter = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val dirtyCheckMapper = jacksonObjectMapper()

    // [v0.1.3] ToolWindow Disposable에 바인딩된 경량 Alarm (창/프로젝트 종료 시 자동 해제, 유휴 시 디스크 I/O 0)
    private val autoSaveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var currentScenario: ApiScenario = ApiScenario()
    private var savedScenarioSnapshotJson: String = ""
    private var quickSingleStep: ApiStep? = null
    private var lastQuickEndpoint: ExtractedEndpoint? = null
    private var lastDisplayedResult: StepExecutionResult? = null
    private var isQuickSingleMode: Boolean = false
    private var isRunning: Boolean = false
    private var isUpdatingUi: Boolean = false
    private var isCheckingUpdate: Boolean = false
    private var availableUpdateVersion: String? = updateService.latestAvailableVersion
    private var lastAutoSavedTime: String? = null

    private val stepStatuses = mutableMapOf<String, StepStatus>()
    private val stepResults = mutableMapOf<String, StepExecutionResult>()
    private val lastRuntimeContext = linkedMapOf<String, String>()

    // --- Top Toolbar Controls (WrapLayout 반응형 배치) ---
    private val topToolbarPanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 4))
    private val scenarioLabel = JBLabel()
    private val scenarioComboModel = DefaultComboBoxModel<String>()
    private val scenarioComboBox = ComboBox(scenarioComboModel)
    private val scenarioManageBtn = JButton()
    private val saveBtn = JButton()
    private val importExportBtn = JButton()
    private val globalConfigBtn = JButton()
    private val variablesHelpBtn = JButton()
    private val curlCopyBtn = JButton()
    private val baseUrlLabel = JBLabel()
    private val baseUrlField = JBTextField(16)
    private val ignoreSslCheckbox = JBCheckBox()
    private val runAllBtn = JButton()
    private val stopBtn = JButton()
    private val updateCheckBtn = JButton()
    private val languageToggleBtn = JButton()

    // Quick-Single Mode Banner
    private val quickModeBanner = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
    private val quickModeLabel = JBLabel()
    private val promoteToScenarioBtn = JButton()
    private val exitQuickModeBtn = JButton()

    // --- Left Pane: Step List ---
    private val stepsHeaderLabel = JBLabel()
    private val stepTableModel = StepListTableModel()
    private val stepTable = JBTable(stepTableModel)
    private val moveUpBtn = JButton("▲")
    private val moveDownBtn = JButton("▼")
    private val duplicateStepBtn = JButton()
    private val deleteStepBtn = JButton()
    private val addEmptyStepBtn = JButton()

    // --- Center Pane: Step Detail Editor ---
    private val methodComboBox = ComboBox(HttpMethod.entries.map { it.name }.toTypedArray())
    private val urlField = JBTextField()
    private val expectedStatusLabel = JBLabel()
    private val expectedStatusField = JBTextField("2xx", 6)
    private val runSingleBtn = JButton()
    private val editorTabs = JBTabbedPane()

    // Params Tab: [체크박스(전송 여부)] | [Key] | [Value] | [Description]
    private val paramsTableModel = object : DefaultTableModel(arrayOf<Any>("전송", "Key", "Value", "Description"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val paramsTable = JBTable(paramsTableModel)
    private val addParamBtn = JButton()
    private val removeParamBtn = JButton()
    private val paramsHintLabel = JBLabel()

    // Body Tab
    private val bodyTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
    }
    private val formatJsonBtn = JButton()
    private val bodyHintLabel = JBLabel()

    // Headers Tab
    private val headersTableModel = object : DefaultTableModel(arrayOf<Any>("Header Key", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val headersTable = JBTable(headersTableModel)
    private val addHeaderBtn = JButton()
    private val removeHeaderBtn = JButton()

    // Extract Rules Tab
    private val extractRulesTableModel = object : DefaultTableModel(arrayOf<Any>("변수명 (varName)", "JsonPath", "필수"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 2) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val extractRulesTable = JBTable(extractRulesTableModel)
    private val addRuleBtn = JButton()
    private val removeRuleBtn = JButton()
    private val extractGuideBtn = JButton()
    private val extractHintLabel = JBLabel()

    // --- Right Pane: Result Viewer ---
    private val resultStatusTitleLabel = JBLabel()
    private val statusBadgeLabel = JBLabel()
    private val resultDurationTitleLabel = JBLabel()
    private val durationLabel = JBLabel("0 ms")
    private val scenarioStatusLabel = JBLabel("")
    private val resultTabs = JBTabbedPane()
    private val extractedHeaderLabel = JBLabel()
    private val responseHeaderLabel = JBLabel()
    private val extractFromResponseBtn = JButton()

    private val extractedVarsModel = object : DefaultTableModel(arrayOf<Any>("추출 변수명", "값"), 0) {
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
        installContextMenus()
        applyTranslations()
        initializeScenarios()

        I18nManager.getInstance().addListener(this) {
            ApplicationManager.getApplication().invokeLater {
                applyTranslations()
            }
        }

        updateService.addUpdateListener(this) { newVersion ->
            ApplicationManager.getApplication().invokeLater {
                availableUpdateVersion = newVersion
                refreshUpdateButtonAppearance()
            }
        }
    }

    private fun buildLayout() {
        val northContainer = JPanel(BorderLayout())
        northContainer.add(buildTopToolbar(), BorderLayout.NORTH)
        northContainer.add(buildQuickModeBanner(), BorderLayout.SOUTH)
        add(northContainer, BorderLayout.NORTH)

        val centerRightSplitter = OnePixelSplitter(false, 0.54f).apply {
            firstComponent = buildCenterEditorPane()
            secondComponent = buildRightResultPane()
        }

        val mainSplitter = OnePixelSplitter(false, 0.24f).apply {
            firstComponent = buildLeftStepListPane()
            secondComponent = centerRightSplitter
        }

        add(mainSplitter, BorderLayout.CENTER)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                topToolbarPanel.revalidate()
                quickModeBanner.revalidate()
                revalidate()
            }
        })
    }

    private fun buildTopToolbar(): JPanel {
        topToolbarPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        scenarioComboBox.preferredSize = Dimension(145, 28)
        baseUrlField.preferredSize = Dimension(170, 28)
        stopBtn.isEnabled = false

        scenarioManageBtn.margin = JBUI.insets(2, 7)
        variablesHelpBtn.margin = JBUI.insets(2, 7)
        curlCopyBtn.margin = JBUI.insets(2, 7)
        updateCheckBtn.margin = JBUI.insets(2, 7)
        languageToggleBtn.font = languageToggleBtn.font.deriveFont(Font.BOLD)
        languageToggleBtn.margin = JBUI.insets(2, 8)

        runAllBtn.foreground = JBColor(Color(0x1B, 0x7F, 0x3A), Color(0x6C, 0xD9, 0x7E))
        stopBtn.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))

        topToolbarPanel.add(scenarioLabel)
        topToolbarPanel.add(scenarioComboBox)
        topToolbarPanel.add(scenarioManageBtn)
        topToolbarPanel.add(saveBtn)
        topToolbarPanel.add(importExportBtn)
        topToolbarPanel.add(globalConfigBtn)
        topToolbarPanel.add(variablesHelpBtn)
        topToolbarPanel.add(curlCopyBtn)
        topToolbarPanel.add(baseUrlLabel)
        topToolbarPanel.add(baseUrlField)
        topToolbarPanel.add(ignoreSslCheckbox)
        topToolbarPanel.add(runAllBtn)
        topToolbarPanel.add(stopBtn)
        topToolbarPanel.add(updateCheckBtn)
        topToolbarPanel.add(languageToggleBtn)

        return topToolbarPanel
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

        stepsHeaderLabel.apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4, 6, 4)
        }
        panel.add(stepsHeaderLabel, BorderLayout.NORTH)

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
                maxWidth = 44
                minWidth = 34
                preferredWidth = 38
                cellRenderer = StatusIconCellRenderer()
            }
            columnModel.getColumn(2).cellRenderer = MethodPathCellRenderer()
        }
        panel.add(JBScrollPane(stepTable), BorderLayout.CENTER)

        val bottomBar = createWrapBar()
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

        val topBar = JPanel(BorderLayout(6, 4))
        methodComboBox.preferredSize = Dimension(88, 28)

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        expectedStatusField.preferredSize = Dimension(58, 28)
        rightControls.add(expectedStatusLabel)
        rightControls.add(expectedStatusField)
        rightControls.add(runSingleBtn)

        topBar.add(methodComboBox, BorderLayout.WEST)
        topBar.add(urlField, BorderLayout.CENTER)
        topBar.add(rightControls, BorderLayout.EAST)

        panel.add(topBar, BorderLayout.NORTH)

        // Tab 0: Params ([Checkbox] | [Key] | [Value] | [Description])
        paramsTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                maxWidth = 54
                minWidth = 42
                preferredWidth = 48
            }
            columnModel.getColumn(1).preferredWidth = 140
            columnModel.getColumn(2).preferredWidth = 160
            columnModel.getColumn(3).preferredWidth = 220
        }
        val paramsPanel = JPanel(BorderLayout(0, 4))
        paramsPanel.add(JBScrollPane(paramsTable), BorderLayout.CENTER)
        val paramsBtnBar = createWrapBar()
        paramsHintLabel.foreground = JBColor.GRAY
        paramsBtnBar.add(addParamBtn)
        paramsBtnBar.add(removeParamBtn)
        paramsBtnBar.add(paramsHintLabel)
        paramsPanel.add(paramsBtnBar, BorderLayout.SOUTH)

        // Tab 1: Body (JSON)
        val bodyPanel = JPanel(BorderLayout(0, 4))
        bodyPanel.add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)
        val bodyBtnBar = createWrapBar()
        bodyHintLabel.foreground = JBColor.GRAY
        bodyBtnBar.add(formatJsonBtn)
        bodyBtnBar.add(bodyHintLabel)
        bodyPanel.add(bodyBtnBar, BorderLayout.SOUTH)

        // Tab 2: Headers
        headersTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
        }
        val headersPanel = JPanel(BorderLayout(0, 4))
        headersPanel.add(JBScrollPane(headersTable), BorderLayout.CENTER)
        val headersBtnBar = createWrapBar()
        headersBtnBar.add(addHeaderBtn)
        headersBtnBar.add(removeHeaderBtn)
        headersPanel.add(headersBtnBar, BorderLayout.SOUTH)

        // Tab 3: Extract Rules (+ Guide button)
        extractRulesTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(2).apply {
                maxWidth = 64
                minWidth = 48
                preferredWidth = 56
            }
        }
        val extractPanel = JPanel(BorderLayout(0, 4))
        extractPanel.add(JBScrollPane(extractRulesTable), BorderLayout.CENTER)
        val extractBtnBar = createWrapBar()
        extractGuideBtn.margin = JBUI.insets(2, 7)
        extractHintLabel.foreground = JBColor.GRAY
        extractBtnBar.add(addRuleBtn)
        extractBtnBar.add(removeRuleBtn)
        extractBtnBar.add(extractGuideBtn)
        extractBtnBar.add(extractHintLabel)
        extractPanel.add(extractBtnBar, BorderLayout.SOUTH)

        editorTabs.addTab("Params", paramsPanel)
        editorTabs.addTab("Body (JSON)", bodyPanel)
        editorTabs.addTab("Headers", headersPanel)
        editorTabs.addTab("응답 변수 추출", extractPanel)

        panel.add(editorTabs, BorderLayout.CENTER)
        return panel
    }

    private fun buildRightResultPane(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(4)

        val summaryBar = createWrapBar()
        summaryBar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        statusBadgeLabel.font = statusBadgeLabel.font.deriveFont(Font.BOLD)
        durationLabel.foreground = JBColor.GRAY
        scenarioStatusLabel.font = scenarioStatusLabel.font.deriveFont(Font.BOLD)

        summaryBar.add(resultStatusTitleLabel)
        summaryBar.add(statusBadgeLabel)
        summaryBar.add(resultDurationTitleLabel)
        summaryBar.add(durationLabel)
        summaryBar.add(scenarioStatusLabel)
        panel.add(summaryBar, BorderLayout.NORTH)

        extractedVarsTable.rowHeight = 22
        extractedVarsTable.tableHeader.reorderingAllowed = false
        extractedHeaderLabel.apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4)
        }
        val extractedPanel = JPanel(BorderLayout())
        extractedPanel.add(extractedHeaderLabel, BorderLayout.NORTH)
        extractedPanel.add(JBScrollPane(extractedVarsTable), BorderLayout.CENTER)

        responseHeaderLabel.apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4)
        }
        extractFromResponseBtn.margin = JBUI.insets(2, 7)

        val responseTopBar = createWrapBar()
        responseTopBar.add(responseHeaderLabel)
        responseTopBar.add(extractFromResponseBtn)

        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(responseTopBar, BorderLayout.NORTH)
        responsePanel.add(JBScrollPane(responseBodyArea), BorderLayout.CENTER)

        val resultSplitter = OnePixelSplitter(true, 0.26f).apply {
            firstComponent = extractedPanel
            secondComponent = responsePanel
        }

        val logScroll = JBScrollPane(detailLogArea)

        resultTabs.addTab("응답 / 추출 변수", resultSplitter)
        resultTabs.addTab("실행 및 에러 로그", logScroll)

        panel.add(resultTabs, BorderLayout.CENTER)
        return panel
    }

    private fun createWrapBar(): JPanel {
        val bar = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3))
        bar.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                bar.revalidate()
            }
        })
        return bar
    }

    private fun wireEvents() {
        languageToggleBtn.addActionListener {
            I18nManager.getInstance().toggleLanguage()
        }

        scenarioComboBox.addActionListener {
            if (isUpdatingUi) return@addActionListener
            val selectedName = scenarioComboBox.selectedItem as? String ?: return@addActionListener
            if (selectedName == currentScenario.name && !isQuickSingleMode) return@addActionListener
            handleScenarioSwitchRequest(selectedName)
        }

        scenarioManageBtn.addActionListener { showScenarioManageMenu() }
        saveBtn.addActionListener { saveCurrentScenario(showFeedback = true) }
        importExportBtn.addActionListener { showImportExportMenu() }
        globalConfigBtn.addActionListener { openGlobalConfigDialog() }
        variablesHelpBtn.addActionListener { openVariablesCheatSheet() }
        curlCopyBtn.addActionListener { showCurlCopyMenu() }
        updateCheckBtn.addActionListener { handleUpdateCheckButtonClick() }

        baseUrlField.document.addDocumentListener(simpleDocListener {
            if (!isUpdatingUi) {
                currentScenario.baseUrl = baseUrlField.text.trim()
                updateDirtyIndicator()
            }
        })

        ignoreSslCheckbox.addActionListener {
            if (!isUpdatingUi) {
                currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected
                updateDirtyIndicator()
            }
        }

        runAllBtn.addActionListener { executeFullScenario() }
        stopBtn.addActionListener { stopExecution() }
        runSingleBtn.addActionListener { executeCurrentSingleStep() }

        // Quick single-run banner buttons
        promoteToScenarioBtn.addActionListener {
            val quick = quickSingleStep ?: return@addActionListener
            stopAllTableEdits()
            syncEditorToCurrentStep()
            val copy = quick.deepCopy()
            isQuickSingleMode = false
            quickSingleStep = null
            lastQuickEndpoint = null
            quickModeBanner.isVisible = false
            currentScenario.steps.add(copy)
            stepTableModel.fireTableDataChanged()
            selectStepRow(currentScenario.steps.lastIndex)
            saveCurrentScenario(showFeedback = false)
        }
        exitQuickModeBtn.addActionListener {
            isQuickSingleMode = false
            quickSingleStep = null
            lastQuickEndpoint = null
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

        // Params Tab buttons
        addParamBtn.addActionListener {
            stopTableEditing(paramsTable)
            paramsTableModel.addRow(arrayOf<Any>(true, "", "", ""))
            val row = paramsTableModel.rowCount - 1
            paramsTable.setRowSelectionInterval(row, row)
            paramsTable.editCellAt(row, 1)
            syncEditorToCurrentStep()
        }
        removeParamBtn.addActionListener {
            stopTableEditing(paramsTable)
            val rows = paramsTable.selectedRows
            for (i in rows.indices.reversed()) {
                paramsTableModel.removeRow(rows[i])
            }
            syncEditorToCurrentStep()
        }

        // Body Tab button
        formatJsonBtn.addActionListener {
            val raw = bodyTextArea.text.trim()
            if (raw.isNotEmpty()) {
                try {
                    val tree = jsonFormatter.readTree(raw)
                    bodyTextArea.text = jsonFormatter.writeValueAsString(tree)
                } catch (e: Exception) {
                    Messages.showWarningDialog(
                        project,
                        I18nManager.t("body.invalidJson.msg", e.message ?: ""),
                        I18nManager.t("body.invalidJson.title")
                    )
                }
            }
        }

        // Headers Tab buttons
        addHeaderBtn.addActionListener {
            stopTableEditing(headersTable)
            headersTableModel.addRow(arrayOf<Any>("", ""))
            val row = headersTableModel.rowCount - 1
            headersTable.setRowSelectionInterval(row, row)
            headersTable.editCellAt(row, 0)
            syncEditorToCurrentStep()
        }
        removeHeaderBtn.addActionListener {
            stopTableEditing(headersTable)
            val rows = headersTable.selectedRows
            for (i in rows.indices.reversed()) {
                headersTableModel.removeRow(rows[i])
            }
            syncEditorToCurrentStep()
        }

        // Extract Rules Tab buttons (with automatic unique default name)
        addRuleBtn.addActionListener {
            stopTableEditing(extractRulesTable)
            val existingNames = VariableNameValidator.collectAllExistingNames(
                globalVariables = storage.loadGlobalVariables(),
                scenario = currentScenario
            )
            val defaultName = VariableNameValidator.generateRecommendedName("token", existingNames)
            extractRulesTableModel.addRow(arrayOf<Any>(defaultName, "$.data.token", true))
            val row = extractRulesTableModel.rowCount - 1
            extractRulesTable.setRowSelectionInterval(row, row)
            extractRulesTable.editCellAt(row, 0)
            syncEditorToCurrentStep()
        }
        removeRuleBtn.addActionListener {
            stopTableEditing(extractRulesTable)
            val rows = extractRulesTable.selectedRows
            for (i in rows.indices.reversed()) {
                extractRulesTableModel.removeRow(rows[i])
            }
            syncEditorToCurrentStep()
        }
        extractGuideBtn.addActionListener {
            ExtractGuideDialog(project).show()
        }

        extractFromResponseBtn.addActionListener {
            openExtractRuleFromResponse(useCaretOffset = true)
        }

        // Center Pane auto-sync to step model
        methodComboBox.addActionListener { if (!isUpdatingUi) syncEditorToCurrentStep() }
        urlField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        expectedStatusField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        bodyTextArea.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })

        paramsTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                syncEditorToCurrentStep()
            }
        }
        headersTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                syncEditorToCurrentStep()
            }
        }
        extractRulesTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                if (e.type == TableModelEvent.UPDATE && e.column == 0 && e.firstRow >= 0) {
                    validateInlineExtractRuleVarName(e.firstRow)
                }
                syncEditorToCurrentStep()
            }
        }
    }

    // [v0.1.3] 응답 변수 추출 테이블 직접 입력 시에도 공통 VariableNameValidator로 중복 검증 및 추천명 자동 세팅
    private fun validateInlineExtractRuleVarName(row: Int) {
        if (row !in 0 until extractRulesTableModel.rowCount) return
        val candidate = (extractRulesTableModel.getValueAt(row, 0)?.toString() ?: "").trim()
        if (candidate.isEmpty()) return

        val jsonPath = (extractRulesTableModel.getValueAt(row, 1)?.toString() ?: "").trim()
        val currentStepIndex = if (isQuickSingleMode) -1 else stepTable.selectedRow

        val validation = VariableNameValidator.validate(
            candidateName = candidate,
            globalVariables = storage.loadGlobalVariables(),
            scenario = currentScenario,
            ignoreStepIndex = currentStepIndex,
            ignoreRuleIndex = row,
            jsonPathForSuggestion = jsonPath
        )

        if (!validation.isValid) {
            val choice = Messages.showYesNoDialog(
                project,
                I18nManager.t("validator.conflict.msg", validation.conflictSourceLabel, validation.recommendedName),
                I18nManager.t("validator.title"),
                I18nManager.t("validator.conflict.useRecommended"),
                I18nManager.t("validator.conflict.cancel"),
                Messages.getWarningIcon()
            )
            if (choice == Messages.YES) {
                isUpdatingUi = true
                try {
                    extractRulesTableModel.setValueAt(validation.recommendedName, row, 0)
                } finally {
                    isUpdatingUi = false
                }
            }
        }
    }

    private fun installContextMenus() {
        val varsProvider: () -> List<ScenarioVariableItem> = { collectAllVariablesForMenus() }

        VariableContextMenuSupport.installOnTextComponent(urlField, varsProvider)
        VariableContextMenuSupport.installOnTextComponent(bodyTextArea, varsProvider)
        VariableContextMenuSupport.installOnTableValueColumn(
            table = paramsTable,
            valueColumnIndex = 2,
            scenarioVariablesProvider = varsProvider,
            onValueUpdated = { syncEditorToCurrentStep() }
        )
        VariableContextMenuSupport.installOnTableValueColumn(
            table = headersTable,
            valueColumnIndex = 1,
            scenarioVariablesProvider = varsProvider,
            onValueUpdated = { syncEditorToCurrentStep() }
        )

        responseBodyArea.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowResponseMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowResponseMenu(e)

            private fun maybeShowResponseMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val clickedOffset = responseBodyArea.viewToModel2D(e.point)
                if (clickedOffset >= 0) {
                    responseBodyArea.caretPosition = clickedOffset
                }

                val popup = JPopupMenu()
                val extractItem = JMenuItem(I18nManager.t("result.context.addExtractRule")).apply {
                    isEnabled = responseBodyArea.text.trim().isNotEmpty() && getActiveEditableStep() != null
                    addActionListener { openExtractRuleFromResponse(useCaretOffset = true) }
                }
                popup.add(extractItem)
                popup.addSeparator()
                popup.add(JMenuItem(I18nManager.t("vars.menu.copy")).apply {
                    isEnabled = responseBodyArea.selectedText != null
                    addActionListener { responseBodyArea.copy() }
                })
                popup.show(responseBodyArea, e.x, e.y)
            }
        })
    }

    /**
     * Collects extracted variables (from scenario steps + runtime context) for the Extracted tab & menus.
     */
    private fun collectExtractedVariablesOnly(): List<ScenarioVariableItem> {
        val result = linkedMapOf<String, ScenarioVariableItem>()

        for ((idx, step) in currentScenario.steps.withIndex()) {
            for (rule in step.extractRules) {
                val varName = rule.varName.trim()
                if (varName.isNotEmpty() && !result.containsKey(varName)) {
                    result[varName] = ScenarioVariableItem(
                        name = varName,
                        token = "{{$varName}}",
                        isExtracted = true,
                        currentValue = lastRuntimeContext[varName],
                        sourceLabel = I18nManager.t("vars.extracted.stepSource", idx + 1)
                    )
                }
            }
        }

        for ((k, v) in lastRuntimeContext) {
            if (!result.containsKey(k)) {
                result[k] = ScenarioVariableItem(
                    name = k,
                    token = "{{$k}}",
                    isExtracted = true,
                    currentValue = v,
                    sourceLabel = I18nManager.t("vars.extracted.runtimeSource")
                )
            }
        }

        return result.values.toList()
    }

    /**
     * Collects extracted variables (preceding steps + runtime) AND project-wide Global Variables
     * for right-click insertion menus.
     */
    private fun collectAllVariablesForMenus(): List<ScenarioVariableItem> {
        val result = linkedMapOf<String, ScenarioVariableItem>()
        val activeIndex = if (isQuickSingleMode) currentScenario.steps.size else stepTable.selectedRow.coerceAtLeast(0)

        // 1. Extracted variables from preceding steps
        for (i in 0 until activeIndex.coerceAtMost(currentScenario.steps.size)) {
            for (rule in currentScenario.steps[i].extractRules) {
                val varName = rule.varName.trim()
                if (varName.isNotEmpty() && !result.containsKey(varName)) {
                    result[varName] = ScenarioVariableItem(
                        name = varName,
                        token = "{{$varName}}",
                        isExtracted = true,
                        currentValue = lastRuntimeContext[varName],
                        sourceLabel = I18nManager.t("vars.extracted.stepSource", i + 1)
                    )
                }
            }
        }

        for ((k, v) in lastRuntimeContext) {
            if (!result.containsKey(k)) {
                result[k] = ScenarioVariableItem(
                    name = k,
                    token = "{{$k}}",
                    isExtracted = true,
                    currentValue = v,
                    sourceLabel = I18nManager.t("vars.extracted.runtimeSource")
                )
            }
        }

        // 2. Project-wide enabled Global Variables (.maekom/global-variables.json)
        for (item in storage.loadGlobalVariables()) {
            val key = item.key.trim()
            if (item.enabled && key.isNotEmpty() && !result.containsKey("G:$key")) {
                result["G:$key"] = ScenarioVariableItem(
                    name = key,
                    token = "{{$key}}",
                    isExtracted = false,
                    currentValue = item.value,
                    isSecret = item.secret
                )
            }
        }

        // 3. Legacy per-scenario global variables
        for ((k, v) in currentScenario.globalVariables) {
            val trimmed = k.trim()
            if (trimmed.isNotEmpty() && !result.containsKey("G:$trimmed")) {
                result["G:$trimmed"] = ScenarioVariableItem(
                    name = trimmed,
                    token = "{{$trimmed}}",
                    isExtracted = false,
                    currentValue = v
                )
            }
        }

        return result.values.toList()
    }

    // --- Feature 1: Consolidated Scenario Management Dropdown ---

    private fun showScenarioManageMenu() {
        val newLabel = I18nManager.t("menu.scenarioActions.new")
        val renameLabel = I18nManager.t("menu.scenarioActions.rename")
        val duplicateLabel = I18nManager.t("menu.scenarioActions.duplicate")
        val deleteLabel = I18nManager.t("menu.scenarioActions.delete")
        val actions = listOf(newLabel, renameLabel, duplicateLabel, deleteLabel)

        val popupStep = object : BaseListPopupStep<String>(I18nManager.t("menu.scenarioActions.title"), actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    when (selectedValue) {
                        newLabel -> createNewScenarioDialog()
                        renameLabel -> renameCurrentScenarioDialog()
                        duplicateLabel -> duplicateCurrentScenario()
                        deleteLabel -> deleteCurrentScenario()
                    }
                }
            }
        }
        JBPopupFactory.getInstance()
            .createListPopup(popupStep)
            .showUnderneathOf(scenarioManageBtn)
    }

    private fun renameCurrentScenarioDialog() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val inputName = Messages.showInputDialog(
            project,
            I18nManager.t("dialog.rename.msg"),
            I18nManager.t("dialog.rename.title"),
            Messages.getQuestionIcon(),
            currentScenario.name,
            null
        )?.trim()
        if (inputName.isNullOrBlank()) return
        if (inputName == currentScenario.name) return

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val result = storage.renameScenario(currentScenario, inputName)
        when (result.status) {
            RenameStatus.SUCCESS -> {
                markScenarioClean()
                refreshScenarioComboBox(currentScenario.name)
                scenarioStatusLabel.text = I18nManager.t("status.renamed", result.savedFile?.name ?: currentScenario.name)
                scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            }
            RenameStatus.ALREADY_EXISTS -> {
                Messages.showWarningDialog(
                    project,
                    I18nManager.t("dialog.rename.exists.msg", result.sanitizedName),
                    I18nManager.t("dialog.rename.exists.title")
                )
            }
            RenameStatus.INVALID_NAME -> {
                Messages.showWarningDialog(
                    project,
                    I18nManager.t("dialog.rename.invalid.msg"),
                    I18nManager.t("dialog.rename.title")
                )
            }
            RenameStatus.FAILED -> {
                Messages.showErrorDialog(
                    project,
                    I18nManager.t("dialog.rename.failed.msg"),
                    I18nManager.t("dialog.error.title")
                )
            }
        }
    }

    // --- Feature 4: 3-Tab Variables Hub Dialog ---

    private fun openVariablesCheatSheet() {
        VariableCheatSheetDialog(
            project = project,
            storage = storage,
            currentScenarioProvider = { currentScenario },
            extractedVariablesProvider = { collectExtractedVariablesOnly() },
            onGlobalVariablesChanged = { /* menus read latest storage dynamically */ }
        ).show()
    }

    // --- Feature 5: One-Click Response JSON Field Extraction ---

    private fun openExtractRuleFromResponse(useCaretOffset: Boolean) {
        if (getActiveEditableStep() == null) {
            Messages.showInfoMessage(project, I18nManager.t("extractDialog.noStep.msg"), "Maekom API Runner")
            return
        }

        val jsonText = responseBodyArea.text
        val candidates = JsonPathLocator.extractAllCandidates(jsonText)
        if (candidates.isEmpty()) {
            Messages.showWarningDialog(
                project,
                I18nManager.t("extractDialog.noJson.msg"),
                I18nManager.t("extractDialog.noJson.title")
            )
            return
        }

        val initialCandidate = if (useCaretOffset) {
            JsonPathLocator.locateAtOffset(jsonText, responseBodyArea.caretPosition)
        } else {
            null
        }

        val dialog = AddExtractRuleDialog(
            project = project,
            candidates = candidates,
            initialCandidate = initialCandidate,
            globalVariablesProvider = { storage.loadGlobalVariables() },
            scenarioProvider = { currentScenario }
        )
        if (dialog.showAndGet()) {
            val newRule = dialog.toExtractRule()
            stopTableEditing(extractRulesTable)
            isUpdatingUi = true
            try {
                extractRulesTableModel.addRow(arrayOf<Any>(newRule.varName, newRule.jsonPath, newRule.required))
            } finally {
                isUpdatingUi = false
            }
            val lastRow = extractRulesTableModel.rowCount - 1
            if (lastRow >= 0) {
                extractRulesTable.setRowSelectionInterval(lastRow, lastRow)
            }
            syncEditorToCurrentStep()
            editorTabs.selectedIndex = TAB_EXTRACT

            val statusMsg = I18nManager.t("status.extractRuleAdded", newRule.varName, newRule.jsonPath)
            scenarioStatusLabel.text = statusMsg
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            showBalloon(extractFromResponseBtn, statusMsg, MessageType.INFO)
        }
    }

    // --- Feature 6: Update Check Button ---

    private fun handleUpdateCheckButtonClick() {
        val detected = availableUpdateVersion
        if (!detected.isNullOrBlank()) {
            updateService.invokeIdeaUpdateChecker(project)
            return
        }
        if (isCheckingUpdate) return

        isCheckingUpdate = true
        updateCheckBtn.isEnabled = false
        updateCheckBtn.text = I18nManager.t("toolbar.updateCheck.checking")

        updateService.checkForUpdatesInBackground(
            project = project,
            showNotificationOnNew = false,
            interactive = true
        ) { outcome ->
            isCheckingUpdate = false
            updateCheckBtn.isEnabled = true
            availableUpdateVersion = if (outcome.updateAvailable) outcome.remoteVersion else null
            refreshUpdateButtonAppearance()
        }
    }

    private fun refreshUpdateButtonAppearance() {
        val newVer = availableUpdateVersion
        if (isCheckingUpdate) {
            updateCheckBtn.text = I18nManager.t("toolbar.updateCheck.checking")
            return
        }
        if (!newVer.isNullOrBlank()) {
            updateCheckBtn.text = I18nManager.t("toolbar.updateAvailable", newVer)
            updateCheckBtn.toolTipText = I18nManager.t("toolbar.updateAvailable.tooltip", newVer)
            updateCheckBtn.font = updateCheckBtn.font.deriveFont(Font.BOLD)
            updateCheckBtn.foreground = JBColor(Color(0xD8, 0x43, 0x15), Color(0xFF, 0xAB, 0x40))
        } else {
            updateCheckBtn.text = I18nManager.t("toolbar.updateCheck")
            updateCheckBtn.toolTipText = I18nManager.t("toolbar.updateCheck.tooltip")
            updateCheckBtn.font = updateCheckBtn.font.deriveFont(Font.PLAIN)
            updateCheckBtn.foreground = JBColor.foreground()
        }
        topToolbarPanel.revalidate()
    }

    // --- Copy as cURL ---

    private fun showCurlCopyMenu() {
        val copyStepLabel = I18nManager.t("curl.menu.step")
        val copyScenarioLabel = I18nManager.t("curl.menu.scenario")
        val actions = listOf(copyStepLabel, copyScenarioLabel)

        val popupStep = object : BaseListPopupStep<String>(I18nManager.t("curl.menu.title"), actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    when (selectedValue) {
                        copyStepLabel -> copyCurrentStepAsCurl()
                        copyScenarioLabel -> copyFullScenarioAsCurl()
                    }
                }
            }
        }
        JBPopupFactory.getInstance()
            .createListPopup(popupStep)
            .showUnderneathOf(curlCopyBtn)
    }

    private fun copyCurrentStepAsCurl() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val activeStep = getActiveEditableStep()
        if (activeStep == null) {
            Messages.showInfoMessage(project, I18nManager.t("curl.noStep.msg"), "Maekom API Runner")
            return
        }

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val secrets = storage.loadSecrets()
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()
        val curl = CurlExporter.exportStep(
            step = activeStep,
            scenario = currentScenario,
            runtimeContext = lastRuntimeContext,
            secrets = secrets,
            projectGlobalVariables = projectGlobals
        )
        CopyPasteManager.getInstance().setContents(StringSelection(curl))

        scenarioStatusLabel.text = I18nManager.t("status.curlCopied", activeStep.displayTitle())
        scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        showBalloon(curlCopyBtn, I18nManager.t("curl.copied.step.msg"), MessageType.INFO)
    }

    private fun copyFullScenarioAsCurl() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        if (currentScenario.steps.isEmpty()) {
            Messages.showInfoMessage(project, I18nManager.t("curl.noScenarioSteps.msg"), "Maekom API Runner")
            return
        }

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val secrets = storage.loadSecrets()
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()
        val script = CurlExporter.exportScenario(
            scenario = currentScenario,
            runtimeContext = lastRuntimeContext,
            secrets = secrets,
            projectGlobalVariables = projectGlobals
        )
        CopyPasteManager.getInstance().setContents(StringSelection(script))

        val msg = I18nManager.t("curl.copied.scenario.msg", currentScenario.steps.size)
        scenarioStatusLabel.text = I18nManager.t("status.curlCopied", currentScenario.name)
        scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        showBalloon(curlCopyBtn, msg, MessageType.INFO)
    }

    private fun showBalloon(anchor: Component, message: String, type: MessageType) {
        try {
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(message, type, null)
                .setFadeoutTime(3000)
                .createBalloon()
            balloon.show(RelativePoint(anchor, Point(anchor.width / 2, anchor.height)), Balloon.Position.below)
        } catch (_: Exception) {
            // Safe ignore in headless environments
        }
    }

    // --- KO / EN Instant UI Translation ---

    fun applyTranslations() {
        val lang = I18nManager.getInstance().language

        scenarioLabel.text = I18nManager.t("toolbar.scenario")
        scenarioManageBtn.text = I18nManager.t("toolbar.scenarioManage")
        scenarioManageBtn.toolTipText = I18nManager.t("toolbar.scenarioManage.tooltip")
        saveBtn.text = I18nManager.t("toolbar.save")
        importExportBtn.text = I18nManager.t("toolbar.importExport")
        globalConfigBtn.text = I18nManager.t("toolbar.globalConfig")
        variablesHelpBtn.text = I18nManager.t("toolbar.variables")
        variablesHelpBtn.toolTipText = I18nManager.t("toolbar.variables.tooltip")
        curlCopyBtn.text = I18nManager.t("toolbar.curl")
        curlCopyBtn.toolTipText = I18nManager.t("toolbar.curl.tooltip")
        baseUrlLabel.text = I18nManager.t("toolbar.baseUrl")
        baseUrlField.toolTipText = I18nManager.t("toolbar.baseUrl.tooltip")
        ignoreSslCheckbox.text = I18nManager.t("toolbar.ignoreSsl")
        ignoreSslCheckbox.toolTipText = I18nManager.t("toolbar.ignoreSsl.tooltip")
        runAllBtn.text = I18nManager.t("toolbar.runAll")
        stopBtn.text = I18nManager.t("toolbar.stop")
        refreshUpdateButtonAppearance()
        languageToggleBtn.text = if (lang == PluginLanguage.KO) "🌐 [KO] / EN" else "🌐 KO / [EN]"
        languageToggleBtn.toolTipText = I18nManager.t("toolbar.lang.tooltip")

        val quickEndpoint = lastQuickEndpoint
        quickModeLabel.text = if (quickEndpoint != null) {
            I18nManager.t("quick.banner.detail", quickEndpoint.displayName, quickEndpoint.method, quickEndpoint.path)
        } else {
            I18nManager.t("quick.banner.default")
        }
        promoteToScenarioBtn.text = I18nManager.t("quick.promote")
        exitQuickModeBtn.text = I18nManager.t("quick.exit")

        stepsHeaderLabel.text = I18nManager.t("steps.header")
        updateTableHeaders(
            stepTable,
            I18nManager.t("steps.col.enabled"),
            I18nManager.t("steps.col.status"),
            I18nManager.t("steps.col.endpoint")
        )
        duplicateStepBtn.text = I18nManager.t("steps.duplicate")
        deleteStepBtn.text = I18nManager.t("steps.delete")
        addEmptyStepBtn.text = I18nManager.t("steps.addEmpty")

        expectedStatusLabel.text = I18nManager.t("editor.expectedStatus")
        expectedStatusField.toolTipText = I18nManager.t("editor.expectedStatus.tooltip")
        runSingleBtn.text = I18nManager.t("editor.runSingle")

        editorTabs.setTitleAt(TAB_PARAMS, I18nManager.t("editor.tab.params"))
        editorTabs.setTitleAt(TAB_BODY, I18nManager.t("editor.tab.body"))
        editorTabs.setTitleAt(TAB_HEADERS, I18nManager.t("editor.tab.headers"))
        editorTabs.setTitleAt(TAB_EXTRACT, I18nManager.t("editor.tab.extract"))

        updateTableHeaders(
            paramsTable,
            I18nManager.t("params.col.enabled"),
            I18nManager.t("params.col.key"),
            I18nManager.t("params.col.value"),
            I18nManager.t("params.col.description")
        )
        addParamBtn.text = I18nManager.t("params.add")
        removeParamBtn.text = I18nManager.t("params.remove")
        paramsHintLabel.text = I18nManager.t("params.hint")

        formatJsonBtn.text = I18nManager.t("body.formatJson")
        bodyHintLabel.text = I18nManager.t("body.hint")

        updateTableHeaders(
            headersTable,
            I18nManager.t("headers.col.key"),
            I18nManager.t("headers.col.value")
        )
        addHeaderBtn.text = I18nManager.t("headers.add")
        removeHeaderBtn.text = I18nManager.t("headers.remove")

        updateTableHeaders(
            extractRulesTable,
            I18nManager.t("extract.col.varName"),
            I18nManager.t("extract.col.jsonPath"),
            I18nManager.t("extract.col.required")
        )
        addRuleBtn.text = I18nManager.t("extract.add")
        removeRuleBtn.text = I18nManager.t("extract.remove")
        extractGuideBtn.text = I18nManager.t("extract.guide")
        extractGuideBtn.toolTipText = I18nManager.t("extract.guide.tooltip")
        extractHintLabel.text = I18nManager.t("extract.hint")

        resultStatusTitleLabel.text = I18nManager.t("result.statusLabel")
        resultDurationTitleLabel.text = I18nManager.t("result.durationLabel")
        resultTabs.setTitleAt(0, I18nManager.t("result.tab.response"))
        resultTabs.setTitleAt(1, I18nManager.t("result.tab.log"))
        extractedHeaderLabel.text = I18nManager.t("result.extractedHeader")
        responseHeaderLabel.text = I18nManager.t("result.responseHeader")
        extractFromResponseBtn.text = I18nManager.t("result.extractFromResponse")
        extractFromResponseBtn.toolTipText = I18nManager.t("result.extractFromResponse.tooltip")
        updateTableHeaders(
            extractedVarsTable,
            I18nManager.t("result.extractedCol.name"),
            I18nManager.t("result.extractedCol.value")
        )

        if (lastDisplayedResult != null) {
            displayStepResult(lastDisplayedResult)
        } else if (!isRunning) {
            statusBadgeLabel.text = if (isQuickSingleMode) {
                I18nManager.t("result.quickReady")
            } else {
                I18nManager.t("result.idle")
            }
        }
        val autoTime = lastAutoSavedTime
        if (!isScenarioDirty() && autoTime != null && !isRunning) {
            scenarioStatusLabel.text = I18nManager.t("status.autoSaved", autoTime)
            scenarioStatusLabel.foreground = JBColor.GRAY
        } else {
            updateDirtyIndicator()
        }
        topToolbarPanel.revalidate()
        revalidate()
        repaint()
    }

    private fun updateTableHeaders(table: JBTable, vararg titles: String) {
        val columnModel = table.columnModel
        for (i in titles.indices) {
            if (i < columnModel.columnCount) {
                columnModel.getColumn(i).headerValue = titles[i]
            }
        }
        table.tableHeader?.repaint()
    }

    // --- Feature 3: Dirty Check & 30s Debounced Smart Auto-Save ---

    private fun serializeScenarioForDirtyCheck(scenario: ApiScenario): String {
        return try {
            dirtyCheckMapper.writeValueAsString(scenario)
        } catch (_: Exception) {
            scenario.toString()
        }
    }

    private fun markScenarioClean() {
        autoSaveAlarm.cancelAllRequests()
        savedScenarioSnapshotJson = serializeScenarioForDirtyCheck(currentScenario)
    }

    fun isScenarioDirty(): Boolean {
        if (savedScenarioSnapshotJson.isEmpty()) return false
        return serializeScenarioForDirtyCheck(currentScenario) != savedScenarioSnapshotJson
    }

    private fun updateDirtyIndicator() {
        if (isRunning) return
        if (isScenarioDirty()) {
            lastAutoSavedTime = null
            scenarioStatusLabel.text = I18nManager.t("status.unsaved")
            scenarioStatusLabel.foreground = JBColor(Color(0xE6, 0x51, 0x00), Color(0xFF, 0xB7, 0x4D))
            scheduleAutoSaveIfDirty()
        } else {
            autoSaveAlarm.cancelAllRequests()
            if (scenarioStatusLabel.text == I18nManager.t("status.unsaved") ||
                scenarioStatusLabel.text == "● 미저장 변경사항" ||
                scenarioStatusLabel.text == "● Unsaved changes"
            ) {
                scenarioStatusLabel.text = ""
            }
        }
    }

    // [v0.1.3] Dirty 상태일 때만 마지막 수정 시점으로부터 30초 디바운스 후 조용히 저장 (Idle 시 디스크 I/O 0)
    private fun scheduleAutoSaveIfDirty() {
        if (isQuickSingleMode || isRunning || autoSaveAlarm.isDisposed) return
        autoSaveAlarm.cancelAllRequests()
        autoSaveAlarm.addRequest({ performAutoSave() }, AUTO_SAVE_DEBOUNCE_MS)
    }

    private fun performAutoSave() {
        if (project.isDisposed || isRunning || isQuickSingleMode) return
        if (!isScenarioDirty()) return

        // Avoid disrupting an active table cell edit; reschedule briefly if editing
        if (stepTable.isEditing || paramsTable.isEditing || headersTable.isEditing || extractRulesTable.isEditing) {
            scheduleAutoSaveIfDirty()
            return
        }

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected
        storage.saveScenario(currentScenario)
        markScenarioClean()
        refreshScenarioComboBox(currentScenario.name)

        val nowText = LocalTime.now().format(AUTO_SAVE_TIME_FMT)
        lastAutoSavedTime = nowText
        scenarioStatusLabel.text = I18nManager.t("status.autoSaved", nowText)
        scenarioStatusLabel.foreground = JBColor.GRAY
    }

    private fun handleScenarioSwitchRequest(targetScenarioName: String) {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        currentScenario.baseUrl = baseUrlField.text.trim()
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        if (!isScenarioDirty()) {
            loadScenarioByName(targetScenarioName)
            return
        }

        val choice = Messages.showYesNoCancelDialog(
            project,
            I18nManager.t("dialog.dirty.message"),
            I18nManager.t("dialog.dirty.title"),
            I18nManager.t("dialog.dirty.save"),
            I18nManager.t("dialog.dirty.discard"),
            I18nManager.t("dialog.dirty.cancel"),
            Messages.getQuestionIcon()
        )

        when (choice) {
            Messages.YES -> {
                saveCurrentScenario(showFeedback = false)
                loadScenarioByName(targetScenarioName)
            }
            Messages.NO -> {
                loadScenarioByName(targetScenarioName)
            }
            else -> {
                isUpdatingUi = true
                try {
                    scenarioComboBox.selectedItem = currentScenario.name
                } finally {
                    isUpdatingUi = false
                }
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
        lastAutoSavedTime = null
        markScenarioClean()
        scenarioStatusLabel.text = ""
    }

    // --- Public API invoked by Gutter Marker via ScenarioService ---

    fun loadQuickSingleRun(endpoint: ExtractedEndpoint) {
        val step = endpoint.toApiStep()
        quickSingleStep = step
        lastQuickEndpoint = endpoint
        isQuickSingleMode = true

        isUpdatingUi = true
        try {
            if (currentScenario.baseUrl.isBlank() || currentScenario.baseUrl == "http://localhost:8080") {
                currentScenario.baseUrl = endpoint.baseUrl
                baseUrlField.text = endpoint.baseUrl
            }
            quickModeLabel.text = I18nManager.t(
                "quick.banner.detail",
                endpoint.displayName,
                endpoint.method,
                endpoint.path
            )
            quickModeBanner.isVisible = true
            setEditorEnabled(true)
            populateEditorFields(step)
            selectPreferredEditorTab(step)
        } finally {
            isUpdatingUi = false
        }

        displayStepResult(null)
        statusBadgeLabel.text = I18nManager.t("result.quickReady")
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        scenarioStatusLabel.text = ""
    }

    fun addStepFromEndpoint(endpoint: ExtractedEndpoint) {
        isQuickSingleMode = false
        quickSingleStep = null
        lastQuickEndpoint = null
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
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.loadFail.msg", name),
                I18nManager.t("dialog.loadFail.title")
            )
            return
        }
        autoSaveAlarm.cancelAllRequests()
        isQuickSingleMode = false
        quickSingleStep = null
        lastQuickEndpoint = null
        quickModeBanner.isVisible = false
        lastAutoSavedTime = null

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
        markScenarioClean()
        scenarioStatusLabel.text = ""
    }

    private fun saveCurrentScenario(showFeedback: Boolean) {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val savedFile = storage.saveScenario(currentScenario)
        lastAutoSavedTime = null
        markScenarioClean()
        refreshScenarioComboBox(currentScenario.name)
        if (showFeedback) {
            scenarioStatusLabel.text = I18nManager.t("status.saved", savedFile.name)
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
    }

    private fun createNewScenarioDialog() {
        val inputName = Messages.showInputDialog(
            project,
            I18nManager.t("dialog.newScenario.msg"),
            I18nManager.t("dialog.newScenario.title"),
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
        val importLabel = I18nManager.t("menu.import")
        val exportLabel = I18nManager.t("menu.export")
        val actions = listOf(importLabel, exportLabel)

        val popupStep = object : BaseListPopupStep<String>(I18nManager.t("menu.importExport.title"), actions) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    when (selectedValue) {
                        importLabel -> importExternalScenario()
                        exportLabel -> exportCurrentScenario()
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
            title = I18nManager.t("dialog.import.title")
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        try {
            val imported = storage.importScenario(File(chosen.path))
            refreshScenarioComboBox(imported.name)
            loadScenarioByName(imported.name)
            Messages.showInfoMessage(
                project,
                I18nManager.t("dialog.import.okMsg", imported.name),
                I18nManager.t("dialog.import.okTitle")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.import.errMsg", e.message ?: ""),
                I18nManager.t("dialog.error.title")
            )
        }
    }

    private fun exportCurrentScenario() {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        val saverDescriptor = FileSaverDescriptor(
            I18nManager.t("dialog.export.title"),
            I18nManager.t("dialog.export.desc"),
            "json"
        )
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(saverDescriptor, project)
        val wrapper = saveDialog.save("${currentScenario.name}.json") ?: return
        try {
            storage.exportScenario(currentScenario, wrapper.file)
            Messages.showInfoMessage(
                project,
                I18nManager.t("dialog.export.okMsg", wrapper.file.absolutePath),
                I18nManager.t("dialog.export.okTitle")
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.export.errMsg", e.message ?: ""),
                I18nManager.t("dialog.error.title")
            )
        }
    }

    private fun duplicateCurrentScenario() {
        saveCurrentScenario(showFeedback = false)
        val newName = Messages.showInputDialog(
            project,
            I18nManager.t("dialog.duplicate.msg"),
            I18nManager.t("dialog.duplicate.title"),
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
            I18nManager.t("dialog.delete.msg", name),
            I18nManager.t("dialog.delete.title"),
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        autoSaveAlarm.cancelAllRequests()
        storage.deleteScenario(name)

        val remaining = storage.listScenarios()
        if (remaining.isEmpty()) {
            val detectedBaseUrl = PsiEndpointExtractor.detectServerBaseUrl(project)
            val freshDefault = ApiScenario(name = "default", baseUrl = detectedBaseUrl)
            storage.saveScenario(freshDefault)
            refreshScenarioComboBox(freshDefault.name)
            loadScenarioByName(freshDefault.name)
            Messages.showInfoMessage(project, I18nManager.t("dialog.delete.lastReset"), I18nManager.t("dialog.delete.title"))
        } else {
            refreshScenarioComboBox(remaining.first())
            loadScenarioByName(remaining.first())
        }
    }

    private fun openGlobalConfigDialog() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val currentSecrets = storage.loadSecrets()
        val dialog = GlobalConfigDialog(
            project = project,
            currentScenarioName = currentScenario.name,
            storage = storage,
            initialCommonHeaders = currentScenario.effectiveCommonHeaderItems(),
            initialVariables = currentScenario.globalVariables,
            initialSecrets = currentSecrets
        )
        if (dialog.showAndGet()) {
            currentScenario.applyCommonHeaderItems(dialog.getCommonHeaderItems())
            currentScenario.globalVariables = dialog.getGlobalVariables()
            storage.saveSecrets(dialog.getSecrets())
            saveCurrentScenario(showFeedback = false)
            scenarioStatusLabel.text = I18nManager.t("status.globalSaved")
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
        updateDirtyIndicator()
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
        updateDirtyIndicator()
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
        updateDirtyIndicator()
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
        updateDirtyIndicator()
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
                paramsTableModel.rowCount = 0
                headersTableModel.rowCount = 0
                bodyTextArea.text = ""
                extractRulesTableModel.rowCount = 0
                setEditorEnabled(false)
            } else {
                setEditorEnabled(true)
                populateEditorFields(step)
                selectPreferredEditorTab(step)
            }
        } finally {
            isUpdatingUi = false
        }
    }

    private fun selectPreferredEditorTab(step: ApiStep) {
        when {
            step.method.equals("GET", ignoreCase = true) || step.queryParams.isNotEmpty() -> {
                editorTabs.selectedIndex = TAB_PARAMS
            }
            step.body.isNotBlank() -> {
                editorTabs.selectedIndex = TAB_BODY
            }
        }
    }

    private fun populateEditorFields(step: ApiStep) {
        methodComboBox.selectedItem = HttpMethod.fromString(step.method).name
        urlField.text = step.url
        expectedStatusField.text = step.expectedStatus.ifBlank { "2xx" }

        paramsTableModel.rowCount = 0
        for (param in step.queryParams) {
            paramsTableModel.addRow(arrayOf<Any>(param.enabled, param.key, param.value, param.description))
        }

        bodyTextArea.text = step.body
        bodyTextArea.caretPosition = 0

        headersTableModel.rowCount = 0
        for ((k, v) in step.headers) {
            headersTableModel.addRow(arrayOf<Any>(k, v))
        }

        extractRulesTableModel.rowCount = 0
        for (rule in step.extractRules) {
            extractRulesTableModel.addRow(arrayOf<Any>(rule.varName, rule.jsonPath, rule.required))
        }
    }

    private fun setEditorEnabled(enabled: Boolean) {
        methodComboBox.isEnabled = enabled
        urlField.isEnabled = enabled
        expectedStatusField.isEnabled = enabled
        runSingleBtn.isEnabled = enabled && !isRunning
        paramsTable.isEnabled = enabled
        addParamBtn.isEnabled = enabled
        removeParamBtn.isEnabled = enabled
        headersTable.isEnabled = enabled
        addHeaderBtn.isEnabled = enabled
        removeHeaderBtn.isEnabled = enabled
        bodyTextArea.isEnabled = enabled
        formatJsonBtn.isEnabled = enabled
        extractRulesTable.isEnabled = enabled
        addRuleBtn.isEnabled = enabled
        removeRuleBtn.isEnabled = enabled
    }

    private fun syncEditorToCurrentStep() {
        val step = getActiveEditableStep() ?: return
        step.method = methodComboBox.selectedItem?.toString() ?: "GET"
        step.url = urlField.text
        step.expectedStatus = expectedStatusField.text.trim().ifEmpty { "2xx" }
        step.body = bodyTextArea.text

        val params = mutableListOf<QueryParamItem>()
        for (i in 0 until paramsTableModel.rowCount) {
            val enabled = (paramsTableModel.getValueAt(i, 0) as? Boolean) ?: true
            val key = (paramsTableModel.getValueAt(i, 1)?.toString() ?: "").trim()
            val value = paramsTableModel.getValueAt(i, 2)?.toString() ?: ""
            val desc = paramsTableModel.getValueAt(i, 3)?.toString() ?: ""
            if (key.isNotEmpty() || value.isNotEmpty() || desc.isNotEmpty()) {
                params.add(QueryParamItem(enabled = enabled, key = key, value = value, description = desc))
            }
        }
        step.queryParams = params

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
            updateDirtyIndicator()
        }
    }

    private fun stopAllTableEdits() {
        stopTableEditing(stepTable)
        stopTableEditing(paramsTable)
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
            Messages.showInfoMessage(project, I18nManager.t("dialog.noSteps.msg"), "Maekom API Runner")
            return
        }

        isQuickSingleMode = false
        quickModeBanner.isVisible = false

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val scenarioSnapshot = currentScenario.deepCopy()
        for (i in currentScenario.steps.indices) {
            scenarioSnapshot.steps[i].id = currentScenario.steps[i].id
        }
        val secrets = storage.loadSecrets()
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()

        stepStatuses.clear()
        stepResults.clear()
        lastRuntimeContext.clear()
        currentScenario.steps.forEach { stepStatuses[it.id] = StepStatus.PENDING }
        stepTableModel.fireTableDataChanged()

        setRunningState(true)
        scenarioStatusLabel.text = I18nManager.t("status.scenarioRunning")
        scenarioStatusLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))

        ApplicationManager.getApplication().executeOnPooledThread {
            scenarioRunner.runScenario(
                scenario = scenarioSnapshot,
                secrets = secrets,
                projectGlobalVariables = projectGlobals,
                listener = object : ScenarioRunListener {
                    override fun onStepStarted(stepIndex: Int, step: ApiStep) {
                        ApplicationManager.getApplication().invokeLater {
                            stepStatuses[step.id] = StepStatus.RUNNING
                            stepTableModel.fireTableDataChanged()
                            selectStepRow(stepIndex)
                            statusBadgeLabel.text = I18nManager.t("result.running")
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
                                    scenarioStatusLabel.text = I18nManager.t("status.scenarioCompleted", summary.totalDurationMs)
                                    scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                                }
                                ScenarioStatus.ABORTED -> {
                                    scenarioStatusLabel.text = I18nManager.t("status.scenarioAborted", summary.totalDurationMs)
                                    scenarioStatusLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                                    val failedResult = summary.stepResults.firstOrNull { it.status == StepStatus.FAILED }
                                    if (failedResult != null) {
                                        selectStepRow(failedResult.stepIndex)
                                        displayStepResult(failedResult)
                                        resultTabs.selectedIndex = 1
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
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()
        val contextCopy = LinkedHashMap(lastRuntimeContext)

        setRunningState(true)
        if (!isQuickSingleMode) {
            stepStatuses[activeStep.id] = StepStatus.RUNNING
            stepTableModel.fireTableDataChanged()
        }
        statusBadgeLabel.text = I18nManager.t("result.singleRunning")
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = scenarioRunner.runSingleStep(
                step = stepSnapshot,
                stepIndex = stepIndex,
                scenario = scenarioSnapshot,
                secrets = secrets,
                runtimeContext = contextCopy,
                projectGlobalVariables = projectGlobals
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
        scenarioStatusLabel.text = I18nManager.t("status.stopping")
        scenarioRunner.stop()
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        runAllBtn.isEnabled = !running
        runSingleBtn.isEnabled = !running && getActiveEditableStep() != null
        stopBtn.isEnabled = running
        scenarioComboBox.isEnabled = !running
        scenarioManageBtn.isEnabled = !running
    }

    // --- Result Rendering ---

    private fun displayStepResult(result: StepExecutionResult?) {
        lastDisplayedResult = result
        extractedVarsModel.rowCount = 0

        if (result == null) {
            statusBadgeLabel.text = I18nManager.t("result.idle")
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
                StepStatus.RUNNING -> I18nManager.t("result.running")
                else -> I18nManager.t("result.idle")
            }
            statusBadgeLabel.foreground = when (result.status) {
                StepStatus.FAILED -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                else -> JBColor.GRAY
            }
            durationLabel.text = "${resp?.durationMs ?: 0} ms"
        }

        for ((k, v) in result.extractedVariables) {
            extractedVarsModel.addRow(arrayOf<Any>(k, v))
        }

        responseBodyArea.text = when {
            resp != null && resp.formattedBody.isNotEmpty() -> resp.formattedBody
            resp != null && resp.rawBody.isNotEmpty() -> resp.rawBody
            result.failureReason != null -> "[ERROR] ${result.failureReason}"
            else -> ""
        }
        responseBodyArea.caretPosition = 0

        detailLogArea.text = buildDetailLog(result)
        detailLogArea.caretPosition = 0
    }

    private fun buildDetailLog(result: StepExecutionResult): String = buildString {
        appendLine(I18nManager.t("log.summaryHeader"))
        appendLine(I18nManager.t("log.stepStatus", result.status))
        if (result.failureReason != null) {
            appendLine(I18nManager.t("log.failReason", result.failureReason))
        }
        if (result.extractionWarnings.isNotEmpty()) {
            appendLine(I18nManager.t("log.warnings"))
            result.extractionWarnings.forEach { appendLine("  - $it") }
        }
        appendLine()

        val req = result.requestSnapshot
        if (req != null) {
            appendLine(I18nManager.t("log.requestHeader"))
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
            appendLine(I18nManager.t("log.responseHeader"))
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
        autoSaveAlarm.cancelAllRequests()
        scenarioRunner.stop()
    }

    private fun simpleDocListener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = onChange()
        override fun removeUpdate(e: DocumentEvent?) = onChange()
        override fun changedUpdate(e: DocumentEvent?) = onChange()
    }

    // --- Inner Table Model & Renderers ---

    private inner class StepListTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = currentScenario.steps.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = when (column) {
            0 -> I18nManager.t("steps.col.enabled")
            1 -> I18nManager.t("steps.col.status")
            else -> I18nManager.t("steps.col.endpoint")
        }

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
                updateDirtyIndicator()
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
