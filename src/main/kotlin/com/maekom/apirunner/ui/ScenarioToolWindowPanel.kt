package com.maekom.apirunner.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import com.maekom.apirunner.engine.JsonPathLocator
import com.maekom.apirunner.engine.ParsedCurlResult
import com.maekom.apirunner.engine.ScenarioRunListener
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.engine.ScenarioVariableSyncEngine
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.i18n.PluginLanguage
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.AssertionOperator
import com.maekom.apirunner.model.AssertionRule
import com.maekom.apirunner.model.EnvironmentConfig
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.ExtractedEndpoint
import com.maekom.apirunner.model.HttpMethod
import com.maekom.apirunner.model.QueryParamItem
import com.maekom.apirunner.model.ScenarioExecutionSummary
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepExecutionResult
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
import com.maekom.apirunner.storage.ScenarioFileRepository
import com.maekom.apirunner.storage.ScenarioStorageService
import com.maekom.apirunner.update.UpdateCheckerService
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.JTableHeader
import javax.swing.text.JTextComponent

// [v0.4.0] [cURL] 통합 모달(내보내기 미리보기 + 스마트 가져오기), 응답 본문 검증(No-Code Assertion) 탭,
//          단건 실행 선행 변수 누락 감지 가드 및 추출 변수 수동 Override 기능 통합
// 변경 의도: 기존 .maekom/scenarios/**/*.json 100% 하위 호환을 유지하면서 실무 API 테스트·디버깅 생산성을 극대화
class ScenarioToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val TAB_PARAMS = 0
        private const val TAB_BODY = 1
        private const val TAB_HEADERS = 2
        private const val TAB_EXTRACT = 3
        private const val TAB_ASSERTIONS = 4
        private const val RESULT_TAB_RESPONSE = 0
        private const val RESULT_TAB_EXTRACTED = 1
        private const val RESULT_TAB_LOG = 2
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
    private var currentScenarioPath: String = "default"
    private var environmentConfig: EnvironmentConfig = EnvironmentConfig()
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
    private val runGeneration = AtomicLong(0)

    private val stepStatuses = mutableMapOf<String, StepStatus>()
    private val stepResults = mutableMapOf<String, StepExecutionResult>()
    private val lastRuntimeContext = linkedMapOf<String, String>()

    // --- Native ToolWindow Title Bar Components (v0.2.2) ---
    // 좌측(Maekom API 타이틀 바로 옆 setTabActions): [업데이트 확인] + [한국어 | English] 세그먼트 스위치
    // 우측(⋮ / — 아이콘 바로 왼쪽 setTitleActions): 상태: 200 OK | 소요: 0 ms | 저장 상태
    private val nativeLeftHeaderPanel = JPanel(GridBagLayout())
    private val nativeRightStatusPanel = JPanel(GridBagLayout())
    private val updateCheckBtn = JButton()
    private val languageSegmentSwitch = LanguageSegmentSwitch(I18nManager.getInstance().language) { chosen ->
        if (!isUpdatingUi) {
            I18nManager.getInstance().language = chosen
        }
    }

    // --- Top Toolbar Controls (WrapLayout 반응형 배치) ---
    private val topToolbarPanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 4))
    private val scenarioLabel = JBLabel()
    private val scenarioComboModel = DefaultComboBoxModel<String>()
    private val scenarioComboBox = ComboBox(scenarioComboModel)
    private val saveBtn = JButton()
    private val scenarioManageBtn = JButton()
    private val globalConfigBtn = JButton()
    private val curlCopyBtn = JButton()
    private val runAllBtn = JButton()
    private val stopBtn = JButton()

    // --- Environment Profile Controls (BaseURL 행 앞쪽 배치) ---
    private val baseUrlBarPanel = JPanel(GridBagLayout())
    private val envComboModel = DefaultComboBoxModel<String>()
    private val envComboBox = ComboBox(envComboModel)
    private val envManageBtn = JButton()
    private val envProtectBadgeLabel = JBLabel()
    private val baseUrlLabel = JBLabel()
    private val baseUrlField = JBTextField(16)
    private val ignoreSslCheckbox = JBCheckBox()

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
    private val stepAliasLabel = JBLabel()
    private val stepAliasField = JBTextField()
    private val methodComboBox = ComboBox(HttpMethod.entries.map { it.name }.toTypedArray())
    private val urlField = JBTextField()
    private val expectedStatusLabel = JBLabel()
    private val expectedStatusField = JBTextField("2xx", 5)
    private val delayLabel = JBLabel()
    private val delayField = JBTextField("0", 5)
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

    // [v0.4.0] Assertions Tab: [활성(✓)] | [JsonPath] | [연산자] | [기대값(Expected)]
    private val assertionsTableModel = object : DefaultTableModel(arrayOf<Any>("활성", "JsonPath", "연산자", "기대값 (Expected)"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val assertionsTable = JBTable(assertionsTableModel)
    private val addAssertionBtn = JButton()
    private val addAssertionFromResponseBtn = JButton()
    private val removeAssertionBtn = JButton()
    private val assertionHintLabel = JBLabel()

    // --- Right Pane: Result Viewer ---
    private val resultStatusTitleLabel = JBLabel()
    private val statusBadgeLabel = JBLabel()
    private val resultDurationTitleLabel = JBLabel()
    private val durationLabel = JBLabel("0 ms")
    private val scenarioStatusLabel = JBLabel("")
    private val resultTabs = JBTabbedPane()
    private val extractedHeaderLabel = JBLabel()
    private val extractedOverrideHintLabel = JBLabel()
    private val addOverrideVarBtn = JButton()
    private val removeOverrideVarBtn = JButton()
    private val responseHeaderLabel = JBLabel()
    private val extractFromResponseBtn = JButton()
    private val clearLogBtn = JButton()
    private val executionLogBuffer = StringBuilder()

    // [v0.4.0] 추출 변수 전용 탭 테이블: Value(컬럼 1) 더블클릭 편집으로 메모리 세션 수동 Override 지원
    private val extractedVarsModel = object : DefaultTableModel(arrayOf<Any>("추출 변수명", "추출된 값", "복사"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = (column == 1)
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

    // [v0.4.5] 응답 본문(JSON/Raw Text) 에디터·입력 필드 포커스 잔류(파란 테두리) 완전 해제용 0x0 비가시 포커스 흡수 컴포넌트
    // 변경 의도: clearGlobalFocusOwner()만으로는 IntelliJ Darcula LAF의 JScrollPane/EditorTextField 포커스 링이 남는 문제를 해결하기 위해
    //          비가시 포커스 싱크로 포커스를 확실히 회수하고 직전 포커스 소유자 및 상위 래퍼 컨테이너를 명시적 repaint() 처리
    private val focusSinkComponent = JPanel().apply {
        isFocusable = true
        isOpaque = false
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }

    // [v0.4.5] 메인 패널 내 입력 필드/응답 본문 에디터 바깥의 빈 배경·라벨·여백 좌클릭 시 포커스 회수 및 Darcula 포커스 테두리 즉시 제거
    private val backgroundFocusClearListener = AWTEventListener { event ->
        val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
        if (mouseEvent.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        if (!SwingUtilities.isLeftMouseButton(mouseEvent) || mouseEvent.isPopupTrigger) return@AWTEventListener

        val clickedComp = mouseEvent.component ?: return@AWTEventListener
        if (!SwingUtilities.isDescendingFrom(clickedComp, this@ScenarioToolWindowPanel)) return@AWTEventListener
        if (isInteractiveTarget(clickedComp)) return@AWTEventListener

        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val oldFocusOwner = focusManager.focusOwner
        if (oldFocusOwner == null || oldFocusOwner === focusSinkComponent) {
            if (isAnyTableEditing()) stopAllTableEdits()
            return@AWTEventListener
        }
        if (!SwingUtilities.isDescendingFrom(oldFocusOwner, this@ScenarioToolWindowPanel)) return@AWTEventListener

        val repaintTargets = collectFocusWrapperAncestors(oldFocusOwner)
        if (isAnyTableEditing()) {
            stopAllTableEdits()
        }
        if (!focusSinkComponent.requestFocusInWindow()) {
            focusManager.clearGlobalFocusOwner()
        }
        repaintTargets.forEach { it.repaint() }
        SwingUtilities.invokeLater {
            repaintTargets.forEach { it.repaint() }
        }
    }

    init {
        buildLayout()
        wireEvents()
        installContextMenus()
        applyTranslations()
        initializeScenarios()

        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(backgroundFocusClearListener, AWTEvent.MOUSE_EVENT_MASK)
        }

        I18nManager.getInstance().addListener(this) {
            ApplicationManager.getApplication().invokeLater {
                applyTranslations()
            }
        }

        updateService.addUpdateListener(this) { newVersion ->
            ApplicationManager.getApplication().invokeLater(
                {
                    availableUpdateVersion = newVersion
                    refreshUpdateButtonAppearance()
                },
                ModalityState.any()
            )
        }
    }

    private fun buildLayout() {
        // [v0.2.2] 커스텀 중복 헤더 행을 제거하고 네이티브 ToolWindow 타이틀 바 전용 컴포넌트를 초기화.
        //          컨텐츠 영역은 곧바로 '시나리오: [ComboBox]' 글로벌 툴바부터 시작한다.
        buildNativeHeaderComponents()

        val northContainer = JPanel(BorderLayout())
        northContainer.add(buildTopToolbar(), BorderLayout.NORTH)
        northContainer.add(buildQuickModeBanner(), BorderLayout.SOUTH)
        add(northContainer, BorderLayout.NORTH)

        val centerRightSplitter = OnePixelSplitter(false, 0.54f).apply {
            firstComponent = buildCenterEditorPane()
            secondComponent = buildRightResultPane()
        }

        val mainSplitter = OnePixelSplitter(false, 0.28f).apply {
            firstComponent = buildLeftStepListPane()
            secondComponent = centerRightSplitter
        }

        add(mainSplitter, BorderLayout.CENTER)
        add(focusSinkComponent, BorderLayout.SOUTH)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                topToolbarPanel.revalidate()
                quickModeBanner.revalidate()
                revalidate()
            }
        })
    }

    // [v0.2.2] IntelliJ 네이티브 ToolWindow 타이틀 바에 직접 부착되는 좌/우 컴포넌트 그룹 구성
    // 변경 의도: GridBagLayout 수직 중앙 정렬로 New UI / Classic UI 헤더 높이에 맞춰 여백·정렬·실시간 폭 갱신을 일관되게 보장
    private fun buildNativeHeaderComponents() {
        updateCheckBtn.apply {
            margin = JBUI.insets(1, 7)
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        nativeLeftHeaderPanel.apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 0, 4)
            add(
                updateCheckBtn,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 8)
                }
            )
            add(
                languageSegmentSwitch,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 2)
                }
            )
        }

        statusBadgeLabel.font = statusBadgeLabel.font.deriveFont(Font.BOLD)
        durationLabel.foreground = JBColor.GRAY
        scenarioStatusLabel.font = scenarioStatusLabel.font.deriveFont(Font.BOLD)

        nativeRightStatusPanel.apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 8)
            add(
                resultStatusTitleLabel,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 4)
                }
            )
            add(
                statusBadgeLabel,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 10)
                }
            )
            add(
                resultDurationTitleLabel,
                GridBagConstraints().apply {
                    gridx = 2
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 4)
                }
            )
            add(
                durationLabel,
                GridBagConstraints().apply {
                    gridx = 3
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 10)
                }
            )
            add(
                scenarioStatusLabel,
                GridBagConstraints().apply {
                    gridx = 4
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(0, 0, 0, 2)
                }
            )
        }
    }

    // [v0.2.2] ScenarioToolWindowFactory에서 네이티브 ToolWindow 타이틀 바 좌측(setTabActions)에 주입할 컴포넌트 반환
    fun getNativeLeftHeaderComponent(): JComponent = nativeLeftHeaderPanel

    // [v0.2.2] ScenarioToolWindowFactory에서 네이티브 ToolWindow 타이틀 바 우측(setTitleActions)에 주입할 컴포넌트 반환
    fun getNativeRightStatusComponent(): JComponent = nativeRightStatusPanel

    // [v0.2.2] 네이티브 타이틀 바 내부 라벨/버튼 텍스트 변경 시 ActionToolbar 및 ToolWindowHeader 상위 레이아웃까지 즉시 재계산
    // 변경 의도: 실행 상태(200 OK 등)나 저장 상태 문구 길이가 동적으로 변할 때 타이틀 바에서 잘리거나 갱신이 지연되는 현상 방지
    private fun revalidateNativeHeaderComponents() {
        val action = {
            revalidateWithAncestors(nativeLeftHeaderPanel)
            revalidateWithAncestors(nativeRightStatusPanel)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater { action() }
        }
    }

    private fun revalidateWithAncestors(component: JComponent) {
        component.revalidate()
        component.repaint()
        var ancestor = component.parent
        var depth = 0
        while (ancestor != null && depth < 3) {
            ancestor.revalidate()
            ancestor.repaint()
            ancestor = ancestor.parent
            depth++
        }
    }

    private fun buildTopToolbar(): JPanel {
        topToolbarPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        // [v0.3.0] 루트 시나리오는 '시나리오명', 폴더 내부 시나리오는 '폴더명 / 시나리오명'으로 표시하며 전체 경로 툴팁 동기화
        scenarioComboBox.minimumSize = Dimension(240, 28)
        scenarioComboBox.preferredSize = Dimension(280, 28)
        scenarioComboBox.maximumSize = Dimension(460, 28)
        scenarioComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val rawPath = value?.toString().orEmpty()
                val formatted = ScenarioFileRepository.formatDisplayPath(rawPath).ifEmpty { rawPath }
                val comp = super.getListCellRendererComponent(list, formatted, index, isSelected, cellHasFocus)
                toolTipText = formatted.ifEmpty { null }
                return comp
            }
        }
        // [v0.2.1] 업데이트 확인 및 언어 스위치를 최상단 헤더 바로 승격하여 시나리오 실행 툴바를 간결하게 정돈
        stopBtn.isEnabled = false

        scenarioManageBtn.margin = JBUI.insets(2, 7)
        globalConfigBtn.margin = JBUI.insets(2, 7)
        curlCopyBtn.margin = JBUI.insets(2, 7)

        applyRunButtonStyle(runAllBtn)
        applyRunButtonStyle(runSingleBtn)
        stopBtn.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))

        // 1) 시나리오: [ComboBox]
        topToolbarPanel.add(scenarioLabel)
        topToolbarPanel.add(scenarioComboBox)
        // 2) [저장]
        topToolbarPanel.add(saveBtn)
        // 3) [시나리오 관리]
        topToolbarPanel.add(scenarioManageBtn)
        // 4) [변수·헤더 관리] (공통 헤더·시나리오 변수·전역 변수·로컬 시크릿·동적 변수 5탭 통합)
        topToolbarPanel.add(globalConfigBtn)
        // 5) [cURL]
        topToolbarPanel.add(curlCopyBtn)
        // 6) [▶ 전체 시나리오 실행]
        topToolbarPanel.add(runAllBtn)
        // 7) [■ 중지]
        topToolbarPanel.add(stopBtn)

        return topToolbarPanel
    }

    // [v0.2.0] 전체 시나리오 실행 버튼과 단건 실행 버튼의 실행(Primary/Run) 색상·강조 스타일 통일
    private fun applyRunButtonStyle(button: JButton) {
        button.foreground = JBColor(Color(0x1B, 0x7F, 0x3A), Color(0x6C, 0xD9, 0x7E))
        button.font = button.font.deriveFont(Font.BOLD)
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

        // [v0.1.3] 실행 스텝 목록의 상태(Status) 컬럼 폭 축소 및 글자 잘림(...) 방지를 위해
        //          AUTO_RESIZE_LAST_COLUMN 정책과 상태 컬럼 최소/선호 폭(96px/106px) 확보
        stepTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            setShowGrid(false)
            rowHeight = 26
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                maxWidth = 32
                minWidth = 28
                preferredWidth = 28
                resizable = false
            }
            columnModel.getColumn(1).apply {
                minWidth = 96
                preferredWidth = 106
                maxWidth = 140
                resizable = true
                cellRenderer = StatusIconCellRenderer()
            }
            columnModel.getColumn(2).apply {
                minWidth = 120
                preferredWidth = 200
                cellRenderer = MethodPathCellRenderer()
            }
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

        // [v0.3.2] 요청 기본 환경 바(1행: 보호 모드 붉은 강조 지원) + 스텝 별칭 행(2행) + Method/URL/기대 상태/대기(ms) 행(3행)
        // 변경 의도: PRD 보호 모드 시각적 경고, 스텝 별칭 편집, 스텝 실행 전 대기(ms) 설정을 요청 편집기 상단에 직관적으로 배치
        envComboBox.minimumSize = Dimension(94, 28)
        envComboBox.preferredSize = Dimension(114, 28)
        baseUrlField.minimumSize = Dimension(120, 28)
        baseUrlField.preferredSize = Dimension(240, 28)
        envProtectBadgeLabel.apply {
            foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
            font = font.deriveFont(Font.BOLD, 11.5f)
            isVisible = false
        }

        envManageBtn.apply {
            text = ""
            icon = AllIcons.General.GearPlain
            margin = JBUI.emptyInsets()
            minimumSize = Dimension(28, 28)
            preferredSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            isFocusable = false
        }

        // 1) [Local ▾] 환경 선택 셀렉트박스
        baseUrlBarPanel.add(
            envComboBox,
            GridBagConstraints().apply {
                gridx = 0; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsRight(8)
            }
        )
        // 2) BaseURL: [입력 필드]
        baseUrlBarPanel.add(
            baseUrlLabel,
            GridBagConstraints().apply {
                gridx = 1; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsRight(6)
            }
        )
        baseUrlBarPanel.add(
            baseUrlField,
            GridBagConstraints().apply {
                gridx = 2; gridy = 0; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
        )
        // 2.5) [🛡️ 보호 환경] 뱃지 (보호 모드 활성 시 표시)
        baseUrlBarPanel.add(
            envProtectBadgeLabel,
            GridBagConstraints().apply {
                gridx = 3; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsLeft(8)
            }
        )
        // 3) [ ] SSL 무시 체크박스
        baseUrlBarPanel.add(
            ignoreSslCheckbox,
            GridBagConstraints().apply {
                gridx = 4; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsLeft(8)
            }
        )
        // 4) [⚙] 환경 프로필 관리 아이콘 전용 버튼
        baseUrlBarPanel.add(
            envManageBtn,
            GridBagConstraints().apply {
                gridx = 5; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsLeft(4)
            }
        )

        // [v0.3.3] 요청 바 2줄 재배치:
        //   1행(핵심 요청 바): [Method ▾] → [Path / URL 입력창(최대 확장)] → [▶ 단건 실행]
        //   2행(스텝 메타·실행 옵션 바): 스텝 별칭(유연 확장) | 기대 상태(컴팩트 고정 폭) | 대기(ms)(컴팩트 고정 폭)
        // 변경 의도: 긴 API Path의 가로 가독성을 극대화하고 스텝 메타·실행 옵션을 탭 바로 위 전용 행으로 정돈
        val requestLineBar = JPanel(BorderLayout(6, 4))
        methodComboBox.preferredSize = Dimension(88, 28)
        requestLineBar.add(methodComboBox, BorderLayout.WEST)
        requestLineBar.add(urlField, BorderLayout.CENTER)
        requestLineBar.add(runSingleBtn, BorderLayout.EAST)

        val stepOptionsRow = JPanel(GridBagLayout())
        stepAliasField.minimumSize = Dimension(120, 26)
        stepAliasField.preferredSize = Dimension(200, 26)
        expectedStatusField.minimumSize = Dimension(54, 26)
        expectedStatusField.preferredSize = Dimension(54, 26)
        delayField.minimumSize = Dimension(58, 26)
        delayField.preferredSize = Dimension(58, 26)

        stepOptionsRow.add(
            stepAliasLabel,
            GridBagConstraints().apply {
                gridx = 0; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsRight(6)
            }
        )
        stepOptionsRow.add(
            stepAliasField,
            GridBagConstraints().apply {
                gridx = 1; gridy = 0; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsRight(10)
            }
        )
        stepOptionsRow.add(
            expectedStatusLabel,
            GridBagConstraints().apply {
                gridx = 2; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsRight(4)
            }
        )
        stepOptionsRow.add(
            expectedStatusField,
            GridBagConstraints().apply {
                gridx = 3; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsRight(10)
            }
        )
        stepOptionsRow.add(
            delayLabel,
            GridBagConstraints().apply {
                gridx = 4; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.insetsRight(4)
            }
        )
        stepOptionsRow.add(
            delayField,
            GridBagConstraints().apply {
                gridx = 5; gridy = 0; weightx = 0.0
                anchor = GridBagConstraints.EAST
            }
        )

        val requestHeaderContainer = JPanel(BorderLayout(0, 4)).apply {
            add(baseUrlBarPanel, BorderLayout.NORTH)
            add(requestLineBar, BorderLayout.CENTER)
            add(stepOptionsRow, BorderLayout.SOUTH)
        }

        panel.add(requestHeaderContainer, BorderLayout.NORTH)

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

        // [v0.4.0] Tab 4: 응답 검증 (No-Code JSONPath Assertions)
        assertionsTable.apply {
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                maxWidth = 54
                minWidth = 42
                preferredWidth = 48
            }
            columnModel.getColumn(1).preferredWidth = 180
            columnModel.getColumn(2).apply {
                minWidth = 96
                preferredWidth = 110
                maxWidth = 140
                cellEditor = DefaultCellEditor(JComboBox(AssertionOperator.ALL_SYMBOLS))
            }
            columnModel.getColumn(3).preferredWidth = 180
        }
        val assertionsPanel = JPanel(BorderLayout(0, 4))
        assertionsPanel.add(JBScrollPane(assertionsTable), BorderLayout.CENTER)
        val assertionBtnBar = createWrapBar()
        addAssertionFromResponseBtn.margin = JBUI.insets(2, 7)
        assertionHintLabel.foreground = JBColor.GRAY
        assertionBtnBar.add(addAssertionBtn)
        assertionBtnBar.add(addAssertionFromResponseBtn)
        assertionBtnBar.add(removeAssertionBtn)
        assertionBtnBar.add(assertionHintLabel)
        assertionsPanel.add(assertionBtnBar, BorderLayout.SOUTH)

        editorTabs.addTab("Params", paramsPanel)
        editorTabs.addTab("Body (JSON)", bodyPanel)
        editorTabs.addTab("Headers", headersPanel)
        editorTabs.addTab("응답 변수 추출", extractPanel)
        editorTabs.addTab("응답 검증", assertionsPanel)

        panel.add(editorTabs, BorderLayout.CENTER)
        return panel
    }

    private fun buildRightResultPane(): JPanel {
        val panel = JPanel(BorderLayout(0, 4))
        panel.border = JBUI.Borders.empty(4)

        // [v0.2.1] 상태/소요/저장 표시줄은 최상단 헤더 바 우측으로 이동하고,
        //          응답 탭 상단의 [응답 변수 추출] 버튼은 BorderLayout.EAST로 맨 우측 정렬 배치

        // Tab 0: 응답 (Response — 100% full height)
        responseHeaderLabel.apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4)
        }
        extractFromResponseBtn.margin = JBUI.insets(2, 8)

        val responseTopBar = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(2, 2, 4, 2)
            add(responseHeaderLabel, BorderLayout.WEST)
            add(extractFromResponseBtn, BorderLayout.EAST)
        }

        val responsePanel = JPanel(BorderLayout()).apply {
            add(responseTopBar, BorderLayout.NORTH)
            add(JBScrollPane(responseBodyArea), BorderLayout.CENTER)
        }

        // [v0.4.0] Tab 1: 추출 변수 (Value 컬럼 더블클릭 수동 Override + 수동 변수 추가/삭제 지원)
        extractedVarsTable.apply {
            rowHeight = 25
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            columnModel.getColumn(0).preferredWidth = 160
            columnModel.getColumn(1).preferredWidth = 240
            columnModel.getColumn(2).apply {
                maxWidth = 72
                minWidth = 56
                preferredWidth = 64
                cellRenderer = object : DefaultTableCellRenderer() {
                    init { horizontalAlignment = SwingConstants.CENTER }
                    override fun getTableCellRendererComponent(
                        table: JTable?,
                        value: Any?,
                        isSelected: Boolean,
                        hasFocus: Boolean,
                        row: Int,
                        column: Int
                    ): Component {
                        super.getTableCellRendererComponent(table, "[ ${value ?: ""} ]", isSelected, hasFocus, row, column)
                        font = font.deriveFont(Font.BOLD)
                        if (!isSelected) {
                            foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                        }
                        return this
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    // 컬럼 1(Value) 더블클릭은 수동 Override 인라인 편집으로 동작하고, 컬럼 2(복사) 클릭 또는 컬럼 0 더블클릭 시 토큰 복사
                    if (col == 2 || (col == 0 && e.clickCount == 2)) {
                        val varName = extractedVarsModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
                        if (varName.isNotEmpty()) {
                            val token = "{{$varName}}"
                            CopyPasteManager.getInstance().setContents(StringSelection(token))
                            showBalloon(extractedVarsTable, I18nManager.t("vars.copiedMsg", token), MessageType.INFO)
                        }
                    }
                }
            })
        }
        extractedHeaderLabel.apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(2, 4)
        }
        extractedOverrideHintLabel.foreground = JBColor.GRAY
        addOverrideVarBtn.margin = JBUI.insets(2, 7)
        removeOverrideVarBtn.margin = JBUI.insets(2, 7)
        val extractedBottomBar = createWrapBar().apply {
            add(addOverrideVarBtn)
            add(removeOverrideVarBtn)
            add(extractedOverrideHintLabel)
        }
        val extractedPanel = JPanel(BorderLayout(0, 4)).apply {
            add(extractedHeaderLabel, BorderLayout.NORTH)
            add(JBScrollPane(extractedVarsTable), BorderLayout.CENTER)
            add(extractedBottomBar, BorderLayout.SOUTH)
        }

        // Tab 2: 실행 및 에러 로그 (Execution & Error Logs + Clear Log button)
        clearLogBtn.margin = JBUI.insets(2, 7)
        clearLogBtn.addActionListener { clearExecutionLog() }
        val logTopBar = createWrapBar()
        logTopBar.add(clearLogBtn)

        val logPanel = JPanel(BorderLayout()).apply {
            add(logTopBar, BorderLayout.NORTH)
            add(JBScrollPane(detailLogArea), BorderLayout.CENTER)
        }

        resultTabs.addTab("응답", responsePanel)
        resultTabs.addTab("추출 변수", extractedPanel)
        resultTabs.addTab("실행 및 에러 로그", logPanel)

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
        scenarioComboBox.addActionListener {
            if (isUpdatingUi) return@addActionListener
            val selectedPath = scenarioComboBox.selectedItem as? String ?: return@addActionListener
            if (selectedPath == currentScenarioPath && !isQuickSingleMode) return@addActionListener
            handleScenarioSwitchRequest(selectedPath)
        }

        envComboBox.addActionListener {
            if (isUpdatingUi) return@addActionListener
            val envName = envComboBox.selectedItem as? String ?: return@addActionListener
            applySelectedEnvironment(envName, persistSelection = true)
        }
        envManageBtn.addActionListener { openEnvironmentManagerDialog() }

        scenarioManageBtn.addActionListener { openScenarioExplorerDialog() }
        saveBtn.addActionListener { saveCurrentScenario(showFeedback = true) }
        globalConfigBtn.addActionListener { openGlobalConfigDialog() }
        curlCopyBtn.addActionListener { openCurlManagerDialog() }
        updateCheckBtn.addActionListener { handleUpdateCheckButtonClick() }

        baseUrlField.document.addDocumentListener(simpleDocListener {
            if (!isUpdatingUi) {
                val editedUrl = baseUrlField.text.trim()
                currentScenario.baseUrl = editedUrl
                updateActiveEnvironmentProfileUrl(editedUrl)
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

        // [v0.4.0] Assertions Tab buttons
        addAssertionBtn.addActionListener {
            openAddAssertionRuleDialog(useResponseCandidates = false, useCaretOffset = false)
        }
        addAssertionFromResponseBtn.addActionListener {
            openAddAssertionRuleDialog(useResponseCandidates = true, useCaretOffset = false)
        }
        removeAssertionBtn.addActionListener {
            stopTableEditing(assertionsTable)
            val rows = assertionsTable.selectedRows
            for (i in rows.indices.reversed()) {
                assertionsTableModel.removeRow(rows[i])
            }
            syncEditorToCurrentStep()
            updateAssertionsTabTitle()
        }

        // [v0.4.0] Extracted Vars manual override (memory-only, never writes to disk)
        addOverrideVarBtn.addActionListener { addManualRuntimeOverrideVar() }
        removeOverrideVarBtn.addActionListener { removeSelectedRuntimeOverrideVar() }
        extractedVarsModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type == TableModelEvent.UPDATE && e.column == 1 && e.firstRow >= 0) {
                val row = e.firstRow
                if (row in 0 until extractedVarsModel.rowCount) {
                    val varName = extractedVarsModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
                    val newVal = extractedVarsModel.getValueAt(row, 1)?.toString().orEmpty()
                    if (varName.isNotEmpty()) {
                        lastRuntimeContext[varName] = newVal
                    }
                }
            }
        }

        // Center Pane auto-sync to step model
        stepAliasField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        methodComboBox.addActionListener { if (!isUpdatingUi) syncEditorToCurrentStep() }
        urlField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        expectedStatusField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
        delayField.document.addDocumentListener(simpleDocListener { if (!isUpdatingUi) syncEditorToCurrentStep() })
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
        assertionsTableModel.addTableModelListener { e ->
            if (!isUpdatingUi && e.type != TableModelEvent.HEADER_ROW) {
                syncEditorToCurrentStep()
                updateAssertionsTabTitle()
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

        VariableContextMenuSupport.installOnTextComponent(baseUrlField, varsProvider)
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
        VariableContextMenuSupport.installOnTableValueColumn(
            table = assertionsTable,
            valueColumnIndex = 3,
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
                val assertionItem = JMenuItem(I18nManager.t("result.context.addAssertionRule")).apply {
                    isEnabled = responseBodyArea.text.trim().isNotEmpty() && getActiveEditableStep() != null
                    addActionListener { openAddAssertionRuleDialog(useResponseCandidates = true, useCaretOffset = true) }
                }
                popup.add(extractItem)
                popup.add(assertionItem)
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

    // --- Feature 1: Folder-Based Scenario Explorer Dialog (v0.3.0) ---

    // [v0.3.0] [시나리오 관리] 클릭 시 폴더·시나리오 트리 관리 모달(ScenarioExplorerDialog)을 열고,
    //          팝업 내 폴더/시나리오 이름 변경·이동·삭제 결과를 메인 화면의 활성 경로와 ComboBox에 즉시 동기화
    private fun openScenarioExplorerDialog() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val dialog = ScenarioExplorerDialog(
            project = project,
            storage = storage,
            initialActiveScenarioPath = currentScenarioPath,
            defaultBaseUrlProvider = {
                baseUrlField.text.trim().ifEmpty { PsiEndpointExtractor.detectServerBaseUrl(project) }
            },
            activeEnvironmentProvider = { getActiveEnvironmentProfile() }
        )
        dialog.show()

        // 1) 현재 열려 있던 시나리오(또는 그 상위 폴더)가 삭제된 경우 안전하게 대체 시나리오 로드
        if (dialog.activeScenarioWasDeleted) {
            autoSaveAlarm.cancelAllRequests()
            val remaining = storage.listScenarios()
            if (remaining.isEmpty()) {
                val detectedBaseUrl = PsiEndpointExtractor.detectServerBaseUrl(project)
                val freshDefault = ApiScenario(name = "default", baseUrl = detectedBaseUrl)
                storage.saveScenario(freshDefault, "default")
                refreshScenarioComboBox("default")
                loadScenarioByPath("default")
                Messages.showInfoMessage(project, I18nManager.t("dialog.delete.lastReset"), I18nManager.t("dialog.delete.title"))
            } else {
                val preferred = dialog.selectedScenarioPathToOpen?.takeIf { it in remaining } ?: remaining.first()
                refreshScenarioComboBox(preferred)
                loadScenarioByPath(preferred)
                dialog.selectedStepIndexToOpen?.let { stepIdx ->
                    if (stepIdx in currentScenario.steps.indices) selectStepRow(stepIdx)
                }
            }
            return
        }

        // 2) 현재 열려 있던 시나리오(또는 상위 폴더)의 경로·이름·설명이 변경된 경우 즉시 동기화 (미저장 상태 보존)
        val wasDirtyBeforeSync = isScenarioDirty()
        if (dialog.trackedActiveScenarioPath != currentScenarioPath || dialog.activeScenarioMetadataUpdated) {
            currentScenarioPath = dialog.trackedActiveScenarioPath
            currentScenario.name = ScenarioFileRepository.baseNameOf(currentScenarioPath)
            storage.loadScenario(currentScenarioPath)?.let { diskCopy ->
                currentScenario.description = diskCopy.description
            }
            if (!wasDirtyBeforeSync) {
                markScenarioClean()
            }
        }

        refreshScenarioComboBox(currentScenarioPath)

        // 3) 더블클릭, [확인], 또는 배치 실행 리포트의 [해당 시나리오 열기]로 시나리오(및 실패 스텝) 즉시 로드
        if (dialog.isOK) {
            val targetPath = dialog.selectedScenarioPathToOpen
            val targetStepIdx = dialog.selectedStepIndexToOpen
            if (!targetPath.isNullOrBlank()) {
                if (targetPath != currentScenarioPath || isQuickSingleMode) {
                    handleScenarioSwitchRequest(targetPath)
                }
                if (targetStepIdx != null && currentScenarioPath == targetPath && targetStepIdx in currentScenario.steps.indices) {
                    selectStepRow(targetStepIdx)
                }
            }
        }
    }

    // [v0.2.0] BaseURL 환경 프로필(Local/DEV/STG/PRD) 전환·수정·관리 다이얼로그 연동
    private fun openEnvironmentManagerDialog() {
        val dialog = EnvironmentManagerDialog(
            project = project,
            initialProfiles = environmentConfig.profiles,
            localPortDetector = { PsiEndpointExtractor.detectServerBaseUrl(project) }
        )
        if (dialog.showAndGet()) {
            environmentConfig.profiles = dialog.getUpdatedProfiles()
            if (environmentConfig.profiles.none { it.name.equals(environmentConfig.selectedEnvironment, ignoreCase = true) }) {
                environmentConfig.selectedEnvironment = environmentConfig.profiles.firstOrNull()?.name ?: "Local"
            }
            storage.saveEnvironmentConfig(environmentConfig)
            refreshEnvironmentComboBox()
            applySelectedEnvironment(environmentConfig.selectedEnvironment, persistSelection = false)
        }
    }

    private fun refreshEnvironmentComboBox() {
        isUpdatingUi = true
        try {
            envComboModel.removeAllElements()
            environmentConfig.profiles.forEach { envComboModel.addElement(it.name) }
            envComboBox.selectedItem = environmentConfig.selectedEnvironment
        } finally {
            isUpdatingUi = false
        }
    }

    private fun applySelectedEnvironment(envName: String, persistSelection: Boolean) {
        val profile = environmentConfig.profiles.firstOrNull { it.name.equals(envName, ignoreCase = true) } ?: return
        environmentConfig.selectedEnvironment = profile.name
        val resolvedUrl = if (profile.builtInLocal && profile.baseUrl.isBlank()) {
            PsiEndpointExtractor.detectServerBaseUrl(project).also { profile.baseUrl = it }
        } else {
            profile.baseUrl
        }
        if (persistSelection) {
            storage.saveEnvironmentConfig(environmentConfig)
        }
        isUpdatingUi = true
        try {
            envComboBox.selectedItem = profile.name
            baseUrlField.text = resolvedUrl
            currentScenario.baseUrl = resolvedUrl
        } finally {
            isUpdatingUi = false
        }
        updateEnvironmentProtectionStyle()
    }

    // [v0.3.2] 보호 모드(confirmBeforeRun=true) 환경 선택 시 상단 환경 바를 붉은색 경고 스타일로 강조
    private fun updateEnvironmentProtectionStyle() {
        val activeProfile = getActiveEnvironmentProfile()
        val isProtected = activeProfile?.confirmBeforeRun == true
        envProtectBadgeLabel.isVisible = isProtected
        if (isProtected) {
            baseUrlBarPanel.isOpaque = true
            baseUrlBarPanel.background = JBColor(Color(0xFF, 0xEB, 0xEE), Color(0x4A, 0x1F, 0x24))
            baseUrlBarPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50)), 1, true),
                JBUI.Borders.empty(2, 6, 2, 6)
            )
        } else {
            baseUrlBarPanel.isOpaque = false
            baseUrlBarPanel.border = JBUI.Borders.empty()
        }
        baseUrlBarPanel.revalidate()
        baseUrlBarPanel.repaint()
    }

    private fun getActiveEnvironmentProfile() =
        environmentConfig.profiles.firstOrNull {
            it.name.equals(environmentConfig.selectedEnvironment, ignoreCase = true)
        }

    // [v0.3.2] 보호 모드 환경에서 단건/전체 실행 클릭 시 실수 실행 차단을 위한 확인 팝업 표시
    private fun confirmProtectedEnvironmentExecution(isFullScenario: Boolean): Boolean {
        val activeProfile = getActiveEnvironmentProfile() ?: return true
        if (!activeProfile.confirmBeforeRun) return true
        val targetLabel = if (isFullScenario) {
            I18nManager.t("env.protect.targetScenario")
        } else {
            I18nManager.t("env.protect.targetStep")
        }
        val effectiveBaseUrl = baseUrlField.text.trim().ifEmpty { activeProfile.baseUrl }
        val choice = Messages.showYesNoDialog(
            project,
            I18nManager.t("env.protect.confirmMsg", activeProfile.name, targetLabel, effectiveBaseUrl),
            I18nManager.t("env.protect.confirmTitle"),
            Messages.getWarningIcon()
        )
        return choice == Messages.YES
    }

    private fun updateActiveEnvironmentProfileUrl(newUrl: String) {
        val activeName = environmentConfig.selectedEnvironment
        val profile = environmentConfig.profiles.firstOrNull { it.name.equals(activeName, ignoreCase = true) }
        if (profile != null && profile.baseUrl != newUrl) {
            profile.baseUrl = newUrl
        }
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
            revalidateNativeHeaderComponents()
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
        revalidateNativeHeaderComponents()

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
            revalidateNativeHeaderComponents()
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
        revalidateNativeHeaderComponents()
        topToolbarPanel.revalidate()
    }

    // --- [v0.4.0] Unified cURL Manager Dialog (Export Preview + Smart Import) ---

    private fun openCurlManagerDialog() {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val projectGlobals = storage.loadGlobalVariables()
        val projectGlobalSecretValues = projectGlobals
            .filter { it.enabled && it.secret && it.value.isNotEmpty() }
            .map { it.value }
            .toSet()

        val dialog = CurlManagerDialog(
            project = project,
            scenario = currentScenario,
            activeStep = getActiveEditableStep(),
            runtimeContext = LinkedHashMap(lastRuntimeContext),
            secrets = storage.loadSecrets(),
            projectGlobalVariables = storage.loadEnabledGlobalVariablesMap(),
            projectGlobalSecretValues = projectGlobalSecretValues,
            onAddNewStep = { parsed -> applyImportedCurlAsNewStep(parsed) },
            onOverwriteCurrentStep = { parsed -> applyImportedCurlToCurrentStep(parsed) }
        )
        dialog.show()
    }

    // [v0.4.0] cURL 가져오기 결과를 새 스텝으로 메모리 세션에 추가 (사용자가 [저장] 클릭 전까지 디스크 미덮어쓰기)
    private fun applyImportedCurlAsNewStep(parsed: ParsedCurlResult) {
        isQuickSingleMode = false
        quickSingleStep = null
        lastQuickEndpoint = null
        quickModeBanner.isVisible = false

        val newStep = parsed.toApiStep()
        currentScenario.steps.add(newStep)
        stepStatuses[newStep.id] = StepStatus.PENDING
        stepTableModel.fireTableDataChanged()
        selectStepRow(currentScenario.steps.lastIndex)
        updateDirtyIndicator()

        val msg = I18nManager.t("curl.import.appliedStatus")
        scenarioStatusLabel.text = msg
        scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        revalidateNativeHeaderComponents()
        showBalloon(curlCopyBtn, msg, MessageType.INFO)
    }

    // [v0.4.0] cURL 가져오기 결과를 현재 선택 스텝에 메모리 세션 덮어쓰기 (기존 추출/검증 규칙은 보존)
    private fun applyImportedCurlToCurrentStep(parsed: ParsedCurlResult) {
        val target = getActiveEditableStep() ?: return
        target.method = parsed.method
        target.url = parsed.url
        target.queryParams = parsed.queryParams.map { it.deepCopy() }.toMutableList()
        target.headers = LinkedHashMap(parsed.headers)
        target.body = parsed.body

        isUpdatingUi = true
        try {
            populateEditorFields(target)
        } finally {
            isUpdatingUi = false
        }
        syncEditorToCurrentStep()

        val msg = I18nManager.t("curl.import.appliedStatus")
        scenarioStatusLabel.text = msg
        scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        revalidateNativeHeaderComponents()
        showBalloon(curlCopyBtn, msg, MessageType.INFO)
    }

    // --- [v0.4.0] No-Code Response Assertion Popup & Tab Badge ---

    private fun openAddAssertionRuleDialog(useResponseCandidates: Boolean, useCaretOffset: Boolean) {
        if (getActiveEditableStep() == null) {
            Messages.showInfoMessage(project, I18nManager.t("extractDialog.noStep.msg"), "Maekom API Runner")
            return
        }
        val jsonText = responseBodyArea.text
        val candidates = if (useResponseCandidates) JsonPathLocator.extractAllCandidates(jsonText) else emptyList()
        if (useResponseCandidates && candidates.isEmpty()) {
            Messages.showWarningDialog(
                project,
                I18nManager.t("extractDialog.noJson.msg"),
                I18nManager.t("extractDialog.noJson.title")
            )
            return
        }
        val initialCandidate = if (useResponseCandidates && useCaretOffset) {
            JsonPathLocator.locateAtOffset(jsonText, responseBodyArea.caretPosition)
        } else {
            null
        }

        val dialog = AddAssertionRuleDialog(
            project = project,
            candidates = candidates,
            initialCandidate = initialCandidate
        )
        if (dialog.showAndGet()) {
            val newRule = dialog.toAssertionRule()
            stopTableEditing(assertionsTable)
            isUpdatingUi = true
            try {
                assertionsTableModel.addRow(arrayOf<Any>(newRule.enabled, newRule.jsonPath, newRule.operator, newRule.expectedValue))
            } finally {
                isUpdatingUi = false
            }
            val lastRow = assertionsTableModel.rowCount - 1
            if (lastRow >= 0) {
                assertionsTable.setRowSelectionInterval(lastRow, lastRow)
            }
            syncEditorToCurrentStep()
            updateAssertionsTabTitle()
            editorTabs.selectedIndex = TAB_ASSERTIONS

            val statusMsg = I18nManager.t("status.assertionRuleAdded", newRule.jsonPath, newRule.operator, newRule.expectedValue)
            scenarioStatusLabel.text = statusMsg
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            revalidateNativeHeaderComponents()
        }
    }

    private fun updateAssertionsTabTitle() {
        var enabledCount = 0
        for (i in 0 until assertionsTableModel.rowCount) {
            val enabled = (assertionsTableModel.getValueAt(i, 0) as? Boolean) ?: true
            val path = (assertionsTableModel.getValueAt(i, 1)?.toString() ?: "").trim()
            if (enabled && path.isNotEmpty()) enabledCount++
        }
        val title = if (enabledCount > 0) {
            I18nManager.t("editor.tab.assertionsCount", enabledCount)
        } else {
            I18nManager.t("editor.tab.assertions")
        }
        if (editorTabs.tabCount > TAB_ASSERTIONS) {
            editorTabs.setTitleAt(TAB_ASSERTIONS, title)
        }
    }

    // --- [v0.4.0] Extracted Vars Manual Override (Memory Session Only) ---

    private fun addManualRuntimeOverrideVar() {
        stopTableEditing(extractedVarsTable)
        val rawName = Messages.showInputDialog(
            project,
            I18nManager.t("extracted.override.promptName"),
            I18nManager.t("extracted.override.promptTitle"),
            Messages.getQuestionIcon()
        )?.trim()
        if (rawName.isNullOrBlank()) return

        val rawValue = Messages.showInputDialog(
            project,
            I18nManager.t("extracted.override.promptValue"),
            I18nManager.t("extracted.override.promptTitle"),
            Messages.getQuestionIcon(),
            lastRuntimeContext[rawName].orEmpty(),
            null
        ) ?: return

        lastRuntimeContext[rawName] = rawValue
        refreshExtractedVarsTable()
    }

    private fun removeSelectedRuntimeOverrideVar() {
        stopTableEditing(extractedVarsTable)
        val row = extractedVarsTable.selectedRow
        if (row !in 0 until extractedVarsModel.rowCount) return
        val varName = extractedVarsModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
        if (varName.isNotEmpty()) {
            lastRuntimeContext.remove(varName)
            refreshExtractedVarsTable()
        }
    }

    private fun refreshExtractedVarsTable(extraStepVars: Map<String, String> = emptyMap()) {
        val merged = linkedMapOf<String, String>()
        merged.putAll(lastRuntimeContext)
        for ((k, v) in extraStepVars) {
            if (!merged.containsKey(k)) {
                merged[k] = v
            }
        }
        isUpdatingUi = true
        try {
            extractedVarsModel.rowCount = 0
            val copyLabel = I18nManager.t("vars.copyBtn")
            for ((k, v) in merged) {
                extractedVarsModel.addRow(arrayOf<Any>(k, v, copyLabel))
            }
        } finally {
            isUpdatingUi = false
        }
        updateExtractedVarsTabTitle()
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
        globalConfigBtn.text = I18nManager.t("toolbar.globalConfig")
        globalConfigBtn.toolTipText = I18nManager.t("toolbar.globalConfig.tooltip")
        curlCopyBtn.text = I18nManager.t("toolbar.curl")
        curlCopyBtn.toolTipText = I18nManager.t("toolbar.curl.tooltip")
        envComboBox.toolTipText = I18nManager.t("env.combo.tooltip")
        envManageBtn.text = ""
        envManageBtn.icon = AllIcons.General.GearPlain
        envManageBtn.toolTipText = I18nManager.t("env.manageBtn.tooltip")
        envProtectBadgeLabel.text = I18nManager.t("env.protect.badge")
        baseUrlLabel.text = I18nManager.t("toolbar.baseUrl")
        baseUrlField.toolTipText = I18nManager.t("toolbar.baseUrl.tooltip")
        ignoreSslCheckbox.text = I18nManager.t("toolbar.ignoreSsl")
        ignoreSslCheckbox.toolTipText = I18nManager.t("toolbar.ignoreSsl.tooltip")
        runAllBtn.text = I18nManager.t("toolbar.runAll")
        stopBtn.text = I18nManager.t("toolbar.stop")
        refreshUpdateButtonAppearance()
        languageSegmentSwitch.toolTipText = I18nManager.t("toolbar.lang.tooltip")
        isUpdatingUi = true
        try {
            languageSegmentSwitch.setSelectedLanguage(lang)
        } finally {
            isUpdatingUi = false
        }

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

        stepAliasLabel.text = I18nManager.t("editor.alias")
        stepAliasField.toolTipText = I18nManager.t("editor.alias.tooltip")
        expectedStatusLabel.text = I18nManager.t("editor.expectedStatus")
        expectedStatusField.toolTipText = I18nManager.t("editor.expectedStatus.tooltip")
        delayLabel.text = I18nManager.t("editor.delay")
        delayField.toolTipText = I18nManager.t("editor.delay.tooltip")
        runSingleBtn.text = I18nManager.t("editor.runSingle")

        editorTabs.setTitleAt(TAB_PARAMS, I18nManager.t("editor.tab.params"))
        editorTabs.setTitleAt(TAB_BODY, I18nManager.t("editor.tab.body"))
        editorTabs.setTitleAt(TAB_HEADERS, I18nManager.t("editor.tab.headers"))
        editorTabs.setTitleAt(TAB_EXTRACT, I18nManager.t("editor.tab.extract"))
        updateAssertionsTabTitle()

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

        updateTableHeaders(
            assertionsTable,
            I18nManager.t("assert.col.enabled"),
            I18nManager.t("assert.col.jsonPath"),
            I18nManager.t("assert.col.operator"),
            I18nManager.t("assert.col.expected")
        )
        addAssertionBtn.text = I18nManager.t("assert.add")
        addAssertionFromResponseBtn.text = I18nManager.t("assert.addFromResponse")
        removeAssertionBtn.text = I18nManager.t("assert.remove")
        assertionHintLabel.text = I18nManager.t("assert.hint")

        extractedOverrideHintLabel.text = I18nManager.t("extracted.override.hint")
        addOverrideVarBtn.text = I18nManager.t("extracted.override.add")
        removeOverrideVarBtn.text = I18nManager.t("extracted.override.remove")

        resultStatusTitleLabel.text = I18nManager.t("result.statusLabel")
        resultDurationTitleLabel.text = I18nManager.t("result.durationLabel")
        resultTabs.setTitleAt(RESULT_TAB_RESPONSE, I18nManager.t("result.tab.response"))
        updateExtractedVarsTabTitle()
        resultTabs.setTitleAt(RESULT_TAB_LOG, I18nManager.t("result.tab.log"))
        clearLogBtn.text = I18nManager.t("result.log.clear")
        extractedHeaderLabel.text = I18nManager.t("result.extractedHeader")
        responseHeaderLabel.text = I18nManager.t("result.responseHeader")
        extractFromResponseBtn.text = I18nManager.t("result.extractFromResponse")
        extractFromResponseBtn.toolTipText = I18nManager.t("result.extractFromResponse.tooltip")
        updateTableHeaders(
            extractedVarsTable,
            I18nManager.t("result.extractedCol.name"),
            I18nManager.t("result.extractedCol.value"),
            I18nManager.t("result.extractedCol.copy")
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
        revalidateNativeHeaderComponents()
        topToolbarPanel.revalidate()
        revalidate()
        repaint()
    }

    private fun updateExtractedVarsTabTitle() {
        val count = extractedVarsModel.rowCount
        val title = if (count > 0) {
            I18nManager.t("result.tab.extractedCount", count)
        } else {
            I18nManager.t("result.tab.extracted")
        }
        if (resultTabs.tabCount > RESULT_TAB_EXTRACTED) {
            resultTabs.setTitleAt(RESULT_TAB_EXTRACTED, title)
        }
    }

    // [v0.1.5] 실행 및 에러 로그 탭이 이미 열려 있을 때도 즉시 텍스트 갱신 및 최하단 자동 스크롤 보장
    private fun clearExecutionLog() {
        executionLogBuffer.setLength(0)
        ApplicationManager.getApplication().invokeLater {
            detailLogArea.text = ""
            detailLogArea.caretPosition = 0
            detailLogArea.revalidate()
            detailLogArea.repaint()
        }
    }

    private fun appendExecutionLog(chunk: String) {
        executionLogBuffer.append(chunk)
        val snapshot = executionLogBuffer.toString()
        ApplicationManager.getApplication().invokeLater {
            detailLogArea.text = snapshot
            detailLogArea.caretPosition = detailLogArea.document.length
            detailLogArea.revalidate()
            detailLogArea.repaint()
        }
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
        revalidateNativeHeaderComponents()
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
        storage.saveScenario(currentScenario, currentScenarioPath)
        markScenarioClean()
        refreshScenarioComboBox(currentScenarioPath)

        val nowText = LocalTime.now().format(AUTO_SAVE_TIME_FMT)
        lastAutoSavedTime = nowText
        scenarioStatusLabel.text = I18nManager.t("status.autoSaved", nowText)
        scenarioStatusLabel.foreground = JBColor.GRAY
        revalidateNativeHeaderComponents()
    }

    private fun handleScenarioSwitchRequest(targetScenarioPath: String) {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        currentScenario.baseUrl = baseUrlField.text.trim()
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        if (!isScenarioDirty()) {
            loadScenarioByPath(targetScenarioPath)
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
                loadScenarioByPath(targetScenarioPath)
            }
            Messages.NO -> {
                loadScenarioByPath(targetScenarioPath)
            }
            else -> {
                isUpdatingUi = true
                try {
                    scenarioComboBox.selectedItem = currentScenarioPath
                } finally {
                    isUpdatingUi = false
                }
            }
        }
    }

    private fun initializeScenarios() {
        val detectedBaseUrl = PsiEndpointExtractor.detectServerBaseUrl(project)
        environmentConfig = storage.loadEnvironmentConfig(detectedBaseUrl)
        refreshEnvironmentComboBox()
        val activeEnvUrl = environmentConfig.profiles
            .firstOrNull { it.name.equals(environmentConfig.selectedEnvironment, ignoreCase = true) }
            ?.baseUrl?.takeIf { it.isNotBlank() } ?: detectedBaseUrl

        val existingPaths = storage.listScenarios()

        isUpdatingUi = true
        try {
            scenarioComboModel.removeAllElements()
            if (existingPaths.isEmpty()) {
                currentScenarioPath = "default"
                currentScenario = ApiScenario(
                    name = "default",
                    baseUrl = activeEnvUrl
                )
                scenarioComboModel.addElement(currentScenarioPath)
                scenarioComboBox.selectedItem = currentScenarioPath
            } else {
                existingPaths.forEach { scenarioComboModel.addElement(it) }
                val firstPath = existingPaths.first()
                currentScenarioPath = firstPath
                currentScenario = storage.loadScenario(firstPath)
                    ?: ApiScenario(name = ScenarioFileRepository.baseNameOf(firstPath), baseUrl = activeEnvUrl)
                scenarioComboBox.selectedItem = currentScenarioPath
            }

            currentScenario.baseUrl = activeEnvUrl
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
        syncScenarioComboTooltip(currentScenarioPath)
        updateEnvironmentProtectionStyle()
        lastAutoSavedTime = null
        markScenarioClean()
        scenarioStatusLabel.text = ""
        revalidateNativeHeaderComponents()
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
        revalidateNativeHeaderComponents()
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

    // --- Scenario Load / Save / Global Config ---

    private fun loadScenarioByPath(relativePath: String) {
        val normPath = ScenarioFileRepository.normalizeRelativePath(relativePath)
        val loaded = storage.loadScenario(normPath)
        if (loaded == null) {
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.loadFail.msg", ScenarioFileRepository.formatDisplayPath(normPath)),
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

        currentScenarioPath = normPath
        currentScenario = loaded
        stepStatuses.clear()
        stepResults.clear()
        lastRuntimeContext.clear()

        // [v0.2.0] 시나리오 전환 시에도 현재 선택된 환경 프로필(Local/DEV/STG/PRD)과 BaseURL 설정을 유지
        val activeEnvUrl = environmentConfig.profiles
            .firstOrNull { it.name.equals(environmentConfig.selectedEnvironment, ignoreCase = true) }
            ?.baseUrl?.takeIf { it.isNotBlank() }

        isUpdatingUi = true
        try {
            if (activeEnvUrl != null) {
                currentScenario.baseUrl = activeEnvUrl
            }
            baseUrlField.text = currentScenario.baseUrl
            ignoreSslCheckbox.isSelected = currentScenario.ignoreSsl
            scenarioComboBox.selectedItem = currentScenarioPath
            stepTableModel.fireTableDataChanged()
        } finally {
            isUpdatingUi = false
        }

        if (currentScenario.steps.isNotEmpty()) {
            selectStepRow(0)
        } else {
            refreshEditorFromSelection()
        }
        syncScenarioComboTooltip(currentScenarioPath)
        displayStepResult(null)
        markScenarioClean()
        scenarioStatusLabel.text = ""
        revalidateNativeHeaderComponents()
    }

    private fun saveCurrentScenario(showFeedback: Boolean) {
        stopAllTableEdits()
        syncEditorToCurrentStep()
        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val savedFile = storage.saveScenario(currentScenario, currentScenarioPath)
        storage.saveEnvironmentConfig(environmentConfig)
        lastAutoSavedTime = null
        markScenarioClean()
        refreshScenarioComboBox(currentScenarioPath)
        if (showFeedback) {
            scenarioStatusLabel.text = I18nManager.t("status.saved", savedFile.name)
            scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            revalidateNativeHeaderComponents()
        }
    }

    // [v0.4.3] [공통 헤더·변수 관리] 5탭 통합 다이얼로그 오픈 및 시나리오 변수 ↔ 스텝 응답 변수 추출 양방향 현행화
    private fun openGlobalConfigDialog(initialTab: Int = GlobalConfigDialog.TAB_COMMON_HEADERS) {
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val currentSecrets = storage.loadSecrets()
        val currentProjectGlobals = storage.loadGlobalVariables()
        val dialog = GlobalConfigDialog(
            project = project,
            currentScenario = currentScenario,
            storage = storage,
            initialCommonHeaders = currentScenario.effectiveCommonHeaderItems(),
            initialVariables = currentScenario.globalVariables,
            initialSecrets = currentSecrets,
            currentScenarioPath = currentScenarioPath,
            initialProjectGlobals = currentProjectGlobals,
            initialSelectedTab = initialTab,
            runtimeContext = LinkedHashMap(lastRuntimeContext)
        )
        if (dialog.showAndGet()) {
            var anySaved = false
            if (dialog.isScenarioTabChanged()) {
                currentScenario.applyCommonHeaderItems(dialog.getCommonHeaderItems())
                val syncResult = ScenarioVariableSyncEngine.applySync(
                    scenario = currentScenario,
                    runtimeContext = lastRuntimeContext,
                    editedRows = dialog.getEditedScenarioVarRows(),
                    deletedExtractorRefs = dialog.getDeletedExtractorRefs(),
                    replaceReferencesForRenames = dialog.shouldReplaceReferencesOnRename()
                )
                // 스텝 추출 규칙·참조 치환 결과를 현재 선택 스텝 UI(응답 변수 추출 탭 포함)에 즉시 반영
                val prevEditorTab = editorTabs.selectedIndex
                refreshEditorFromSelection()
                if (prevEditorTab in 0 until editorTabs.tabCount) {
                    editorTabs.selectedIndex = prevEditorTab
                }
                refreshStepTablePreservingSelection(refreshEditor = false)
                if (syncResult.runtimeContextModified) {
                    refreshExtractedVarsTable()
                }
                saveCurrentScenario(showFeedback = false)
                updateDirtyIndicator()
                anySaved = true
            }
            if (dialog.isProjectGlobalsChanged()) {
                storage.saveGlobalVariables(dialog.getProjectGlobalVariables())
                anySaved = true
            }
            if (dialog.isSecretsChanged()) {
                storage.saveSecrets(dialog.getSecrets())
                anySaved = true
            }
            if (anySaved) {
                scenarioStatusLabel.text = I18nManager.t("status.globalSaved")
                scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                revalidateNativeHeaderComponents()
            }
        }
    }

    private fun refreshScenarioComboBox(selectedPath: String) {
        val paths = storage.listScenarios().toMutableList()
        if (selectedPath.isNotBlank() && selectedPath !in paths) {
            paths.add(selectedPath)
            paths.sortWith(
                compareBy<String>(
                    { ScenarioFileRepository.parentFolderOf(it).lowercase() },
                    { ScenarioFileRepository.baseNameOf(it).lowercase() }
                )
            )
        }
        isUpdatingUi = true
        try {
            scenarioComboModel.removeAllElements()
            paths.forEach { scenarioComboModel.addElement(it) }
            scenarioComboBox.selectedItem = selectedPath
        } finally {
            isUpdatingUi = false
        }
        syncScenarioComboTooltip(selectedPath)
    }

    // [v0.3.0] 시나리오 선택 ComboBox 툴팁('폴더명 / 시나리오명') 및 동적 선호 폭 동기화
    private fun syncScenarioComboTooltip(selectedPath: String?) {
        val label = ScenarioFileRepository.formatDisplayPath(selectedPath.orEmpty())
        scenarioComboBox.toolTipText = label.ifEmpty { null }
        val estimatedWidth = (label.length * 9 + 56).coerceIn(260, 420)
        scenarioComboBox.preferredSize = Dimension(estimatedWidth, 28)
        topToolbarPanel.revalidate()
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
                stepAliasField.text = ""
                methodComboBox.selectedItem = "GET"
                urlField.text = ""
                expectedStatusField.text = "2xx"
                delayField.text = "0"
                paramsTableModel.rowCount = 0
                headersTableModel.rowCount = 0
                bodyTextArea.text = ""
                extractRulesTableModel.rowCount = 0
                assertionsTableModel.rowCount = 0
                updateAssertionsTabTitle()
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
        stepAliasField.text = step.alias
        methodComboBox.selectedItem = HttpMethod.fromString(step.method).name
        urlField.text = step.url
        expectedStatusField.text = step.expectedStatus.ifBlank { "2xx" }
        delayField.text = step.delayMs.coerceAtLeast(0L).toString()

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

        assertionsTableModel.rowCount = 0
        for (assertion in step.assertions) {
            assertionsTableModel.addRow(arrayOf<Any>(assertion.enabled, assertion.jsonPath, assertion.operator, assertion.expectedValue))
        }
        updateAssertionsTabTitle()
    }

    private fun setEditorEnabled(enabled: Boolean) {
        stepAliasField.isEnabled = enabled
        methodComboBox.isEnabled = enabled
        urlField.isEnabled = enabled
        expectedStatusField.isEnabled = enabled
        delayField.isEnabled = enabled
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
        assertionsTable.isEnabled = enabled
        addAssertionBtn.isEnabled = enabled
        addAssertionFromResponseBtn.isEnabled = enabled
        removeAssertionBtn.isEnabled = enabled
    }

    private fun syncEditorToCurrentStep() {
        val step = getActiveEditableStep() ?: return
        step.alias = stepAliasField.text.trim()
        step.method = methodComboBox.selectedItem?.toString() ?: "GET"
        step.url = urlField.text
        step.expectedStatus = expectedStatusField.text.trim().ifEmpty { "2xx" }
        step.delayMs = delayField.text.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
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

        val assertions = mutableListOf<AssertionRule>()
        for (i in 0 until assertionsTableModel.rowCount) {
            val enabled = (assertionsTableModel.getValueAt(i, 0) as? Boolean) ?: true
            val jsonPath = (assertionsTableModel.getValueAt(i, 1)?.toString() ?: "").trim()
            val operator = (assertionsTableModel.getValueAt(i, 2)?.toString() ?: AssertionOperator.EQUALS.symbol).trim()
            val expected = assertionsTableModel.getValueAt(i, 3)?.toString() ?: ""
            if (jsonPath.isNotEmpty() || expected.isNotEmpty()) {
                assertions.add(
                    AssertionRule(
                        enabled = enabled,
                        jsonPath = jsonPath,
                        operator = operator.ifEmpty { AssertionOperator.EQUALS.symbol },
                        expectedValue = expected
                    )
                )
            }
        }
        step.assertions = assertions

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
        stopTableEditing(assertionsTable)
        stopTableEditing(extractedVarsTable)
    }

    private fun stopTableEditing(table: JBTable) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    // [v0.2.0] 테이블 행 갱신 시 현재 선택된 스텝 행이 해제되거나 ListSelectionListener가 오작동하지 않도록 보호
    // 변경 의도: 단건/전체 실행 중 fireTableDataChanged()로 선택이 -1로 초기화되어 편집 패널이 꼬이는 오작동 방지
    private fun refreshStepTablePreservingSelection(forceSelectRow: Int? = null, refreshEditor: Boolean = false) {
        val rowToKeep = forceSelectRow ?: stepTable.selectedRow
        val prevGuard = isUpdatingUi
        isUpdatingUi = true
        try {
            if (stepTableModel.rowCount > 0) {
                stepTableModel.fireTableRowsUpdated(0, stepTableModel.rowCount - 1)
            } else {
                stepTableModel.fireTableDataChanged()
            }
            if (rowToKeep in 0 until stepTable.rowCount) {
                if (stepTable.selectedRow != rowToKeep) {
                    stepTable.setRowSelectionInterval(rowToKeep, rowToKeep)
                }
                stepTable.scrollRectToVisible(stepTable.getCellRect(rowToKeep, 0, true))
            }
        } finally {
            isUpdatingUi = prevGuard
        }
        if (refreshEditor && rowToKeep in 0 until stepTable.rowCount) {
            refreshEditorFromSelection()
        }
    }

    // --- Execution Engine (Background Thread -> EDT invokeLater) ---

    // [v0.4.0] 전체 또는 선행 스텝(1~N번) 순차 실행 지원(maxStepIndex) 및 실행 완료 상태 확정 갱신
    private fun executeFullScenario(maxStepIndex: Int? = null, skipProtectedConfirm: Boolean = false) {
        if (isRunning) return
        stopAllTableEdits()
        syncEditorToCurrentStep()

        if (currentScenario.steps.isEmpty()) {
            Messages.showInfoMessage(project, I18nManager.t("dialog.noSteps.msg"), "Maekom API Runner")
            return
        }
        if (!skipProtectedConfirm && !confirmProtectedEnvironmentExecution(isFullScenario = true)) return

        isQuickSingleMode = false
        quickModeBanner.isVisible = false

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val effectiveMaxIndex = (maxStepIndex ?: currentScenario.steps.lastIndex)
            .coerceIn(0, currentScenario.steps.lastIndex)
        val scenarioSnapshot = currentScenario.deepCopy()
        for (i in currentScenario.steps.indices) {
            scenarioSnapshot.steps[i].id = currentScenario.steps[i].id
        }
        val secrets = storage.loadSecrets()
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()
        val generation = runGeneration.incrementAndGet()

        stepStatuses.clear()
        stepResults.clear()
        lastRuntimeContext.clear()
        currentScenario.steps.forEach { stepStatuses[it.id] = StepStatus.PENDING }
        refreshStepTablePreservingSelection(forceSelectRow = stepTable.selectedRow.takeIf { it >= 0 } ?: 0)

        setRunningState(true)
        statusBadgeLabel.text = I18nManager.t("result.running")
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        scenarioStatusLabel.text = I18nManager.t("status.scenarioRunning")
        scenarioStatusLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        revalidateNativeHeaderComponents()

        clearExecutionLog()
        val runCount = effectiveMaxIndex + 1
        appendExecutionLog("=== ▶ Scenario Run: ${scenarioSnapshot.name} ($runCount/${scenarioSnapshot.steps.size} steps) ===\n")

        ApplicationManager.getApplication().executeOnPooledThread {
            var completedSummary: ScenarioExecutionSummary? = null
            var unexpectedError: Throwable? = null
            try {
                completedSummary = scenarioRunner.runScenario(
                    scenario = scenarioSnapshot,
                    secrets = secrets,
                    projectGlobalVariables = projectGlobals,
                    maxStepIndex = effectiveMaxIndex,
                    listener = object : ScenarioRunListener {
                        override fun onStepStarted(stepIndex: Int, step: ApiStep) {
                            val delayNote = if (step.delayMs > 0L) " (delay ${step.delayMs} ms)" else ""
                            appendExecutionLog("\n[Step #${stepIndex + 1}] ▶ ${step.method.uppercase()} ${step.url}$delayNote ...\n")
                            ApplicationManager.getApplication().invokeLater {
                                if (runGeneration.get() != generation || !isRunning) return@invokeLater
                                stepStatuses[step.id] = StepStatus.RUNNING
                                refreshStepTablePreservingSelection(forceSelectRow = stepIndex, refreshEditor = true)
                                statusBadgeLabel.text = I18nManager.t("result.running")
                                statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
                                revalidateNativeHeaderComponents()
                            }
                        }

                        override fun onStepRequestResolved(stepIndex: Int, step: ApiStep, resolvedRequestLog: String) {
                            appendExecutionLog(resolvedRequestLog + "\n")
                        }

                        override fun onStepCompleted(result: StepExecutionResult) {
                            appendExecutionLog(buildDetailLog(result) + "\n")
                            ApplicationManager.getApplication().invokeLater {
                                if (runGeneration.get() != generation) return@invokeLater
                                stepStatuses[result.stepId] = result.status
                                stepResults[result.stepId] = result
                                lastRuntimeContext.putAll(result.extractedVariables)
                                refreshStepTablePreservingSelection(
                                    forceSelectRow = result.stepIndex,
                                    refreshEditor = (result.status == StepStatus.FAILED)
                                )
                                displayStepResult(result, overwriteLog = false)
                            }
                        }

                        override fun onScenarioCompleted(summary: ScenarioExecutionSummary) {
                            appendExecutionLog("=== ■ Scenario Finished: ${summary.scenarioStatus} (${summary.totalDurationMs} ms) ===\n")
                        }
                    }
                )
            } catch (t: Throwable) {
                unexpectedError = t
                appendExecutionLog("[FATAL ERROR] ${t.message ?: t.javaClass.simpleName}\n")
            } finally {
                val finalSummary = completedSummary
                val finalError = unexpectedError
                ApplicationManager.getApplication().invokeLater {
                    if (runGeneration.get() != generation) return@invokeLater
                    setRunningState(false)

                    if (finalError != null) {
                        statusBadgeLabel.text = "ERROR"
                        statusBadgeLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                        scenarioStatusLabel.text = "⛔ ${finalError.message ?: finalError.javaClass.simpleName}"
                        scenarioStatusLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                        resultTabs.selectedIndex = RESULT_TAB_LOG
                        revalidateNativeHeaderComponents()
                        return@invokeLater
                    }

                    if (finalSummary != null) {
                        lastRuntimeContext.clear()
                        lastRuntimeContext.putAll(finalSummary.finalContext)
                        for (res in finalSummary.stepResults) {
                            stepStatuses[res.stepId] = res.status
                            stepResults[res.stepId] = res
                        }

                        val okCount = finalSummary.stepResults.count { it.status == StepStatus.SUCCESS }
                        val failCount = finalSummary.stepResults.count { it.status == StepStatus.FAILED }

                        when (finalSummary.scenarioStatus) {
                            ScenarioStatus.COMPLETED -> {
                                scenarioStatusLabel.text = I18nManager.t("status.scenarioCompleted", finalSummary.totalDurationMs)
                                scenarioStatusLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                                val lastExecuted = finalSummary.stepResults.lastOrNull { it.status != StepStatus.SKIPPED }
                                if (lastExecuted != null) {
                                    refreshStepTablePreservingSelection(forceSelectRow = lastExecuted.stepIndex, refreshEditor = true)
                                    displayStepResult(lastExecuted, overwriteLog = false)
                                } else {
                                    refreshStepTablePreservingSelection()
                                    statusBadgeLabel.text = I18nManager.t("status.scenarioSummary", okCount, failCount)
                                    statusBadgeLabel.foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                                }
                            }
                            ScenarioStatus.ABORTED -> {
                                scenarioStatusLabel.text = I18nManager.t("status.scenarioAborted", finalSummary.totalDurationMs)
                                scenarioStatusLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                                val failedResult = finalSummary.stepResults.firstOrNull { it.status == StepStatus.FAILED }
                                if (failedResult != null) {
                                    refreshStepTablePreservingSelection(forceSelectRow = failedResult.stepIndex, refreshEditor = true)
                                    displayStepResult(failedResult, overwriteLog = false)
                                    resultTabs.selectedIndex = RESULT_TAB_LOG
                                } else {
                                    refreshStepTablePreservingSelection()
                                    statusBadgeLabel.text = I18nManager.t("status.scenarioAbortedSummary", okCount, failCount)
                                    statusBadgeLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                                }
                            }
                            else -> {
                                refreshStepTablePreservingSelection()
                                statusBadgeLabel.text = I18nManager.t("result.idle")
                                statusBadgeLabel.foreground = JBColor.GRAY
                                scenarioStatusLabel.text = ""
                            }
                        }
                    } else {
                        refreshStepTablePreservingSelection()
                        statusBadgeLabel.text = I18nManager.t("result.idle")
                        statusBadgeLabel.foreground = JBColor.GRAY
                    }
                    revalidateNativeHeaderComponents()
                }
            }
        }
    }

    // [v0.4.0] 단건 실행 직전 선행 변수 누락 감지 가드(1~N번 순차 실행 / 강제 실행 / 취소) 및 선택 행 보존
    private fun executeCurrentSingleStep() {
        if (isRunning) return
        stopAllTableEdits()
        syncEditorToCurrentStep()

        val activeStep = getActiveEditableStep() ?: return
        if (!confirmProtectedEnvironmentExecution(isFullScenario = false)) return
        val selectedRowBeforeRun = if (isQuickSingleMode) -1 else stepTable.selectedRow
        val stepIndex = if (isQuickSingleMode) 0 else selectedRowBeforeRun.coerceAtLeast(0)

        currentScenario.baseUrl = baseUrlField.text.trim().ifEmpty { "http://localhost:8080" }
        currentScenario.ignoreSsl = ignoreSslCheckbox.isSelected

        val secrets = storage.loadSecrets()
        val projectGlobals = storage.loadEnabledGlobalVariablesMap()
        val missingVars = VariableResolver.findMissingVariables(
            step = activeStep,
            scenario = currentScenario,
            runtimeContext = lastRuntimeContext,
            secrets = secrets,
            projectGlobalVariables = projectGlobals
        )
        if (missingVars.isNotEmpty()) {
            val formattedVars = missingVars.joinToString(", ") { "{{$it}}" }
            if (!isQuickSingleMode && selectedRowBeforeRun > 0) {
                val targetStepNo = selectedRowBeforeRun + 1
                val choice = Messages.showDialog(
                    project,
                    I18nManager.t("missingVar.msgWithPrev", formattedVars, targetStepNo),
                    I18nManager.t("missingVar.title"),
                    arrayOf(
                        I18nManager.t("missingVar.runFromPrev", targetStepNo),
                        I18nManager.t("missingVar.forceRun"),
                        I18nManager.t("missingVar.cancel")
                    ),
                    0,
                    Messages.getWarningIcon()
                )
                when (choice) {
                    0 -> {
                        executeFullScenario(maxStepIndex = selectedRowBeforeRun, skipProtectedConfirm = true)
                        return
                    }
                    1 -> { /* Proceed with forced single-step execution */ }
                    else -> return
                }
            } else {
                val choice = Messages.showDialog(
                    project,
                    I18nManager.t("missingVar.msgNoPrev", formattedVars),
                    I18nManager.t("missingVar.title"),
                    arrayOf(
                        I18nManager.t("missingVar.forceRun"),
                        I18nManager.t("missingVar.cancel")
                    ),
                    0,
                    Messages.getWarningIcon()
                )
                if (choice != 0) return
            }
        }

        val scenarioSnapshot = currentScenario.deepCopy()
        val stepSnapshot = activeStep.deepCopy().apply { id = activeStep.id }
        val contextCopy = LinkedHashMap(lastRuntimeContext)
        val generation = runGeneration.incrementAndGet()

        setRunningState(true)
        if (!isQuickSingleMode) {
            stepStatuses[activeStep.id] = StepStatus.RUNNING
            refreshStepTablePreservingSelection(forceSelectRow = selectedRowBeforeRun, refreshEditor = false)
        }
        statusBadgeLabel.text = I18nManager.t("result.singleRunning")
        statusBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        revalidateNativeHeaderComponents()

        clearExecutionLog()
        appendExecutionLog("=== ▶ Single Step Run: ${stepSnapshot.method.uppercase()} ${stepSnapshot.url} ===\n")

        ApplicationManager.getApplication().executeOnPooledThread {
            var stepResult: StepExecutionResult? = null
            var unexpectedError: Throwable? = null
            try {
                val res = scenarioRunner.runSingleStep(
                    step = stepSnapshot,
                    stepIndex = stepIndex,
                    scenario = scenarioSnapshot,
                    secrets = secrets,
                    runtimeContext = contextCopy,
                    projectGlobalVariables = projectGlobals,
                    onRequestResolved = { resolvedLog -> appendExecutionLog(resolvedLog + "\n") }
                )
                stepResult = res
                appendExecutionLog(buildDetailLog(res) + "\n")
            } catch (t: Throwable) {
                unexpectedError = t
                appendExecutionLog("[FATAL ERROR] ${t.message ?: t.javaClass.simpleName}\n")
            } finally {
                val finalResult = stepResult
                val finalError = unexpectedError
                ApplicationManager.getApplication().invokeLater {
                    if (runGeneration.get() != generation) return@invokeLater
                    setRunningState(false)

                    if (finalError != null) {
                        if (!isQuickSingleMode) {
                            stepStatuses[activeStep.id] = StepStatus.FAILED
                            refreshStepTablePreservingSelection(forceSelectRow = selectedRowBeforeRun, refreshEditor = false)
                        }
                        statusBadgeLabel.text = "ERROR"
                        statusBadgeLabel.foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                        resultTabs.selectedIndex = RESULT_TAB_LOG
                        revalidateNativeHeaderComponents()
                        return@invokeLater
                    }

                    if (finalResult != null) {
                        lastRuntimeContext.putAll(finalResult.extractedVariables)
                        if (!isQuickSingleMode) {
                            stepStatuses[finalResult.stepId] = finalResult.status
                            stepResults[finalResult.stepId] = finalResult
                            refreshStepTablePreservingSelection(forceSelectRow = selectedRowBeforeRun, refreshEditor = false)
                        }
                        displayStepResult(finalResult, overwriteLog = false)
                        if (finalResult.status == StepStatus.FAILED) {
                            resultTabs.selectedIndex = RESULT_TAB_LOG
                        } else if (resultTabs.selectedIndex != RESULT_TAB_LOG) {
                            resultTabs.selectedIndex = RESULT_TAB_RESPONSE
                        }
                    }
                }
            }
        }
    }

    private fun stopExecution() {
        if (!isRunning) return
        stopBtn.isEnabled = false
        scenarioStatusLabel.text = I18nManager.t("status.stopping")
        revalidateNativeHeaderComponents()
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

    private fun displayStepResult(result: StepExecutionResult?, overwriteLog: Boolean = true) {
        lastDisplayedResult = result

        if (result == null) {
            statusBadgeLabel.text = I18nManager.t("result.idle")
            statusBadgeLabel.foreground = JBColor.GRAY
            durationLabel.text = "0 ms"
            responseBodyArea.text = ""
            if (overwriteLog) {
                detailLogArea.text = ""
            }
            refreshExtractedVarsTable()
            revalidateNativeHeaderComponents()
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

        refreshExtractedVarsTable(result.extractedVariables)

        responseBodyArea.text = when {
            resp != null && resp.formattedBody.isNotEmpty() -> resp.formattedBody
            resp != null && resp.rawBody.isNotEmpty() -> resp.rawBody
            result.failureReason != null -> "[ERROR] ${result.failureReason}"
            else -> ""
        }
        responseBodyArea.caretPosition = 0

        if (overwriteLog) {
            detailLogArea.text = buildDetailLog(result)
            detailLogArea.caretPosition = detailLogArea.document.length
            detailLogArea.revalidate()
            detailLogArea.repaint()
        }
        revalidateNativeHeaderComponents()
    }

    private fun buildDetailLog(result: StepExecutionResult): String = buildString {
        appendLine(I18nManager.t("log.summaryHeader"))
        appendLine(I18nManager.t("log.stepStatus", result.status))
        if (result.failureReason != null) {
            appendLine(I18nManager.t("log.failReason", result.failureReason))
        }
        if (result.assertionResults.isNotEmpty()) {
            appendLine("-- Response Assertions (${result.assertionResults.count { it.passed }}/${result.assertionResults.size} PASS) --")
            result.assertionResults.forEach { eval ->
                val icon = if (eval.passed) "✅" else "❌"
                appendLine("  $icon ${eval.message}")
            }
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

    private fun isAnyTableEditing(): Boolean =
        stepTable.isEditing ||
            paramsTable.isEditing ||
            headersTable.isEditing ||
            extractRulesTable.isEditing ||
            assertionsTable.isEditing ||
            extractedVarsTable.isEditing

    // [v0.4.5] 포커스 해제 시 Darcula 포커스 링 테두리가 그려지는 직전 포커스 컴포넌트 및 상위 JScrollPane/EditorTextField 래퍼 수집
    private fun collectFocusWrapperAncestors(focusOwner: Component): List<Component> {
        val targets = mutableListOf<Component>()
        var current: Component? = focusOwner
        while (current != null && current !== this) {
            val className = current.javaClass.name
            if (current === focusOwner ||
                current is JScrollPane ||
                current is JTextComponent ||
                className.contains("EditorTextField") ||
                className.contains("EditorComponent")
            ) {
                targets.add(current)
            }
            current = current.parent
        }
        return targets
    }

    private fun isInteractiveTarget(component: Component): Boolean {
        var current: Component? = component
        while (current != null && current !== this) {
            val className = current.javaClass.name
            if (current is JTextComponent ||
                current is AbstractButton ||
                current is JComboBox<*> ||
                current is JTable ||
                current is JTableHeader ||
                current is JList<*> ||
                current is JTree ||
                current is JTabbedPane ||
                current is JScrollBar ||
                current is JMenuItem ||
                current is LanguageSegmentSwitch ||
                className.contains("EditorTextField") ||
                className.contains("EditorComponent")
            ) {
                return true
            }
            current = current.parent
        }
        return false
    }

    override fun dispose() {
        runCatching {
            Toolkit.getDefaultToolkit().removeAWTEventListener(backgroundFocusClearListener)
        }
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
            1 -> ApiStep::class.java
            else -> ApiStep::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val step = currentScenario.steps[rowIndex]
            return when (columnIndex) {
                0 -> step.enabled
                1 -> step
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

    // [v0.1.3] 상태 아이콘과 함께 HTTP 상태 코드(예: 200 OK) 또는 다국어 상태 문구(대기/실행 중/실패/스킵)를 온전히 표기
    private inner class StatusIconCellRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.LEFT
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val step = value as? ApiStep
            val status = step?.let { stepStatuses[it.id] } ?: StepStatus.PENDING
            val httpCode = step?.let { stepResults[it.id]?.response?.statusCode }
            val labelText = when (status) {
                StepStatus.PENDING -> I18nManager.t("steps.status.pending")
                StepStatus.RUNNING -> I18nManager.t("steps.status.running")
                StepStatus.SUCCESS -> if (httpCode != null) "$httpCode OK" else I18nManager.t("steps.status.success")
                StepStatus.FAILED -> if (httpCode != null) "$httpCode ${I18nManager.t("steps.status.failed")}" else I18nManager.t("steps.status.failed")
                StepStatus.SKIPPED -> I18nManager.t("steps.status.skipped")
            }

            super.getTableCellRendererComponent(table, labelText, isSelected, hasFocus, row, column)
            icon = statusToIcon(status)
            iconTextGap = 5
            if (!isSelected) {
                foreground = when (status) {
                    StepStatus.SUCCESS -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
                    StepStatus.FAILED -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
                    StepStatus.RUNNING -> JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
                    else -> JBColor.GRAY
                }
            }
            toolTipText = labelText
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
                val aliasPrefix = step.alias.trim().takeIf { it.isNotEmpty() }?.let { rawAlias ->
                    val escaped = rawAlias
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    "[$escaped] "
                }.orEmpty()
                if (!step.enabled) {
                    "<html><span style='color:#9E9E9E'><s>$aliasPrefix<b>$method</b> $pathEscaped</s></span></html>"
                } else {
                    "<html>$aliasPrefix<b style='color:$colorHex'>$method</b> $pathEscaped</html>"
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
