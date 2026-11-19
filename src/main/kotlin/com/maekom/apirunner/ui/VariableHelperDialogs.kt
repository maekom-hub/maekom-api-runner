package com.maekom.apirunner.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.JsonPathCandidate
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ExtractRule
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
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.text.JTextComponent

// [v0.1.2] 동적 변수·추출 변수 치트시트, 우클릭 변수 삽입 메뉴, JsonPath 가이드 및 응답 원클릭 추출 다이얼로그 추가
// 변경 의도: 사용자가 변수명과 JsonPath 문법을 외우지 않고도 클릭만으로 복사·삽입·추출 규칙 등록을 완료하도록 지원

data class ScenarioVariableItem(
    val name: String,
    val token: String,
    val isExtracted: Boolean,
    val currentValue: String?
)

/**
 * Non-modal cheat-sheet dialog showing built-in dynamic variables (with live evaluated previews)
 * and scenario extracted/global variables with one-click clipboard copy.
 */
class VariableCheatSheetDialog(
    project: Project,
    private val scenarioVariablesProvider: () -> List<ScenarioVariableItem>
) : DialogWrapper(project, true) {

    private val tableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("vars.col.category"),
            I18nManager.t("vars.col.token"),
            I18nManager.t("vars.col.desc"),
            I18nManager.t("vars.col.preview"),
            I18nManager.t("vars.col.action")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JBTable(tableModel)
    private val feedbackLabel = JBLabel(" ")

    init {
        title = I18nManager.t("vars.dialog.title")
        isModal = false
        setOKButtonText(I18nManager.t("vars.dialog.close"))
        init()
        reloadRows()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(820, 440)
        root.border = JBUI.Borders.empty(4)

        val topBar = JPanel(BorderLayout(8, 0))
        val hintLabel = JBLabel("<html>${I18nManager.t("vars.dialog.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        val refreshBtn = JButton(I18nManager.t("vars.dialog.refresh")).apply {
            addActionListener { reloadRows() }
        }
        topBar.add(hintLabel, BorderLayout.CENTER)
        topBar.add(refreshBtn, BorderLayout.EAST)
        root.add(topBar, BorderLayout.NORTH)

        table.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 26
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).apply {
                preferredWidth = 82
                maxWidth = 96
            }
            columnModel.getColumn(1).apply {
                preferredWidth = 175
                cellRenderer = MonospaceTokenRenderer()
            }
            columnModel.getColumn(2).preferredWidth = 240
            columnModel.getColumn(3).apply {
                preferredWidth = 220
                cellRenderer = MonospacePreviewRenderer()
            }
            columnModel.getColumn(4).apply {
                preferredWidth = 68
                maxWidth = 76
                cellRenderer = CopyButtonCellRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    if (col == 4 || e.clickCount == 2) {
                        val token = tableModel.getValueAt(row, 1)?.toString() ?: return
                        copyTokenToClipboard(token)
                    }
                }
            })
        }

        root.add(JBScrollPane(table), BorderLayout.CENTER)

        feedbackLabel.apply {
            font = font.deriveFont(Font.BOLD)
            foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
        root.add(feedbackLabel, BorderLayout.SOUTH)

        return root
    }

    private fun reloadRows() {
        tableModel.rowCount = 0
        val copyLabel = I18nManager.t("vars.copyBtn")

        // 1. Built-in dynamic variables evaluated live
        val dynamicCat = I18nManager.t("vars.cat.dynamic")
        for (spec in VariableResolver.DYNAMIC_VARIABLES) {
            tableModel.addRow(
                arrayOf<Any>(
                    dynamicCat,
                    spec.token,
                    I18nManager.t(spec.descKey),
                    spec.evaluator.invoke(),
                    copyLabel
                )
            )
        }

        // 2. Scenario extracted & global variables
        val extractedCat = I18nManager.t("vars.cat.extracted")
        val globalCat = I18nManager.t("vars.cat.global")
        for (item in scenarioVariablesProvider()) {
            val cat = if (item.isExtracted) extractedCat else globalCat
            val desc = if (item.isExtracted) I18nManager.t("vars.extracted.desc") else I18nManager.t("vars.global.desc")
            val preview = item.currentValue?.takeIf { it.isNotEmpty() } ?: I18nManager.t("vars.extracted.pending")
            tableModel.addRow(arrayOf<Any>(cat, item.token, desc, preview, copyLabel))
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

        val scroll = JBScrollPane(editorPane).apply {
            preferredSize = Dimension(740, 480)
        }
        return scroll
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Confirmation dialog for creating an ExtractRule from a selected JSON response field.
 */
class AddExtractRuleDialog(
    project: Project,
    candidates: List<JsonPathCandidate>,
    initialCandidate: JsonPathCandidate?
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

    fun toExtractRule(): ExtractRule = ExtractRule(
        varName = varNameField.text.trim(),
        jsonPath = jsonPathField.text.trim(),
        required = requiredCheckBox.isSelected
    )
}

/**
 * Installs right-click variable insertion context menus on JTextComponents and JBTable Value columns.
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
        // 1. Custom text cell editor with right-click menu while editing the Value cell
        val editorField = JBTextField()
        installOnTextComponent(editorField, scenarioVariablesProvider)
        table.columnModel.getColumn(valueColumnIndex).cellEditor = DefaultCellEditor(editorField)

        // 2. Right-click directly on a non-editing table row in the Value column
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

        submenu.add(JMenuItem(I18nManager.t("vars.menu.sectionDynamic")).apply { isEnabled = false })
        for (spec in VariableResolver.DYNAMIC_VARIABLES) {
            val preview = spec.evaluator.invoke()
            submenu.add(JMenuItem("${spec.token}   ($preview)").apply {
                addActionListener { onInsert(spec.token) }
            })
        }

        submenu.addSeparator()
        submenu.add(JMenuItem(I18nManager.t("vars.menu.sectionScenario")).apply { isEnabled = false })
        val scenarioVars = scenarioVariablesProvider()
        if (scenarioVars.isEmpty()) {
            submenu.add(JMenuItem(I18nManager.t("vars.menu.noScenarioVars")).apply { isEnabled = false })
        } else {
            for (item in scenarioVars) {
                val suffix = item.currentValue?.takeIf { it.isNotEmpty() }?.let { "   ($it)" }.orEmpty()
                submenu.add(JMenuItem("${item.token}$suffix").apply {
                    addActionListener { onInsert(item.token) }
                })
            }
        }
        return submenu
    }
}
