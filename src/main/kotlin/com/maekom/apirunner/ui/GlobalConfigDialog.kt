package com.maekom.apirunner.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.BulkJsonVariableParser
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.CommonHeaderItem
import com.maekom.apirunner.model.GlobalVariableItem
import com.maekom.apirunner.storage.ScenarioFileRepository
import com.maekom.apirunner.storage.ScenarioStorageService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// [v0.4.1] [변수·헤더 관리] 5탭 단일 통합 다이얼로그 개편(공통 헤더 / 시나리오 변수 / 프로젝트 전역 변수 인라인 편집 /
//          로컬 시크릿 / 내장 동적 변수), 탭별 적용 범위 배지 표시 및 셀 안전 커밋·변경된 저장소만 선택 저장 지원
// 변경 의도: 상단 툴바의 분리된 두 관리 창을 하나로 통합해 가로 공간을 절약하고, 프로젝트 전역 변수를 팝업 없이
//          인라인으로 직접 편집하며 중복 키 검증·복원 및 기존 디스크 저장 경로 100% 호환성을 보장

enum class HeaderImportMode {
    MERGE,
    OVERWRITE
}

/**
 * Unified 5-tab Variables & Headers Manager Dialog:
 * 0. Common Headers ([Scenario Only]) -> `.maekom/scenarios/**/*.json`
 * 1. Scenario Variables ([Scenario Only]) -> `.maekom/scenarios/**/*.json`
 * 2. Project Global Variables ([Project Shared], inline editable) -> `.maekom/global-variables.json`
 * 3. Local Secrets ([Project Shared · Git Ignored]) -> `.maekom/secrets.local.json`
 * 4. Built-in Dynamic Variables ([Read-Only Reference])
 */
class GlobalConfigDialog(
    private val project: Project,
    private val currentScenario: ApiScenario,
    private val storage: ScenarioStorageService,
    private val initialCommonHeaders: List<CommonHeaderItem>,
    private val initialVariables: Map<String, String>,
    private val initialSecrets: Map<String, String>,
    private val currentScenarioPath: String = currentScenario.name,
    private val initialProjectGlobals: List<GlobalVariableItem> = storage.loadGlobalVariables(),
    private val initialSelectedTab: Int = TAB_COMMON_HEADERS
) : DialogWrapper(project, true) {

    companion object {
        const val TAB_COMMON_HEADERS = 0
        const val TAB_SCENARIO_VARS = 1
        const val TAB_PROJECT_GLOBALS = 2
        const val TAB_LOCAL_SECRETS = 3
        const val TAB_DYNAMIC_REF = 4

        /**
         * Pure helper for Merge / Overwrite of common headers (unit-testable).
         */
        fun mergeCommonHeaders(
            existing: List<CommonHeaderItem>,
            imported: List<CommonHeaderItem>,
            mode: HeaderImportMode
        ): MutableList<CommonHeaderItem> {
            if (mode == HeaderImportMode.OVERWRITE) {
                return imported.map { it.deepCopy() }.toMutableList()
            }
            val result = existing.map { it.deepCopy() }.toMutableList()
            for (item in imported) {
                val idx = result.indexOfFirst { it.key.equals(item.key, ignoreCase = true) }
                if (idx >= 0) {
                    result[idx] = item.deepCopy()
                } else {
                    result.add(item.deepCopy())
                }
            }
            return result
        }
    }

    private val commonHeadersModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("global.col.enabled"),
            I18nManager.t("global.col.key"),
            I18nManager.t("global.col.value"),
            I18nManager.t("global.col.secret")
        ),
        0
    ) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0 || columnIndex == 3) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }

    private val variablesModel = createKeyValueModel(initialVariables)

    // Tab 2: Project Global Variables — inline editable ([활성] | [Key] | [Value] | [Secret] | [복사])
    private val projectGlobalsModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("vars.global.col.enabled"),
            I18nManager.t("vars.global.col.key"),
            I18nManager.t("vars.global.col.value"),
            I18nManager.t("vars.global.col.secret"),
            I18nManager.t("vars.col.action")
        ),
        0
    ) {
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0, 3 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = (column != 4)
    }
    private val previousGlobalKeys = mutableListOf<String>()
    private var isUpdatingGlobalsTable = false

    private val secretsModel = createKeyValueModel(initialSecrets)

    // Tab 4: Built-in Dynamic Variables (Read-only reference)
    private val dynamicTableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("vars.col.token"),
            I18nManager.t("vars.col.desc"),
            I18nManager.t("vars.col.preview"),
            I18nManager.t("vars.col.action")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private lateinit var headersTable: JBTable
    private lateinit var variablesTable: JBTable
    private lateinit var projectGlobalsTable: JBTable
    private lateinit var secretsTable: JBTable
    private lateinit var dynamicTable: JBTable
    private val copyFeedbackLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
        foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
    }

    init {
        title = I18nManager.t("global.unified.title")
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        for (item in initialCommonHeaders) {
            commonHeadersModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret))
        }
        populateProjectGlobalsModel(initialProjectGlobals)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 6))
        root.preferredSize = Dimension(860, 500)

        val tabbedPane = JBTabbedPane()

        headersTable = JBTable(commonHeadersModel).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 25
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 56; minWidth = 44; preferredWidth = 50 }
            columnModel.getColumn(1).preferredWidth = 220
            columnModel.getColumn(2).apply {
                preferredWidth = 340
                cellRenderer = MaskedValueCellRenderer(secretColumnIndex = 3)
            }
            columnModel.getColumn(3).apply { maxWidth = 68; minWidth = 54; preferredWidth = 60 }
        }
        variablesTable = createEditableTable(variablesModel)
        projectGlobalsTable = createProjectGlobalsTable()
        secretsTable = createEditableTable(secretsModel)
        dynamicTable = createDynamicVariablesTable()

        val scenarioDisplay = ScenarioFileRepository.formatDisplayPath(currentScenarioPath)
            .ifBlank { currentScenario.name }

        tabbedPane.addTab(
            I18nManager.t("global.tab.headers"),
            buildCommonHeadersTab(scenarioDisplay)
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.vars"),
            buildKeyValueTableTab(
                scopeText = I18nManager.t("global.scope.scenario", scenarioDisplay),
                scopeColor = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6)),
                table = variablesTable,
                model = variablesModel,
                hint = I18nManager.t("global.tab.vars.hint"),
                bulkTargetLabel = I18nManager.t("global.tab.vars")
            )
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.projectGlobals"),
            buildProjectGlobalsTab()
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.secrets"),
            buildKeyValueTableTab(
                scopeText = I18nManager.t("global.scope.projectGitIgnored"),
                scopeColor = JBColor(Color(0xE6, 0x51, 0x00), Color(0xFF, 0xB7, 0x4D)),
                table = secretsTable,
                model = secretsModel,
                hint = I18nManager.t("global.tab.secrets.hint"),
                bulkTargetLabel = I18nManager.t("global.tab.secrets")
            )
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.dynamicRef"),
            buildDynamicVariablesTab()
        )

        tabbedPane.selectedIndex = initialSelectedTab.coerceIn(0, tabbedPane.tabCount - 1)
        reloadDynamicVariables()

        root.add(tabbedPane, BorderLayout.CENTER)
        root.add(copyFeedbackLabel, BorderLayout.SOUTH)
        return root
    }

    private fun buildScopeBanner(scopeText: String, scopeColor: Color, hintHtml: String, rightAction: JButton? = null): JPanel {
        val banner = JPanel(BorderLayout(8, 4))
        val badgeLabel = JBLabel(scopeText).apply {
            font = font.deriveFont(Font.BOLD, 11.5f)
            foreground = scopeColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(scopeColor, 1, true),
                JBUI.Borders.empty(2, 7)
            )
        }
        val topRow = JPanel(BorderLayout(8, 0)).apply {
            val leftWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(badgeLabel) }
            add(leftWrap, BorderLayout.WEST)
            if (rightAction != null) {
                add(rightAction, BorderLayout.EAST)
            }
        }
        val hintLabel = JBLabel("<html>$hintHtml</html>").apply {
            foreground = JBColor.GRAY
        }
        banner.add(topRow, BorderLayout.NORTH)
        banner.add(hintLabel, BorderLayout.SOUTH)
        return banner
    }

    private fun buildCommonHeadersTab(scenarioDisplay: String): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val importBtn = JButton(I18nManager.t("global.importHeaders.btn")).apply {
            addActionListener { openImportFromScenarioDialog() }
        }
        val headerBanner = buildScopeBanner(
            scopeText = I18nManager.t("global.scope.scenario", scenarioDisplay),
            scopeColor = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6)),
            hintHtml = I18nManager.t("global.tab.headers.hint"),
            rightAction = importBtn
        )

        panel.add(headerBanner, BorderLayout.NORTH)
        panel.add(JBScrollPane(headersTable), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("global.add")).apply {
            addActionListener {
                stopEditing(headersTable)
                commonHeadersModel.addRow(arrayOf<Any>(true, "", "", false))
                val row = commonHeadersModel.rowCount - 1
                headersTable.setRowSelectionInterval(row, row)
                headersTable.editCellAt(row, 1)
            }
        }
        val removeBtn = JButton(I18nManager.t("global.remove")).apply {
            addActionListener {
                stopEditing(headersTable)
                val selected = headersTable.selectedRows
                for (i in selected.indices.reversed()) {
                    commonHeadersModel.removeRow(selected[i])
                }
            }
        }
        btnBar.add(addBtn)
        btnBar.add(removeBtn)
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    private fun createProjectGlobalsTable(): JBTable {
        val table = JBTable(projectGlobalsModel).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 25
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 54; minWidth = 44; preferredWidth = 48 }
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).apply {
                preferredWidth = 320
                cellRenderer = MaskedValueCellRenderer(secretColumnIndex = 3)
            }
            columnModel.getColumn(3).apply { maxWidth = 86; minWidth = 64; preferredWidth = 76 }
            columnModel.getColumn(4).apply {
                maxWidth = 72
                minWidth = 58
                preferredWidth = 64
                cellRenderer = InlineCopyCellRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0 || row >= projectGlobalsModel.rowCount) return
                    if (col == 4) {
                        stopEditing(this@apply)
                        val key = (projectGlobalsModel.getValueAt(row, 1)?.toString() ?: "").trim()
                        if (key.isNotEmpty()) {
                            copyTokenWithFeedback("{{$key}}")
                        }
                    }
                }
            })
        }

        // [v0.4.1] 프로젝트 전역 변수 인라인 Key 편집 시 중복 키·예약 접두사($) 검증 및 충돌 시 이전 값 복원
        projectGlobalsModel.addTableModelListener { e ->
            if (isUpdatingGlobalsTable || e.type != TableModelEvent.UPDATE) return@addTableModelListener
            val row = e.firstRow
            if (row !in 0 until projectGlobalsModel.rowCount) return@addTableModelListener
            if (e.column == 3) {
                table.repaint()
                return@addTableModelListener
            }
            if (e.column != 1) return@addTableModelListener

            val prevKey = previousGlobalKeys.getOrElse(row) { "" }
            val newKey = (projectGlobalsModel.getValueAt(row, 1)?.toString() ?: "").trim()

            if (newKey.startsWith("$")) {
                Messages.showWarningDialog(
                    project,
                    I18nManager.t("global.projectGlobals.reservedKeyMsg"),
                    I18nManager.t("global.projectGlobals.dupKeyTitle")
                )
                restoreGlobalKeyCell(row, prevKey)
                return@addTableModelListener
            }

            if (newKey.isNotEmpty()) {
                val duplicate = (0 until projectGlobalsModel.rowCount).any { otherRow ->
                    otherRow != row &&
                        (projectGlobalsModel.getValueAt(otherRow, 1)?.toString() ?: "").trim()
                            .equals(newKey, ignoreCase = true)
                }
                if (duplicate) {
                    Messages.showWarningDialog(
                        project,
                        I18nManager.t("global.projectGlobals.dupKeyMsg", newKey),
                        I18nManager.t("global.projectGlobals.dupKeyTitle")
                    )
                    restoreGlobalKeyCell(row, prevKey)
                    return@addTableModelListener
                }
            }

            if (row < previousGlobalKeys.size) {
                previousGlobalKeys[row] = newKey
            }
            if (newKey != projectGlobalsModel.getValueAt(row, 1)?.toString()) {
                restoreGlobalKeyCell(row, newKey)
            }
        }

        return table
    }

    private fun restoreGlobalKeyCell(row: Int, value: String) {
        isUpdatingGlobalsTable = true
        try {
            projectGlobalsModel.setValueAt(value, row, 1)
        } finally {
            isUpdatingGlobalsTable = false
        }
    }

    private fun populateProjectGlobalsModel(items: List<GlobalVariableItem>) {
        isUpdatingGlobalsTable = true
        try {
            projectGlobalsModel.rowCount = 0
            previousGlobalKeys.clear()
            val copyLabel = I18nManager.t("vars.copyBtn")
            for (item in items) {
                projectGlobalsModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret, copyLabel))
                previousGlobalKeys.add(item.key)
            }
        } finally {
            isUpdatingGlobalsTable = false
        }
    }

    private fun buildProjectGlobalsTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val bulkImportBtn = JButton(I18nManager.t("bulk.btn")).apply {
            addActionListener { openBulkImportForProjectGlobals() }
        }
        val banner = buildScopeBanner(
            scopeText = I18nManager.t("global.scope.project"),
            scopeColor = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x6C, 0xD9, 0x7E)),
            hintHtml = I18nManager.t("global.projectGlobals.inlineHint"),
            rightAction = bulkImportBtn
        )
        panel.add(banner, BorderLayout.NORTH)
        panel.add(JBScrollPane(projectGlobalsTable), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("global.add")).apply {
            addActionListener {
                stopEditing(projectGlobalsTable)
                isUpdatingGlobalsTable = true
                try {
                    projectGlobalsModel.addRow(arrayOf<Any>(true, "", "", false, I18nManager.t("vars.copyBtn")))
                    previousGlobalKeys.add("")
                } finally {
                    isUpdatingGlobalsTable = false
                }
                val row = projectGlobalsModel.rowCount - 1
                projectGlobalsTable.setRowSelectionInterval(row, row)
                projectGlobalsTable.editCellAt(row, 1)
            }
        }
        val removeBtn = JButton(I18nManager.t("global.remove")).apply {
            addActionListener {
                stopEditing(projectGlobalsTable)
                val selected = projectGlobalsTable.selectedRows
                isUpdatingGlobalsTable = true
                try {
                    for (i in selected.indices.reversed()) {
                        projectGlobalsModel.removeRow(selected[i])
                        if (selected[i] in previousGlobalKeys.indices) {
                            previousGlobalKeys.removeAt(selected[i])
                        }
                    }
                } finally {
                    isUpdatingGlobalsTable = false
                }
            }
        }
        btnBar.add(addBtn)
        btnBar.add(removeBtn)
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    private fun openBulkImportForProjectGlobals() {
        stopEditing(projectGlobalsTable)
        val currentList = getProjectGlobalVariables()
        val dialog = BulkJsonImportDialog(
            project = project,
            targetName = I18nManager.t("global.tab.projectGlobals"),
            globalVariablesProvider = { currentList },
            scenarioProvider = { currentScenario },
            isImportingIntoGlobalVars = true
        )
        if (!dialog.showAndGet()) return

        val imported = dialog.getImportedItems()
        val merged = BulkJsonVariableParser.mergeItems(currentList, imported, dialog.getSelectedMergeMode())
        populateProjectGlobalsModel(merged)
        Messages.showInfoMessage(project, I18nManager.t("bulk.success", imported.size), I18nManager.t("global.tab.projectGlobals"))
    }

    private fun createDynamicVariablesTable(): JBTable {
        return JBTable(dynamicTableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 25
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).preferredWidth = 170
            columnModel.getColumn(1).preferredWidth = 280
            columnModel.getColumn(2).preferredWidth = 220
            columnModel.getColumn(3).apply {
                maxWidth = 72
                minWidth = 58
                preferredWidth = 64
                cellRenderer = InlineCopyCellRenderer()
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    if (col == 3 || e.clickCount == 2) {
                        val token = dynamicTableModel.getValueAt(row, 0)?.toString().orEmpty()
                        if (token.isNotEmpty()) {
                            copyTokenWithFeedback(token)
                        }
                    }
                }
            })
        }
    }

    private fun buildDynamicVariablesTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val refreshBtn = JButton(I18nManager.t("vars.dialog.refresh")).apply {
            addActionListener { reloadDynamicVariables() }
        }
        val banner = buildScopeBanner(
            scopeText = I18nManager.t("global.scope.readonly"),
            scopeColor = JBColor.GRAY,
            hintHtml = I18nManager.t("global.dynamic.hint"),
            rightAction = refreshBtn
        )
        panel.add(banner, BorderLayout.NORTH)
        panel.add(JBScrollPane(dynamicTable), BorderLayout.CENTER)
        return panel
    }

    private fun reloadDynamicVariables() {
        dynamicTableModel.rowCount = 0
        val copyLabel = I18nManager.t("vars.copyBtn")
        for (spec in VariableResolver.DYNAMIC_VARIABLES) {
            dynamicTableModel.addRow(
                arrayOf<Any>(
                    spec.token,
                    I18nManager.t(spec.descKey),
                    spec.evaluator(),
                    copyLabel
                )
            )
        }
    }

    private fun copyTokenWithFeedback(token: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(token))
        copyFeedbackLabel.text = I18nManager.t("vars.copiedMsg", token)
    }

    private fun openImportFromScenarioDialog() {
        stopEditing(headersTable)
        val normActive = ScenarioFileRepository.normalizeRelativePath(currentScenarioPath)
        val otherScenarios = storage.listScenarios().filter { !it.equals(normActive, ignoreCase = true) }
        if (otherScenarios.isEmpty()) {
            Messages.showInfoMessage(project, I18nManager.t("global.importHeaders.empty"), I18nManager.t("global.importHeaders.title"))
            return
        }

        val dialog = ImportCommonHeadersDialog(project, otherScenarios, storage)
        if (!dialog.showAndGet()) return

        val importedItems = dialog.getSelectedHeaders()
        if (importedItems.isEmpty()) {
            Messages.showInfoMessage(project, I18nManager.t("global.importHeaders.noHeaders"), I18nManager.t("global.importHeaders.title"))
            return
        }

        val mergedList = mergeCommonHeaders(getCommonHeaderItems(), importedItems, dialog.getSelectedMode())
        commonHeadersModel.rowCount = 0
        for (item in mergedList) {
            commonHeadersModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret))
        }
    }

    // [v0.4.1] Enter를 누르지 않고 바로 [저장] 클릭 시 모든 편집 테이블의 마지막 입력 셀을 안전하게 커밋
    override fun doOKAction() {
        stopEditing(headersTable)
        stopEditing(variablesTable)
        stopEditing(projectGlobalsTable)
        stopEditing(secretsTable)
        super.doOKAction()
    }

    fun getCommonHeaderItems(): MutableList<CommonHeaderItem> {
        val result = mutableListOf<CommonHeaderItem>()
        for (i in 0 until commonHeadersModel.rowCount) {
            val enabled = (commonHeadersModel.getValueAt(i, 0) as? Boolean) ?: true
            val key = (commonHeadersModel.getValueAt(i, 1)?.toString() ?: "").trim()
            val value = commonHeadersModel.getValueAt(i, 2)?.toString() ?: ""
            val secret = (commonHeadersModel.getValueAt(i, 3) as? Boolean) ?: false
            if (key.isNotEmpty()) {
                result.add(CommonHeaderItem(enabled = enabled, key = key, value = value, secret = secret))
            }
        }
        return result
    }

    fun getGlobalVariables(): LinkedHashMap<String, String> = extractMap(variablesModel)

    fun getProjectGlobalVariables(): MutableList<GlobalVariableItem> {
        val result = mutableListOf<GlobalVariableItem>()
        for (i in 0 until projectGlobalsModel.rowCount) {
            val enabled = (projectGlobalsModel.getValueAt(i, 0) as? Boolean) ?: true
            val key = (projectGlobalsModel.getValueAt(i, 1)?.toString() ?: "").trim()
            val value = projectGlobalsModel.getValueAt(i, 2)?.toString() ?: ""
            val secret = (projectGlobalsModel.getValueAt(i, 3) as? Boolean) ?: false
            if (key.isNotEmpty()) {
                result.add(GlobalVariableItem(enabled = enabled, key = key, value = value, secret = secret))
            }
        }
        return result
    }

    fun getSecrets(): LinkedHashMap<String, String> = extractMap(secretsModel)

    fun isScenarioTabChanged(): Boolean {
        val normalizedInitialHeaders = initialCommonHeaders
            .filter { it.key.trim().isNotEmpty() }
            .map { it.copy(key = it.key.trim()) }
        val normalizedInitialVars = LinkedHashMap<String, String>().apply {
            initialVariables.forEach { (k, v) -> if (k.trim().isNotEmpty()) put(k.trim(), v) }
        }
        return getCommonHeaderItems() != normalizedInitialHeaders || getGlobalVariables() != normalizedInitialVars
    }

    fun isProjectGlobalsChanged(): Boolean {
        val normalizedInitial = initialProjectGlobals
            .filter { it.key.trim().isNotEmpty() }
            .map { it.copy(key = it.key.trim()) }
        return getProjectGlobalVariables() != normalizedInitial
    }

    fun isSecretsChanged(): Boolean {
        val normalizedInitial = LinkedHashMap<String, String>().apply {
            initialSecrets.forEach { (k, v) -> if (k.trim().isNotEmpty()) put(k.trim(), v) }
        }
        return getSecrets() != normalizedInitial
    }

    private fun buildKeyValueTableTab(
        scopeText: String,
        scopeColor: Color,
        table: JBTable,
        model: DefaultTableModel,
        hint: String,
        bulkTargetLabel: String
    ): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val bulkImportBtn = JButton(I18nManager.t("bulk.btn")).apply {
            addActionListener { openBulkImportForKeyValueTable(table, model, bulkTargetLabel) }
        }
        val banner = buildScopeBanner(
            scopeText = scopeText,
            scopeColor = scopeColor,
            hintHtml = hint,
            rightAction = bulkImportBtn
        )

        panel.add(banner, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("global.add")).apply {
            addActionListener {
                stopEditing(table)
                model.addRow(arrayOf<Any>("", ""))
                val row = model.rowCount - 1
                table.setRowSelectionInterval(row, row)
                table.editCellAt(row, 0)
            }
        }
        val removeBtn = JButton(I18nManager.t("global.remove")).apply {
            addActionListener {
                stopEditing(table)
                val selected = table.selectedRows
                for (i in selected.indices.reversed()) {
                    model.removeRow(selected[i])
                }
            }
        }
        btnBar.add(addBtn)
        btnBar.add(removeBtn)
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    private fun openBulkImportForKeyValueTable(
        table: JBTable,
        model: DefaultTableModel,
        targetLabel: String
    ) {
        stopEditing(table)
        val dialog = BulkJsonImportDialog(
            project = project,
            targetName = targetLabel,
            globalVariablesProvider = { getProjectGlobalVariables() },
            scenarioProvider = { currentScenario },
            isImportingIntoGlobalVars = false
        )
        if (!dialog.showAndGet()) return

        val imported = dialog.getImportedItems()
        val merged = BulkJsonVariableParser.mergeIntoMap(extractMap(model), imported, dialog.getSelectedMergeMode())
        model.rowCount = 0
        for ((k, v) in merged) {
            model.addRow(arrayOf<Any>(k, v))
        }
        Messages.showInfoMessage(project, I18nManager.t("bulk.success", imported.size), targetLabel)
    }

    private fun createKeyValueModel(initial: Map<String, String>): DefaultTableModel {
        val model = object : DefaultTableModel(
            arrayOf<Any>(I18nManager.t("global.col.key"), I18nManager.t("global.col.value")),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        for ((k, v) in initial) {
            model.addRow(arrayOf<Any>(k, v))
        }
        return model
    }

    private fun createEditableTable(model: DefaultTableModel): JBTable {
        return JBTable(model).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 25
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
        }
    }

    private fun stopEditing(table: JBTable) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    private fun extractMap(model: DefaultTableModel): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        for (i in 0 until model.rowCount) {
            val key = (model.getValueAt(i, 0)?.toString() ?: "").trim()
            val value = model.getValueAt(i, 1)?.toString() ?: ""
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private class InlineCopyCellRenderer : DefaultTableCellRenderer() {
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
            super.getTableCellRendererComponent(table, "[ ${value ?: ""} ]", isSelected, hasFocus, row, column)
            font = font.deriveFont(Font.BOLD)
            if (!isSelected) {
                foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            }
            return this
        }
    }
}

/**
 * Preview & Import dialog allowing users to pick another scenario file under `.maekom/scenarios/`,
 * inspect its Common Headers (Enabled, Key, Value, Secret), and apply Merge or Overwrite.
 */
class ImportCommonHeadersDialog(
    project: Project,
    scenarioNames: List<String>,
    private val storage: ScenarioStorageService
) : DialogWrapper(project, true) {

    private val scenarioCombo = ComboBox(DefaultComboBoxModel(scenarioNames.toTypedArray()))
    private val mergeRadio = JBRadioButton(I18nManager.t("global.importHeaders.merge"), true)
    private val overwriteRadio = JBRadioButton(I18nManager.t("global.importHeaders.overwrite"), false)

    private val previewModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("global.col.enabled"),
            I18nManager.t("global.col.key"),
            I18nManager.t("global.col.value"),
            I18nManager.t("global.col.secret")
        ),
        0
    ) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0 || columnIndex == 3) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private var loadedHeaders: List<CommonHeaderItem> = emptyList()

    init {
        title = I18nManager.t("global.importHeaders.title")
        setOKButtonText(I18nManager.t("global.importHeaders.apply"))
        setCancelButtonText(I18nManager.t("global.cancel"))

        val group = ButtonGroup()
        group.add(mergeRadio)
        group.add(overwriteRadio)

        scenarioCombo.addActionListener { reloadPreview() }
        reloadPreview()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(620, 340)
        root.border = JBUI.Borders.empty(6)

        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        scenarioCombo.preferredSize = Dimension(320, 28)
        scenarioCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val formatted = ScenarioFileRepository.formatDisplayPath(value?.toString().orEmpty())
                return super.getListCellRendererComponent(list, formatted, index, isSelected, cellHasFocus)
            }
        }
        topPanel.add(JBLabel(I18nManager.t("global.importHeaders.selectLabel")))
        topPanel.add(scenarioCombo)
        root.add(topPanel, BorderLayout.NORTH)

        val previewTable = JBTable(previewModel).apply {
            rowHeight = 24
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 56; preferredWidth = 50 }
            columnModel.getColumn(1).preferredWidth = 170
            columnModel.getColumn(2).apply {
                preferredWidth = 260
                cellRenderer = MaskedValueCellRenderer(secretColumnIndex = 3)
            }
            columnModel.getColumn(3).apply { maxWidth = 68; preferredWidth = 60 }
        }
        root.add(JBScrollPane(previewTable), BorderLayout.CENTER)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        modePanel.add(JBLabel(I18nManager.t("global.importHeaders.modeLabel")))
        modePanel.add(mergeRadio)
        modePanel.add(overwriteRadio)
        root.add(modePanel, BorderLayout.SOUTH)

        return root
    }

    private fun reloadPreview() {
        val selected = scenarioCombo.selectedItem as? String ?: return
        loadedHeaders = storage.loadCommonHeadersOfScenario(selected)
        previewModel.rowCount = 0
        for (item in loadedHeaders) {
            previewModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret))
        }
    }

    fun getSelectedHeaders(): List<CommonHeaderItem> = loadedHeaders

    fun getSelectedMode(): HeaderImportMode =
        if (overwriteRadio.isSelected) HeaderImportMode.OVERWRITE else HeaderImportMode.MERGE
}

/**
 * Masks cell text as `••••••••` when the row's Secret boolean column is true.
 */
class MaskedValueCellRenderer(private val secretColumnIndex: Int) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val isSecret = (table?.model?.getValueAt(row, secretColumnIndex) as? Boolean) ?: false
        val rawText = value?.toString().orEmpty()
        val displayText = if (isSecret && rawText.isNotEmpty()) "••••••••" else rawText
        return super.getTableCellRendererComponent(table, displayText, isSelected, hasFocus, row, column)
    }
}
