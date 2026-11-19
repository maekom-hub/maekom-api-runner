package com.maekom.apirunner.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
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
        title = "공통 헤더 / 전역 변수 / 로컬 시크릿 관리"
        setOKButtonText("저장")
        setCancelButtonText("취소")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabbedPane = JBTabbedPane()
        tabbedPane.preferredSize = Dimension(680, 420)

        headersTable = createEditableTable(headersModel)
        variablesTable = createEditableTable(variablesModel)
        secretsTable = createEditableTable(secretsModel)

        tabbedPane.addTab(
            "공통 헤더 (Global Headers)",
            buildTableTab(
                table = headersTable,
                model = headersModel,
                hint = "모든 스텝에 자동 병합됩니다. 개별 스텝 헤더에 동일 키가 있으면 스텝 헤더가 우선 적용됩니다."
            )
        )
        tabbedPane.addTab(
            "전역 변수 (Global Variables)",
            buildTableTab(
                table = variablesTable,
                model = variablesModel,
                hint = "시나리오 파일(.maekom/scenarios/*.json)에 저장됩니다. URL/헤더/본문에서 {{변수명}}으로 참조하세요."
            )
        )
        tabbedPane.addTab(
            "🔒 로컬 시크릿 (secrets.local.json)",
            buildTableTab(
                table = secretsTable,
                model = secretsModel,
                hint = "비밀번호/토큰 등 민감정보는 .maekom/secrets.local.json에 분리 저장됩니다(.gitignore 적용). 치환 우선순위: 실행 컨텍스트 > 시크릿 > 전역 변수"
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
        val addBtn = JButton("+ 항목 추가").apply {
            addActionListener {
                stopEditing(table)
                model.addRow(arrayOf("", ""))
                val row = model.rowCount - 1
                table.setRowSelectionInterval(row, row)
                table.editCellAt(row, 0)
            }
        }
        val removeBtn = JButton("- 선택 삭제").apply {
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
        val model = object : DefaultTableModel(arrayOf("Key (이름)", "Value (값)"), 0) {
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
