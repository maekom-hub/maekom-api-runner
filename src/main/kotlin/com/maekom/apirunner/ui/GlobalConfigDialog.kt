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
import com.maekom.apirunner.engine.ScenarioVarOrigin
import com.maekom.apirunner.engine.ScenarioVarRow
import com.maekom.apirunner.engine.ScenarioVariableSyncEngine
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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DropMode
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// [v0.4.4] 공통 헤더·프로젝트 전역 변수·시나리오 변수·로컬 시크릿 4개 편집 탭 행 순서 이동(▲/▼ 버튼 + DropMode.INSERT_ROWS 드래그 앤 드롭) 구현
// 변경 의도: 다중 선택 안전 이동, 편집 중 셀 자동 커밋, 시나리오 변수 추출 규칙 참조(stepIndex, extractorIndex) 불변 유지 및 순서 영속 저장 보장

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
    @Suppress("UNUSED_PARAMETER") initialVariables: Map<String, String>,
    private val initialSecrets: Map<String, String>,
    private val currentScenarioPath: String = currentScenario.name,
    private val initialProjectGlobals: List<GlobalVariableItem> = storage.loadGlobalVariables(),
    private val initialSelectedTab: Int = TAB_COMMON_HEADERS,
    private val runtimeContext: Map<String, String> = emptyMap()
) : DialogWrapper(project, true) {

    companion object {
        const val TAB_COMMON_HEADERS = 0
        const val TAB_PROJECT_GLOBALS = 1
        const val TAB_SCENARIO_VARS = 2
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

        // [v0.4.4] 다중 선택(연속/비연속) 행의 안전한 한 칸 위/아래 이동 순열 계산 유틸 (단위 테스트 가능)
        fun computeMoveStepOrder(totalSize: Int, selectedIndices: IntArray, delta: Int): Pair<List<Int>, IntArray>? {
            if (totalSize <= 1 || selectedIndices.isEmpty() || (delta != -1 && delta != 1)) return null
            val valid = selectedIndices.filter { it in 0 until totalSize }.distinct().sorted()
            if (valid.isEmpty()) return null
            if (delta == -1 && valid.first() <= 0) return null
            if (delta == 1 && valid.last() >= totalSize - 1) return null

            val order = (0 until totalSize).toMutableList()
            if (delta == -1) {
                for (idx in valid) {
                    val tmp = order[idx - 1]
                    order[idx - 1] = order[idx]
                    order[idx] = tmp
                }
                return order to valid.map { it - 1 }.toIntArray()
            } else {
                for (idx in valid.reversed()) {
                    val tmp = order[idx + 1]
                    order[idx + 1] = order[idx]
                    order[idx] = tmp
                }
                return order to valid.map { it + 1 }.toIntArray()
            }
        }

        // [v0.4.4] 마우스 드래그 앤 드롭(DropMode.INSERT_ROWS) 삽입 위치 기반 행 재배치 순열 계산 유틸
        fun computeDropReorder(totalSize: Int, draggedIndices: IntArray, dropRow: Int): Pair<List<Int>, IntArray>? {
            if (totalSize <= 1 || draggedIndices.isEmpty()) return null
            val moved = draggedIndices.filter { it in 0 until totalSize }.distinct().sorted()
            if (moved.isEmpty()) return null
            val movedSet = moved.toSet()
            val clampedDrop = dropRow.coerceIn(0, totalSize)
            val remaining = (0 until totalSize).filter { it !in movedSet }
            val adjustedInsert = (clampedDrop - moved.count { it < clampedDrop }).coerceIn(0, remaining.size)

            val newOrder = ArrayList<Int>(totalSize)
            newOrder.addAll(remaining.subList(0, adjustedInsert))
            newOrder.addAll(moved)
            newOrder.addAll(remaining.subList(adjustedInsert, remaining.size))

            if (newOrder == (0 until totalSize).toList()) return null
            val newSelection = (adjustedInsert until adjustedInsert + moved.size).toList().toIntArray()
            return newOrder to newSelection
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

    // [v0.4.3] 시나리오 변수 탭 모델: [출처(Source, 읽기 전용)] | [Key] | [Value] | [복사]
    private val scenarioVarsModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("global.scenarioVars.col.source"),
            I18nManager.t("global.col.key"),
            I18nManager.t("global.col.value"),
            I18nManager.t("vars.col.action")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = (column == 1 || column == 2)
    }
    private val scenarioVarRows: MutableList<ScenarioVarRow> = ScenarioVariableSyncEngine.buildUnifiedRows(
        scenario = currentScenario,
        runtimeContext = runtimeContext,
        staticSourceLabel = I18nManager.t("global.scenarioVars.sourceStatic")
    )
    private val initialScenarioVarRowsSnapshot: List<ScenarioVarRow> = scenarioVarRows.map { it.deepCopy() }
    private val deletedExtractorRefs: MutableSet<Pair<Int, Int>> = linkedSetOf()
    private var isUpdatingScenarioVarsTable = false
    private var replaceReferencesOnRename = false

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
        populateScenarioVarsModel()
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
        variablesTable = createScenarioVarsTable()
        projectGlobalsTable = createProjectGlobalsTable()
        secretsTable = createEditableTable(secretsModel)
        dynamicTable = createDynamicVariablesTable()

        val scenarioDisplay = ScenarioFileRepository.formatDisplayPath(currentScenarioPath)
            .ifBlank { currentScenario.name }

        // 1) 공통 헤더 (0)
        tabbedPane.addTab(
            I18nManager.t("global.tab.headers"),
            buildCommonHeadersTab(scenarioDisplay)
        )
        // 2) 프로젝트 전역 변수 (1)
        tabbedPane.addTab(
            I18nManager.t("global.tab.projectGlobals"),
            buildProjectGlobalsTab()
        )
        // 3) 시나리오 변수 (2) — 정적 시나리오 변수 + 스텝 응답 변수 추출 통합 양방향 현행화 탭
        tabbedPane.addTab(
            I18nManager.t("global.tab.vars"),
            buildScenarioVarsTab(scenarioDisplay)
        )
        // 4) 로컬 시크릿 (3)
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
        // 5) 내장 동적 변수·헤더 (4)
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
        installRowReorderSupport(headersTable, btnBar) { newOrder, newSelection ->
            reorderDefaultTableModel(commonHeadersModel, newOrder)
            restoreTableSelection(headersTable, newSelection)
        }
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    // [v0.4.3] 시나리오 변수 통합 테이블([출처] | [Key] | [Value] | [복사]), 우클릭/버튼 {{Key}} 복사 및 양방향 현행화 로직
    private fun createScenarioVarsTable(): JBTable {
        val table = JBTable(scenarioVarsModel).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 25
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                preferredWidth = 240
                minWidth = 140
                cellRenderer = ScenarioVarSourceRenderer()
            }
            columnModel.getColumn(1).preferredWidth = 190
            columnModel.getColumn(2).preferredWidth = 260
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
                    if (row !in scenarioVarRows.indices) return
                    // [복사] 셀 클릭 또는 읽기 전용 [출처] 셀 더블클릭 시 {{Key}} 즉시 복사
                    if (col == 3 || (col == 0 && e.clickCount == 2)) {
                        copyScenarioVarTokenAt(row)
                    }
                }

                override fun mousePressed(e: MouseEvent) = maybeShowCopyMenu(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowCopyMenu(e)

                private fun maybeShowCopyMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val row = rowAtPoint(e.point)
                    if (row !in scenarioVarRows.indices) return
                    setRowSelectionInterval(row, row)
                    val key = scenarioVarRows[row].key.trim()
                    val menu = JPopupMenu()
                    menu.add(JMenuItem(I18nManager.t("global.scenarioVars.copyMenu")).apply {
                        isEnabled = key.isNotEmpty()
                        addActionListener { copyScenarioVarTokenAt(row) }
                    })
                    menu.show(this@apply, e.x, e.y)
                }
            })
        }

        scenarioVarsModel.addTableModelListener { e ->
            if (isUpdatingScenarioVarsTable || e.type != TableModelEvent.UPDATE) return@addTableModelListener
            val row = e.firstRow
            if (row !in scenarioVarRows.indices) return@addTableModelListener
            val meta = scenarioVarRows[row]

            if (e.column == 1) {
                val prevKey = meta.key
                val newKey = (scenarioVarsModel.getValueAt(row, 1)?.toString() ?: "").trim()
                if (newKey.startsWith("$")) {
                    Messages.showWarningDialog(
                        project,
                        I18nManager.t("global.projectGlobals.reservedKeyMsg"),
                        I18nManager.t("global.projectGlobals.dupKeyTitle")
                    )
                    restoreScenarioVarKeyCell(row, prevKey)
                    return@addTableModelListener
                }
                if (newKey.isNotEmpty()) {
                    val duplicate = scenarioVarRows.withIndex().any { (idx, other) ->
                        idx != row && other.key.trim().equals(newKey, ignoreCase = true)
                    }
                    if (duplicate) {
                        Messages.showWarningDialog(
                            project,
                            I18nManager.t("global.scenarioVars.dupKeyMsg", newKey),
                            I18nManager.t("global.projectGlobals.dupKeyTitle")
                        )
                        restoreScenarioVarKeyCell(row, prevKey)
                        return@addTableModelListener
                    }
                }
                meta.key = newKey
                if (newKey != scenarioVarsModel.getValueAt(row, 1)?.toString()) {
                    restoreScenarioVarKeyCell(row, newKey)
                }
            } else if (e.column == 2) {
                meta.value = scenarioVarsModel.getValueAt(row, 2)?.toString() ?: ""
            }
        }

        return table
    }

    private fun restoreScenarioVarKeyCell(row: Int, value: String) {
        isUpdatingScenarioVarsTable = true
        try {
            scenarioVarsModel.setValueAt(value, row, 1)
        } finally {
            isUpdatingScenarioVarsTable = false
        }
    }

    private fun copyScenarioVarTokenAt(row: Int) {
        stopEditing(variablesTable)
        if (row !in scenarioVarRows.indices) return
        val key = scenarioVarRows[row].key.trim()
        if (key.isNotEmpty()) {
            copyTokenWithFeedback("{{$key}}")
        }
    }

    private fun populateScenarioVarsModel() {
        isUpdatingScenarioVarsTable = true
        try {
            scenarioVarsModel.rowCount = 0
            val copyLabel = I18nManager.t("vars.copyBtn")
            for (row in scenarioVarRows) {
                scenarioVarsModel.addRow(arrayOf<Any>(row.sourceLabel, row.key, row.value, copyLabel))
            }
        } finally {
            isUpdatingScenarioVarsTable = false
        }
    }

    private fun buildScenarioVarsTab(scenarioDisplay: String): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val bulkImportBtn = JButton(I18nManager.t("bulk.btn")).apply {
            addActionListener { openBulkImportForScenarioVars() }
        }
        val banner = buildScopeBanner(
            scopeText = I18nManager.t("global.scope.scenario", scenarioDisplay),
            scopeColor = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6)),
            hintHtml = I18nManager.t("global.tab.vars.hint"),
            rightAction = bulkImportBtn
        )
        panel.add(banner, BorderLayout.NORTH)
        panel.add(JBScrollPane(variablesTable), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("global.add")).apply {
            addActionListener {
                stopEditing(variablesTable)
                val staticLabel = I18nManager.t("global.scenarioVars.sourceStatic")
                val newRow = ScenarioVarRow(
                    origin = ScenarioVarOrigin.SCENARIO_STATIC,
                    originalKey = "",
                    key = "",
                    value = "",
                    sourceLabel = staticLabel,
                    initialValue = ""
                )
                scenarioVarRows.add(newRow)
                isUpdatingScenarioVarsTable = true
                try {
                    scenarioVarsModel.addRow(arrayOf<Any>(staticLabel, "", "", I18nManager.t("vars.copyBtn")))
                } finally {
                    isUpdatingScenarioVarsTable = false
                }
                val rowIdx = scenarioVarsModel.rowCount - 1
                variablesTable.setRowSelectionInterval(rowIdx, rowIdx)
                variablesTable.editCellAt(rowIdx, 1)
            }
        }
        val copyVarBtn = JButton(I18nManager.t("global.scenarioVars.copyBtn")).apply {
            addActionListener {
                val row = variablesTable.selectedRow
                if (row in scenarioVarRows.indices) {
                    copyScenarioVarTokenAt(row)
                }
            }
        }
        val removeBtn = JButton(I18nManager.t("global.remove")).apply {
            addActionListener {
                stopEditing(variablesTable)
                val selected = variablesTable.selectedRows.filter { it in scenarioVarRows.indices }
                if (selected.isEmpty()) return@addActionListener

                val extractorCount = selected.count { scenarioVarRows[it].origin == ScenarioVarOrigin.STEP_EXTRACTOR }
                if (extractorCount > 0) {
                    val confirm = Messages.showYesNoDialog(
                        project,
                        I18nManager.t("global.scenarioVars.deleteExtractorMsg", extractorCount),
                        I18nManager.t("global.scenarioVars.deleteExtractorTitle"),
                        Messages.getWarningIcon()
                    )
                    if (confirm != Messages.YES) return@addActionListener
                }

                isUpdatingScenarioVarsTable = true
                try {
                    for (idx in selected.sortedDescending()) {
                        val removed = scenarioVarRows.removeAt(idx)
                        if (removed.origin == ScenarioVarOrigin.STEP_EXTRACTOR) {
                            deletedExtractorRefs.add(removed.stepIndex to removed.extractorIndex)
                        }
                        scenarioVarsModel.removeRow(idx)
                    }
                } finally {
                    isUpdatingScenarioVarsTable = false
                }
            }
        }
        btnBar.add(addBtn)
        btnBar.add(removeBtn)
        btnBar.add(copyVarBtn)
        // [v0.4.4] 시나리오 변수 테이블 행 순서 변경 시 scenarioVarRows((stepIndex, extractorIndex) 포함)를 함께 재배치하여 스텝 실행 순서 및 양방향 동기화 무결성 보존
        installRowReorderSupport(variablesTable, btnBar) { newOrder, newSelection ->
            isUpdatingScenarioVarsTable = true
            try {
                val reorderedRows = newOrder.map { scenarioVarRows[it] }
                scenarioVarRows.clear()
                scenarioVarRows.addAll(reorderedRows)
                reorderDefaultTableModel(scenarioVarsModel, newOrder)
            } finally {
                isUpdatingScenarioVarsTable = false
            }
            restoreTableSelection(variablesTable, newSelection)
        }
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    // [v0.4.3] 시나리오 변수 JSON 일괄 입력 시 일반 시나리오 변수만 갱신하고 스텝 추출 변수 행은 덮어쓰지 않고 보존
    private fun openBulkImportForScenarioVars() {
        stopEditing(variablesTable)
        val dialog = BulkJsonImportDialog(
            project = project,
            targetName = I18nManager.t("global.tab.vars"),
            globalVariablesProvider = { getProjectGlobalVariables() },
            scenarioProvider = { currentScenario },
            isImportingIntoGlobalVars = false
        )
        if (!dialog.showAndGet()) return

        val imported = dialog.getImportedItems()
        val existingStaticMap = linkedMapOf<String, String>()
        val preservedExtractors = mutableListOf<ScenarioVarRow>()
        val extractorKeysLower = mutableSetOf<String>()

        for (row in scenarioVarRows) {
            if (row.origin == ScenarioVarOrigin.STEP_EXTRACTOR) {
                preservedExtractors.add(row)
                if (row.key.trim().isNotEmpty()) extractorKeysLower.add(row.key.trim().lowercase())
            } else if (row.key.trim().isNotEmpty()) {
                existingStaticMap[row.key.trim()] = row.value
            }
        }

        // Preserve extractor rows: do not duplicate keys already owned by step extractors
        val filteredImport = imported.filter { it.key.trim().lowercase() !in extractorKeysLower }
        val mergedStatic = BulkJsonVariableParser.mergeIntoMap(existingStaticMap, filteredImport, dialog.getSelectedMergeMode())
        val staticLabel = I18nManager.t("global.scenarioVars.sourceStatic")

        scenarioVarRows.clear()
        for ((k, v) in mergedStatic) {
            scenarioVarRows.add(
                ScenarioVarRow(
                    origin = ScenarioVarOrigin.SCENARIO_STATIC,
                    originalKey = k,
                    key = k,
                    value = v,
                    sourceLabel = staticLabel,
                    initialValue = v
                )
            )
        }
        scenarioVarRows.addAll(preservedExtractors)
        populateScenarioVarsModel()
        Messages.showInfoMessage(project, I18nManager.t("bulk.success", filteredImport.size), I18nManager.t("global.tab.vars"))
    }

    private inner class ScenarioVarSourceRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val meta = scenarioVarRows.getOrNull(row)
            if (meta?.origin == ScenarioVarOrigin.STEP_EXTRACTOR) {
                font = font.deriveFont(Font.BOLD)
                if (!isSelected) {
                    foreground = JBColor(Color(0x1B, 0x7F, 0x3A), Color(0x6C, 0xD9, 0x7E))
                }
            } else {
                font = font.deriveFont(Font.PLAIN)
                if (!isSelected) {
                    foreground = JBColor.GRAY
                }
            }
            toolTipText = value?.toString()
            return this
        }
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
        // [v0.4.4] 프로젝트 전역 변수 순서 변경 시 previousGlobalKeys 중복 검증 추적 리스트도 동일 순서로 동기화
        installRowReorderSupport(projectGlobalsTable, btnBar) { newOrder, newSelection ->
            isUpdatingGlobalsTable = true
            try {
                val reorderedKeys = newOrder.map { idx -> previousGlobalKeys.getOrElse(idx) { "" } }
                previousGlobalKeys.clear()
                previousGlobalKeys.addAll(reorderedKeys)
                reorderDefaultTableModel(projectGlobalsModel, newOrder)
            } finally {
                isUpdatingGlobalsTable = false
            }
            restoreTableSelection(projectGlobalsTable, newSelection)
        }
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

    // [v0.4.3] 저장 시 편집 중인 셀 강제 커밋 후 변수명 변경에 따른 참조({{구변수명}} -> {{신변수명}}) 일괄 치환 확인
    override fun doOKAction() {
        stopEditing(headersTable)
        stopEditing(variablesTable)
        stopEditing(projectGlobalsTable)
        stopEditing(secretsTable)

        val renamedPairs = scenarioVarRows.mapNotNull { row ->
            val oldKey = row.originalKey.trim()
            val newKey = row.key.trim()
            if (oldKey.isNotEmpty() && newKey.isNotEmpty() && oldKey != newKey) oldKey to newKey else null
        }.distinct()

        replaceReferencesOnRename = false
        if (renamedPairs.isNotEmpty()) {
            val totalRefs = renamedPairs.sumOf { (oldKey, _) ->
                ScenarioVariableSyncEngine.countVariableReferences(currentScenario, oldKey)
            }
            if (totalRefs > 0) {
                val summary = renamedPairs.joinToString(", ") { (o, n) -> "$o → $n" }
                val choice = Messages.showYesNoCancelDialog(
                    project,
                    I18nManager.t("global.scenarioVars.renameRefMsg", summary, totalRefs),
                    I18nManager.t("global.scenarioVars.renameRefTitle"),
                    I18nManager.t("global.scenarioVars.renameRefYes"),
                    I18nManager.t("global.scenarioVars.renameRefNo"),
                    I18nManager.t("global.cancel"),
                    Messages.getQuestionIcon()
                )
                when (choice) {
                    Messages.YES -> replaceReferencesOnRename = true
                    Messages.NO -> replaceReferencesOnRename = false
                    else -> return // Keep dialog open
                }
            }
        }

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

    fun getGlobalVariables(): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        for (row in scenarioVarRows) {
            if (row.origin == ScenarioVarOrigin.SCENARIO_STATIC && row.key.trim().isNotEmpty()) {
                result[row.key.trim()] = row.value
            }
        }
        return result
    }

    fun getEditedScenarioVarRows(): List<ScenarioVarRow> = scenarioVarRows.map { it.deepCopy() }

    fun getDeletedExtractorRefs(): Set<Pair<Int, Int>> = deletedExtractorRefs.toSet()

    fun shouldReplaceReferencesOnRename(): Boolean = replaceReferencesOnRename

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
        val headersChanged = getCommonHeaderItems() != normalizedInitialHeaders
        val varsChanged = scenarioVarRows != initialScenarioVarRowsSnapshot || deletedExtractorRefs.isNotEmpty()
        return headersChanged || varsChanged
    }

    fun isProjectGlobalsChanged(): Boolean {
        val normalizedInitial = initialProjectGlobals
            .filter { it.key.trim().isNotEmpty() }
            .map { it.copy(key = it.key.trim()) }
        return getProjectGlobalVariables() != normalizedInitial
    }

    // [v0.4.4] LinkedHashMap 순수 순서 변경(키/값 동일)도 변경으로 감지하여 .maekom/secrets.local.json에 순서 영속 저장
    fun isSecretsChanged(): Boolean {
        val normalizedInitial = LinkedHashMap<String, String>().apply {
            initialSecrets.forEach { (k, v) -> if (k.trim().isNotEmpty()) put(k.trim(), v) }
        }
        return getSecrets().entries.toList() != normalizedInitial.entries.toList()
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
        installRowReorderSupport(table, btnBar) { newOrder, newSelection ->
            reorderDefaultTableModel(model, newOrder)
            restoreTableSelection(table, newSelection)
        }
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

    // [v0.4.4] 편집 가능한 4개 테이블에 ▲/▼ 버튼(활성 상태 자동 동기화) + DropMode.INSERT_ROWS 드래그 앤 드롭 공통 설치
    private fun installRowReorderSupport(
        table: JBTable,
        btnBar: JPanel,
        applyPermutation: (newOrder: List<Int>, newSelection: IntArray) -> Unit
    ) {
        val moveUpBtn = JButton("▲").apply {
            margin = JBUI.insets(2, 6)
            toolTipText = I18nManager.t("global.reorder.upTooltip")
            isEnabled = false
        }
        val moveDownBtn = JButton("▼").apply {
            margin = JBUI.insets(2, 6)
            toolTipText = I18nManager.t("global.reorder.downTooltip")
            isEnabled = false
        }

        val syncButtons = {
            val selected = table.selectedRows
            val count = table.rowCount
            moveUpBtn.isEnabled = selected.isNotEmpty() && selected.min() > 0
            moveDownBtn.isEnabled = selected.isNotEmpty() && selected.max() < count - 1
        }

        moveUpBtn.addActionListener {
            stopEditing(table)
            val result = computeMoveStepOrder(table.rowCount, table.selectedRows, delta = -1) ?: return@addActionListener
            applyPermutation(result.first, result.second)
            syncButtons()
        }
        moveDownBtn.addActionListener {
            stopEditing(table)
            val result = computeMoveStepOrder(table.rowCount, table.selectedRows, delta = 1) ?: return@addActionListener
            applyPermutation(result.first, result.second)
            syncButtons()
        }

        table.selectionModel.addListSelectionListener { syncButtons() }
        table.model.addTableModelListener { syncButtons() }

        table.dragEnabled = true
        table.dropMode = DropMode.INSERT_ROWS
        table.transferHandler = RowReorderTransferHandler(table) { newOrder, newSelection ->
            applyPermutation(newOrder, newSelection)
            syncButtons()
        }

        btnBar.add(moveUpBtn)
        btnBar.add(moveDownBtn)
    }

    private fun reorderDefaultTableModel(model: DefaultTableModel, newOrder: List<Int>) {
        val colCount = model.columnCount
        val snapshot = newOrder.map { rowIdx ->
            Array<Any?>(colCount) { colIdx -> model.getValueAt(rowIdx, colIdx) }
        }
        model.rowCount = 0
        for (rowData in snapshot) {
            model.addRow(rowData)
        }
    }

    private fun restoreTableSelection(table: JBTable, newSelection: IntArray) {
        table.clearSelection()
        val sorted = newSelection.filter { it in 0 until table.rowCount }.sorted()
        for (idx in sorted) {
            table.addRowSelectionInterval(idx, idx)
        }
        sorted.firstOrNull()?.let { first ->
            table.scrollRectToVisible(table.getCellRect(first, 0, true))
        }
    }

    private data class RowDragPayload(
        val sourceTable: JTable,
        val rows: IntArray
    )

    private inner class RowReorderTransferHandler(
        private val table: JBTable,
        private val onReorder: (newOrder: List<Int>, newSelection: IntArray) -> Unit
    ) : TransferHandler() {
        private val rowFlavor = DataFlavor(RowDragPayload::class.java, "MaekomTableRowReorder")

        override fun getSourceActions(c: JComponent?): Int = MOVE

        override fun createTransferable(c: JComponent?): Transferable? {
            if (table.isEditing) {
                table.cellEditor?.stopCellEditing()
            }
            val selected = table.selectedRows
            if (selected.isEmpty()) return null
            val payload = RowDragPayload(table, selected.copyOf())
            return object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(rowFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == rowFlavor
                override fun getTransferData(flavor: DataFlavor?): Any {
                    if (flavor != rowFlavor) throw UnsupportedFlavorException(flavor)
                    return payload
                }
            }
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(rowFlavor)) return false
            val dl = support.dropLocation as? JTable.DropLocation ?: return false
            if (!dl.isInsertRow) return false
            val payload = runCatching { support.transferable.getTransferData(rowFlavor) as? RowDragPayload }.getOrNull()
                ?: return false
            if (payload.sourceTable !== table) return false
            support.setShowDropLocation(true)
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            if (table.isEditing) {
                table.cellEditor?.stopCellEditing()
            }
            val dl = support.dropLocation as? JTable.DropLocation ?: return false
            val payload = runCatching { support.transferable.getTransferData(rowFlavor) as? RowDragPayload }.getOrNull()
                ?: return false
            val result = computeDropReorder(table.rowCount, payload.rows, dl.row) ?: return false
            onReorder(result.first, result.second)
            return true
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
