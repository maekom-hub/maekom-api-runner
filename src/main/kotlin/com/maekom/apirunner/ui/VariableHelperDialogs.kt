package com.maekom.apirunner.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.BulkJsonVariableParser
import com.maekom.apirunner.engine.JsonPathCandidate
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.GlobalVariableItem
import com.maekom.apirunner.storage.ScenarioStorageService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.text.JTextComponent

// [v0.1.3] {{$}} 변수 도움말 3탭 통합(전역 변수 CRUD·내장 동적 변수·추출 변수), 변수명 중복 검증기 연동, 우클릭 전역 변수 삽입 지원
// 변경 의도: 시나리오가 바뀌어도 유지되는 전역 변수를 한 창에서 관리하고, 변수명 충돌 시 추천 변수명을 자동 제안

data class ScenarioVariableItem(
    val name: String,
    val token: String,
    val isExtracted: Boolean,
    val currentValue: String?,
    val sourceLabel: String = "",
    val isSecret: Boolean = false
)

/**
 * Non-modal 3-tab Variables Hub Dialog:
 * - Tab 1: Project-wide Global Variables (.maekom/global-variables.json) with full CRUD + Secret masking + Duplicate Validation
 * - Tab 2: Built-in Dynamic Variables with live evaluated preview & Copy button
 * - Tab 3: Current Scenario Extracted Variables with source step & Copy button
 */
class VariableCheatSheetDialog(
    private val project: Project,
    private val storage: ScenarioStorageService,
    private val currentScenarioProvider: () -> ApiScenario,
    private val extractedVariablesProvider: () -> List<ScenarioVariableItem>,
    private val onGlobalVariablesChanged: () -> Unit
) : DialogWrapper(project, true) {

    private var globalItems: MutableList<GlobalVariableItem> = storage.loadGlobalVariables()
    private var isUpdatingGlobalTable = false

    // Tab 1: Global Variables Table ([활성] | [변수명] | [값] | [Secret 마스킹] | [복사])
    private val globalTableModel = object : DefaultTableModel(
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

        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 || column == 3
    }
    private val globalTable = JBTable(globalTableModel)

    // Tab 2: Built-in Dynamic Variables Table
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
    private val dynamicTable = JBTable(dynamicTableModel)

    // Tab 3: Extracted Variables Table
    private val extractedTableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("vars.col.token"),
            I18nManager.t("vars.extracted.col.source"),
            I18nManager.t("vars.col.preview"),
            I18nManager.t("vars.col.action")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val extractedTable = JBTable(extractedTableModel)

    private val feedbackLabel = JBLabel(" ")

    init {
        title = I18nManager.t("vars.dialog.title")
        isModal = false
        setOKButtonText(I18nManager.t("vars.dialog.close"))
        init()
        reloadAllTabs()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(860, 480)
        root.border = JBUI.Borders.empty(4)

        val tabs = JBTabbedPane()
        tabs.addTab(I18nManager.t("vars.tab.global"), buildGlobalVariablesTab())
        tabs.addTab(I18nManager.t("vars.tab.dynamic"), buildDynamicVariablesTab())
        tabs.addTab(I18nManager.t("vars.tab.extracted"), buildExtractedVariablesTab())

        root.add(tabs, BorderLayout.CENTER)

        feedbackLabel.apply {
            font = font.deriveFont(Font.BOLD)
            foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
        root.add(feedbackLabel, BorderLayout.SOUTH)

        return root
    }

    private fun buildGlobalVariablesTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        // [v0.2.0] 프로젝트 전역 변수 탭에 JSON 일괄 입력(Bulk Import) 버튼 추가
        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>${I18nManager.t("vars.global.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        val bulkImportBtn = JButton(I18nManager.t("bulk.btn")).apply {
            addActionListener { bulkImportGlobalVariables() }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(bulkImportBtn, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        globalTable.apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply { maxWidth = 54; minWidth = 44; preferredWidth = 48 }
            columnModel.getColumn(1).apply {
                preferredWidth = 180
                cellRenderer = MonospaceTokenRenderer()
            }
            columnModel.getColumn(2).apply {
                preferredWidth = 280
                cellRenderer = MaskedValueCellRenderer(secretColumnIndex = 3)
            }
            columnModel.getColumn(3).apply { maxWidth = 96; minWidth = 76; preferredWidth = 86 }
            columnModel.getColumn(4).apply {
                maxWidth = 76
                minWidth = 62
                preferredWidth = 68
                cellRenderer = CopyButtonCellRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0 || row !in globalItems.indices) return
                    if (col == 4) {
                        copyTokenToClipboard("{{${globalItems[row].key}}}")
                    } else if (e.clickCount == 2 && col != 0 && col != 3) {
                        editSelectedGlobalVariable()
                    }
                }
            })
        }

        globalTableModel.addTableModelListener { e ->
            if (isUpdatingGlobalTable || e.type != TableModelEvent.UPDATE) return@addTableModelListener
            val row = e.firstRow
            if (row !in globalItems.indices) return@addTableModelListener
            val enabled = (globalTableModel.getValueAt(row, 0) as? Boolean) ?: true
            val secret = (globalTableModel.getValueAt(row, 3) as? Boolean) ?: false
            globalItems[row].enabled = enabled
            globalItems[row].secret = secret
            persistGlobalVariables()
            globalTable.repaint()
        }

        panel.add(JBScrollPane(globalTable), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("vars.global.add")).apply {
            addActionListener { addGlobalVariable() }
        }
        val editBtn = JButton(I18nManager.t("vars.global.edit")).apply {
            addActionListener { editSelectedGlobalVariable() }
        }
        val deleteBtn = JButton(I18nManager.t("vars.global.delete")).apply {
            addActionListener { deleteSelectedGlobalVariables() }
        }
        btnBar.add(addBtn)
        btnBar.add(editBtn)
        btnBar.add(deleteBtn)
        panel.add(btnBar, BorderLayout.SOUTH)

        return panel
    }

    // [v0.2.0] 프로젝트 전역 변수 JSON 일괄 입력 및 .maekom/global-variables.json 영속 저장
    private fun bulkImportGlobalVariables() {
        val dialog = BulkJsonImportDialog(
            project = project,
            targetName = I18nManager.t("vars.tab.global"),
            globalVariablesProvider = { globalItems },
            scenarioProvider = currentScenarioProvider,
            isImportingIntoGlobalVars = true
        )
        if (!dialog.showAndGet()) return

        val imported = dialog.getImportedItems()
        globalItems = BulkJsonVariableParser.mergeItems(globalItems, imported, dialog.getSelectedMergeMode())
        persistGlobalVariables()
        reloadGlobalRows()
        feedbackLabel.text = I18nManager.t("bulk.success", imported.size)
    }

    private fun buildDynamicVariablesTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>${I18nManager.t("vars.dialog.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        val refreshBtn = JButton(I18nManager.t("vars.dialog.refresh")).apply {
            addActionListener { reloadDynamicRows() }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(refreshBtn, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        dynamicTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                preferredWidth = 175
                cellRenderer = MonospaceTokenRenderer()
            }
            columnModel.getColumn(1).preferredWidth = 280
            columnModel.getColumn(2).apply {
                preferredWidth = 240
                cellRenderer = MonospacePreviewRenderer()
            }
            columnModel.getColumn(3).apply {
                maxWidth = 76
                preferredWidth = 68
                cellRenderer = CopyButtonCellRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    if (col == 3 || e.clickCount == 2) {
                        val token = dynamicTableModel.getValueAt(row, 0)?.toString() ?: return
                        copyTokenToClipboard(token)
                    }
                }
            })
        }

        panel.add(JBScrollPane(dynamicTable), BorderLayout.CENTER)
        return panel
    }

    private fun buildExtractedVariablesTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>${I18nManager.t("vars.dialog.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        val refreshBtn = JButton(I18nManager.t("vars.dialog.refresh")).apply {
            addActionListener { reloadExtractedRows() }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(refreshBtn, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        extractedTable.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                preferredWidth = 190
                cellRenderer = MonospaceTokenRenderer()
            }
            columnModel.getColumn(1).preferredWidth = 140
            columnModel.getColumn(2).apply {
                preferredWidth = 320
                cellRenderer = MonospacePreviewRenderer()
            }
            columnModel.getColumn(3).apply {
                maxWidth = 76
                preferredWidth = 68
                cellRenderer = CopyButtonCellRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    if (col == 3 || e.clickCount == 2) {
                        val token = extractedTableModel.getValueAt(row, 0)?.toString() ?: return
                        copyTokenToClipboard(token)
                    }
                }
            })
        }

        panel.add(JBScrollPane(extractedTable), BorderLayout.CENTER)
        return panel
    }

    private fun addGlobalVariable() {
        val editDialog = GlobalVariableEditDialog(
            project = project,
            existing = null,
            globalVariablesProvider = { globalItems },
            scenarioProvider = currentScenarioProvider
        )
        if (editDialog.showAndGet()) {
            globalItems.add(editDialog.toGlobalVariableItem())
            persistGlobalVariables()
            reloadGlobalRows()
        }
    }

    private fun editSelectedGlobalVariable() {
        val row = globalTable.selectedRow
        if (row !in globalItems.indices) return
        val current = globalItems[row]
        val editDialog = GlobalVariableEditDialog(
            project = project,
            existing = current,
            globalVariablesProvider = { globalItems },
            scenarioProvider = currentScenarioProvider
        )
        if (editDialog.showAndGet()) {
            globalItems[row] = editDialog.toGlobalVariableItem()
            persistGlobalVariables()
            reloadGlobalRows()
        }
    }

    private fun deleteSelectedGlobalVariables() {
        val rows = globalTable.selectedRows
        if (rows.isEmpty()) return
        val confirm = Messages.showYesNoDialog(
            project,
            I18nManager.t("vars.global.deleteConfirm", rows.size),
            I18nManager.t("vars.tab.global"),
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return
        for (i in rows.indices.reversed()) {
            if (rows[i] in globalItems.indices) {
                globalItems.removeAt(rows[i])
            }
        }
        persistGlobalVariables()
        reloadGlobalRows()
    }

    private fun persistGlobalVariables() {
        storage.saveGlobalVariables(globalItems)
        onGlobalVariablesChanged()
    }

    private fun reloadAllTabs() {
        reloadGlobalRows()
        reloadDynamicRows()
        reloadExtractedRows()
    }

    private fun reloadGlobalRows() {
        isUpdatingGlobalTable = true
        try {
            globalTableModel.rowCount = 0
            val copyLabel = I18nManager.t("vars.copyBtn")
            for (item in globalItems) {
                globalTableModel.addRow(arrayOf<Any>(item.enabled, item.key, item.value, item.secret, copyLabel))
            }
        } finally {
            isUpdatingGlobalTable = false
        }
    }

    private fun reloadDynamicRows() {
        dynamicTableModel.rowCount = 0
        val copyLabel = I18nManager.t("vars.copyBtn")
        for (spec in VariableResolver.DYNAMIC_VARIABLES) {
            dynamicTableModel.addRow(
                arrayOf<Any>(
                    spec.token,
                    I18nManager.t(spec.descKey),
                    spec.evaluator.invoke(),
                    copyLabel
                )
            )
        }
    }

    private fun reloadExtractedRows() {
        extractedTableModel.rowCount = 0
        val copyLabel = I18nManager.t("vars.copyBtn")
        for (item in extractedVariablesProvider()) {
            val preview = item.currentValue?.takeIf { it.isNotEmpty() } ?: I18nManager.t("vars.extracted.pending")
            extractedTableModel.addRow(arrayOf<Any>(item.token, item.sourceLabel, preview, copyLabel))
        }
    }

    private fun copyTokenToClipboard(token: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(token))
        feedbackLabel.text = I18nManager.t("vars.copiedMsg", token)
    }

    private class MonospaceTokenRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            font = Font(Font.MONOSPACED, Font.BOLD, 12)
            if (!isSelected) {
                foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
            }
            return this
        }
    }

    private class MonospacePreviewRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            return this
        }
    }

    private class CopyButtonCellRenderer : DefaultTableCellRenderer() {
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
 * Add / Edit dialog for a single GlobalVariableItem with VariableNameValidator duplicate check.
 */
class GlobalVariableEditDialog(
    private val project: Project,
    private val existing: GlobalVariableItem?,
    private val globalVariablesProvider: () -> List<GlobalVariableItem>,
    private val scenarioProvider: () -> ApiScenario
) : DialogWrapper(project, true) {

    private val keyField = JBTextField(existing?.key.orEmpty(), 26)
    private val valueField = JBTextField(existing?.value.orEmpty(), 32)
    private val enabledCheck = JBCheckBox(I18nManager.t("vars.global.editDialog.enabled"), existing?.enabled ?: true)
    private val secretCheck = JBCheckBox(I18nManager.t("vars.global.editDialog.secret"), existing?.secret ?: false)

    init {
        title = if (existing == null) {
            I18nManager.t("vars.global.editDialog.addTitle")
        } else {
            I18nManager.t("vars.global.editDialog.editTitle")
        }
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(6)
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("vars.global.editDialog.key")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(keyField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("vars.global.editDialog.value")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(valueField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        panel.add(enabledCheck, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
        panel.add(secretCheck, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (keyField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.t("vars.global.editDialog.emptyKey"), keyField)
        }
        return null
    }

    override fun doOKAction() {
        val candidate = keyField.text.trim()
        val validation = VariableNameValidator.validate(
            candidateName = candidate,
            globalVariables = globalVariablesProvider(),
            scenario = scenarioProvider(),
            ignoreGlobalKey = existing?.key?.trim()
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
                keyField.text = validation.recommendedName
            } else {
                keyField.requestFocusInWindow()
                return
            }
        }
        super.doOKAction()
    }

    fun toGlobalVariableItem(): GlobalVariableItem = GlobalVariableItem(
        enabled = enabledCheck.isSelected,
        key = keyField.text.trim(),
        value = valueField.text,
        secret = secretCheck.isSelected
    )
}

/**
 * Guide popup dialog displaying JsonPath examples, next-step variable chaining syntax,
 * and live-evaluated built-in dynamic variables cheat sheet.
 */
class ExtractGuideDialog(project: Project) : DialogWrapper(project, true) {

    init {
        title = I18nManager.t("guide.title")
        isModal = false
        setOKButtonText(I18nManager.t("guide.close"))
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val dynamicRowsHtml = buildString {
            append("<table border='1' cellpadding='4' cellspacing='0' style='border-collapse:collapse; width:100%;'>")
            append("<tr><th>${I18nManager.t("vars.col.token")}</th><th>${I18nManager.t("vars.col.desc")}</th><th>${I18nManager.t("vars.col.preview")}</th></tr>")
            for (spec in VariableResolver.DYNAMIC_VARIABLES) {
                val sample = escapeHtml(spec.evaluator.invoke())
                append("<tr><td><code>${escapeHtml(spec.token)}</code></td><td>${escapeHtml(I18nManager.t(spec.descKey))}</td><td><code>$sample</code></td></tr>")
            }
            append("</table>")
        }

        val fullHtml = I18nManager.t("guide.content.html", dynamicRowsHtml)
        val editorPane = JEditorPane("text/html", fullHtml).apply {
            isEditable = false
            caretPosition = 0
            border = JBUI.Borders.empty(6)
        }

        return JBScrollPane(editorPane).apply {
            preferredSize = Dimension(740, 480)
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Confirmation dialog for creating an ExtractRule from a selected JSON response field,
 * integrated with [VariableNameValidator] duplicate check & parent-key recommendation.
 */
class AddExtractRuleDialog(
    private val project: Project,
    candidates: List<JsonPathCandidate>,
    initialCandidate: JsonPathCandidate?,
    private val globalVariablesProvider: () -> List<GlobalVariableItem>,
    private val scenarioProvider: () -> ApiScenario
) : DialogWrapper(project, true) {

    private val candidateCombo = ComboBox(DefaultComboBoxModel(candidates.toTypedArray()))
    private val jsonPathField = JBTextField(32)
    private val varNameField = JBTextField(24)
    private val previewLabel = JBLabel("")
    private val requiredCheckBox = JBCheckBox(I18nManager.t("extractDialog.required"), true)
    private val hasCandidates = candidates.isNotEmpty()

    init {
        title = I18nManager.t("extractDialog.title")
        setOKButtonText(I18nManager.t("extractDialog.add"))
        setCancelButtonText(I18nManager.t("extractDialog.cancel"))

        val selected = initialCandidate
            ?: candidates.firstOrNull { it.isLeaf }
            ?: candidates.firstOrNull()
        if (selected != null) {
            candidateCombo.selectedItem = selected
            applyCandidate(selected)
        }

        candidateCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val item = value as? JsonPathCandidate
                val text = if (item != null) {
                    "${item.jsonPath}  =  ${item.valuePreview}"
                } else {
                    ""
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        candidateCombo.addActionListener {
            val chosen = candidateCombo.selectedItem as? JsonPathCandidate ?: return@addActionListener
            applyCandidate(chosen)
        }

        init()
    }

    private fun applyCandidate(candidate: JsonPathCandidate) {
        jsonPathField.text = candidate.jsonPath
        varNameField.text = candidate.suggestedVarName
        previewLabel.text = candidate.valuePreview
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(6)
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0
        if (hasCandidates) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            panel.add(JBLabel(I18nManager.t("extractDialog.fieldSelect")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            candidateCombo.preferredSize = Dimension(420, 28)
            panel.add(candidateCombo, gbc)
            row++
        }

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("extractDialog.jsonPath")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(jsonPathField, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("extractDialog.varName")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(varNameField, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("extractDialog.preview")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        previewLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
        panel.add(previewLabel, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(requiredCheckBox, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (varNameField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.t("extractDialog.invalidInput"), varNameField)
        }
        if (jsonPathField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.t("extractDialog.invalidInput"), jsonPathField)
        }
        return null
    }

    override fun doOKAction() {
        val candidateName = varNameField.text.trim()
        val validation = VariableNameValidator.validate(
            candidateName = candidateName,
            globalVariables = globalVariablesProvider(),
            scenario = scenarioProvider(),
            jsonPathForSuggestion = jsonPathField.text.trim()
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
                varNameField.text = validation.recommendedName
            } else {
                varNameField.requestFocusInWindow()
                return
            }
        }
        super.doOKAction()
    }

    fun toExtractRule(): ExtractRule = ExtractRule(
        varName = varNameField.text.trim(),
        jsonPath = jsonPathField.text.trim(),
        required = requiredCheckBox.isSelected
    )
}

/**
 * Installs right-click variable insertion context menus on JTextComponents and JBTable Value columns,
 * exposing Built-in Dynamic Variables, Current Scenario Extracted Variables, and Project Global Variables.
 */
object VariableContextMenuSupport {

    fun installOnTextComponent(
        textComponent: JTextComponent,
        scenarioVariablesProvider: () -> List<ScenarioVariableItem>
    ) {
        textComponent.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)

            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger || !textComponent.isEnabled) return
                textComponent.requestFocusInWindow()
                val popup = buildTextPopupMenu(textComponent, scenarioVariablesProvider)
                popup.show(textComponent, e.x, e.y)
            }
        })
    }

    fun installOnTableValueColumn(
        table: JBTable,
        valueColumnIndex: Int,
        scenarioVariablesProvider: () -> List<ScenarioVariableItem>,
        onValueUpdated: () -> Unit
    ) {
        val editorField = JBTextField()
        installOnTextComponent(editorField, scenarioVariablesProvider)
        table.columnModel.getColumn(valueColumnIndex).cellEditor = DefaultCellEditor(editorField)

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)

            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger || !table.isEnabled) return
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col != valueColumnIndex) return

                table.setRowSelectionInterval(row, row)
                val popup = JPopupMenu()
                popup.add(buildInsertVariableSubmenu(scenarioVariablesProvider) { token ->
                    if (table.isEditing) {
                        table.cellEditor?.stopCellEditing()
                    }
                    val current = table.model.getValueAt(row, valueColumnIndex)?.toString().orEmpty()
                    table.model.setValueAt(current + token, row, valueColumnIndex)
                    onValueUpdated()
                })
                popup.show(table, e.x, e.y)
            }
        })
    }

    private fun buildTextPopupMenu(
        textComponent: JTextComponent,
        scenarioVariablesProvider: () -> List<ScenarioVariableItem>
    ): JPopupMenu {
        val popup = JPopupMenu()
        popup.add(buildInsertVariableSubmenu(scenarioVariablesProvider) { token ->
            textComponent.replaceSelection(token)
        })
        popup.addSeparator()
        popup.add(JMenuItem(I18nManager.t("vars.menu.cut")).apply {
            isEnabled = textComponent.isEditable && textComponent.selectedText != null
            addActionListener { textComponent.cut() }
        })
        popup.add(JMenuItem(I18nManager.t("vars.menu.copy")).apply {
            isEnabled = textComponent.selectedText != null
            addActionListener { textComponent.copy() }
        })
        popup.add(JMenuItem(I18nManager.t("vars.menu.paste")).apply {
            isEnabled = textComponent.isEditable
            addActionListener { textComponent.paste() }
        })
        return popup
    }

    private fun buildInsertVariableSubmenu(
        scenarioVariablesProvider: () -> List<ScenarioVariableItem>,
        onInsert: (String) -> Unit
    ): JMenu {
        val submenu = JMenu(I18nManager.t("vars.menu.insert"))

        // 1. Built-in Dynamic Variables
        submenu.add(JMenuItem(I18nManager.t("vars.menu.sectionDynamic")).apply { isEnabled = false })
        for (spec in VariableResolver.DYNAMIC_VARIABLES) {
            val preview = spec.evaluator.invoke()
            submenu.add(JMenuItem("${spec.token}   ($preview)").apply {
                addActionListener { onInsert(spec.token) }
            })
        }

        val allScenarioVars = scenarioVariablesProvider()
        val extractedVars = allScenarioVars.filter { it.isExtracted }
        val globalVars = allScenarioVars.filter { !it.isExtracted }

        // 2. Current Scenario Extracted Variables
        submenu.addSeparator()
        submenu.add(JMenuItem(I18nManager.t("vars.menu.sectionExtracted")).apply { isEnabled = false })
        if (extractedVars.isEmpty()) {
            submenu.add(JMenuItem(I18nManager.t("vars.menu.noScenarioVars")).apply { isEnabled = false })
        } else {
            for (item in extractedVars) {
                val suffix = item.currentValue?.takeIf { it.isNotEmpty() }?.let { "   ($it)" }.orEmpty()
                submenu.add(JMenuItem("${item.token}$suffix").apply {
                    addActionListener { onInsert(item.token) }
                })
            }
        }

        // 3. Project Global Variables
        submenu.addSeparator()
        submenu.add(JMenuItem(I18nManager.t("vars.menu.sectionGlobal")).apply { isEnabled = false })
        if (globalVars.isEmpty()) {
            submenu.add(JMenuItem(I18nManager.t("vars.menu.noGlobalVars")).apply { isEnabled = false })
        } else {
            for (item in globalVars) {
                val displayVal = when {
                    item.isSecret && !item.currentValue.isNullOrEmpty() -> "   (••••••••)"
                    !item.currentValue.isNullOrEmpty() -> "   (${item.currentValue})"
                    else -> ""
                }
                submenu.add(JMenuItem("${item.token}$displayVal").apply {
                    addActionListener { onInsert(item.token) }
                })
            }
        }

        return submenu
    }
}
