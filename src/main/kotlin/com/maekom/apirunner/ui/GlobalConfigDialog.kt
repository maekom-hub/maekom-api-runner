package com.maekom.apirunner.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.i18n.I18nManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Dialog for editing:
 * 1. Global Headers (saved inside scenario JSON, merged with step headers)
 * 2. Global Variables (saved inside scenario JSON, referenced via {{varName}})
 * 3. Local Secrets (saved separately in .maekom/secrets.local.json, excluded from Git)
 */
class GlobalConfigDialog(
    project: Project,
    initialHeaders: Map<String, String>,
    initialVariables: Map<String, String>,
    initialSecrets: Map<String, String>
) : DialogWrapper(project, true) {

    private val headersModel = createKeyValueModel(initialHeaders)
    private val variablesModel = createKeyValueModel(initialVariables)
    private val secretsModel = createKeyValueModel(initialSecrets)

    private lateinit var headersTable: JBTable
    private lateinit var variablesTable: JBTable
    private lateinit var secretsTable: JBTable

    init {
        title = I18nManager.t("global.title")
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabbedPane = JBTabbedPane()
        tabbedPane.preferredSize = Dimension(680, 420)

        headersTable = createEditableTable(headersModel)
        variablesTable = createEditableTable(variablesModel)
        secretsTable = createEditableTable(secretsModel)

        tabbedPane.addTab(
            I18nManager.t("global.tab.headers"),
            buildTableTab(
                table = headersTable,
                model = headersModel,
                hint = I18nManager.t("global.tab.headers.hint")
            )
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.vars"),
            buildTableTab(
                table = variablesTable,
                model = variablesModel,
                hint = I18nManager.t("global.tab.vars.hint")
            )
        )
        tabbedPane.addTab(
            I18nManager.t("global.tab.secrets"),
            buildTableTab(
                table = secretsTable,
                model = secretsModel,
                hint = I18nManager.t("global.tab.secrets.hint")
            )
        )

        return tabbedPane
    }

    override fun doOKAction() {
        stopEditing(headersTable)
        stopEditing(variablesTable)
        stopEditing(secretsTable)
        super.doOKAction()
    }

    fun getGlobalHeaders(): LinkedHashMap<String, String> = extractMap(headersModel)

    fun getGlobalVariables(): LinkedHashMap<String, String> = extractMap(variablesModel)

    fun getSecrets(): LinkedHashMap<String, String> = extractMap(secretsModel)

    private fun buildTableTab(table: JBTable, model: DefaultTableModel, hint: String): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)

        val hintLabel = JBLabel("<html>$hint</html>").apply {
            foreground = JBColor.GRAY
        }
        panel.add(hintLabel, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("global.add")).apply {
            addActionListener {
                stopEditing(table)
                model.addRow(arrayOf("", ""))
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

    private fun createKeyValueModel(initial: Map<String, String>): DefaultTableModel {
        val model = object : DefaultTableModel(
            arrayOf(I18nManager.t("global.col.key"), I18nManager.t("global.col.value")),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        for ((k, v) in initial) {
            model.addRow(arrayOf(k, v))
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
}
