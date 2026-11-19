package com.maekom.apirunner.ui

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
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.CommonHeaderItem
import com.maekom.apirunner.storage.ScenarioFileRepository
import com.maekom.apirunner.storage.ScenarioStorageService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// [v0.1.3] 공통 헤더 Enabled/Secret 컬럼 확장 및 다른 시나리오의 공통 헤더 미리보기·병합/덮어쓰기 가져오기 기능 구현
// 변경 의도: 시나리오 간 공통 인증 헤더/시크릿을 매번 재입력하지 않고 미리보기 확인 후 안전하게 재사용

enum class HeaderImportMode {
    MERGE,
    OVERWRITE
}

/**
 * Dialog for editing:
 * 1. Common Headers (Enabled | Key | Value | Secret) + Import from Another Scenario (Merge / Overwrite)
 * 2. Scenario Global Variables (legacy per-scenario vars)
 * 3. Local Secrets (.maekom/secrets.local.json)
 */
// [v0.3.0] 폴더 상대 경로 기반 시나리오 구분(currentScenarioPath) 및 공통 헤더 가져오기 목록 포맷('폴더명 / 시나리오명') 지원
// 변경 의도: 서로 다른 폴더에 동일 이름의 시나리오가 공존할 때도 정확히 구분해 공통 헤더를 가져올 수 있도록 개선
class GlobalConfigDialog(
    private val project: Project,
    private val currentScenario: ApiScenario,
    private val storage: ScenarioStorageService,
    initialCommonHeaders: List<CommonHeaderItem>,
    initialVariables: Map<String, String>,
    initialSecrets: Map<String, String>,
    private val currentScenarioPath: String = currentScenario.name
) : DialogWrapper(project, true) {

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
    private val secretsModel = createKeyValueModel(initialSecrets)

    private lateinit var headersTable: JBTable
    private lateinit var variablesTable: JBTable
    private lateinit var secretsTable: JBTable

    init {
        title = I18nManager.t("global.title")
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        for (item in initialCommonHeaders) {
            commonHeadersModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret))
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabbedPane = JBTabbedPane()
        tabbedPane.preferredSize = Dimension(740, 440)

        headersTable = JBTable(commonHeadersModel).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                maxWidth = 56
                minWidth = 44
                preferredWidth = 50
            }
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).apply {
                preferredWidth = 300
                cellRenderer = MaskedValueCellRenderer(secretColumnIndex = 3)
            }
            columnModel.getColumn(3).apply {
                maxWidth = 68
                minWidth = 54
                preferredWidth = 60
            }
        }
        variablesTable = createEditableTable(variablesModel)
        secretsTable = createEditableTable(secretsModel)

        tabbedPane.addTab(
            I18nManager.t("global.tab.headers"),
            buildCommonHeadersTab()
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.vars"),
            buildTableTab(
                table = variablesTable,
                model = variablesModel,
                hint = I18nManager.t("global.tab.vars.hint"),
                bulkTargetLabel = I18nManager.t("global.tab.vars")
            )
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.secrets"),
            buildTableTab(
                table = secretsTable,
                model = secretsModel,
                hint = I18nManager.t("global.tab.secrets.hint"),
                bulkTargetLabel = I18nManager.t("global.tab.secrets")
            )
        )

        return tabbedPane
    }

    private fun buildCommonHeadersTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>${I18nManager.t("global.tab.headers.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        val importBtn = JButton(I18nManager.t("global.importHeaders.btn")).apply {
            addActionListener { openImportFromScenarioDialog() }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(importBtn, BorderLayout.EAST)

        panel.add(topBar, BorderLayout.NORTH)
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

    override fun doOKAction() {
        stopEditing(headersTable)
        stopEditing(variablesTable)
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

    fun getSecrets(): LinkedHashMap<String, String> = extractMap(secretsModel)

    private fun buildTableTab(
        table: JBTable,
        model: DefaultTableModel,
        hint: String,
        bulkTargetLabel: String
    ): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>$hint</html>").apply {
            foreground = JBColor.GRAY
        }
        val bulkImportBtn = JButton(I18nManager.t("bulk.btn")).apply {
            addActionListener { openBulkImportForKeyValueTable(table, model, bulkTargetLabel) }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(bulkImportBtn, BorderLayout.EAST)

        panel.add(topBar, BorderLayout.NORTH)
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

    // [v0.2.0] 시나리오 변수 / 로컬 시크릿 탭 JSON 일괄 입력 반영
    private fun openBulkImportForKeyValueTable(
        table: JBTable,
        model: DefaultTableModel,
        targetLabel: String
    ) {
        stopEditing(table)
        val dialog = BulkJsonImportDialog(
            project = project,
            targetName = targetLabel,
            globalVariablesProvider = { storage.loadGlobalVariables() },
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
            rowHeight = 24
            putClientProperty("terminateEditOnFocusLost", true)
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

    companion object {
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
