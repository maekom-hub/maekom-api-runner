package com.maekom.apirunner.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.engine.BulkJsonVariableParser
import com.maekom.apirunner.engine.BulkMergeMode
import com.maekom.apirunner.engine.VariableConflictType
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.EnvironmentProfile
import com.maekom.apirunner.model.GlobalVariableItem
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

// [v0.3.2] 환경 프로필 보호 모드(confirmBeforeRun: 실행 전 확인 팝업, PRD 기본 활성화) 설정 UI 추가
// 변경 의도: PRD 등 운영 환경에서 실수로 API를 실행하는 사고를 사전 차단하기 위해 환경 프로필 단위 보호 모드 지원

/**
 * Dialog for adding, editing, and deleting project-wide BaseURL environment profiles
 * persisted in `.maekom/environments.json`.
 */
class EnvironmentManagerDialog(
    private val project: Project,
    initialProfiles: List<EnvironmentProfile>,
    private val localPortDetector: () -> String
) : DialogWrapper(project, true) {

    private val profiles: MutableList<EnvironmentProfile> = initialProfiles.map { it.deepCopy() }.toMutableList()

    private val tableModel = object : DefaultTableModel(
        arrayOf<Any>(
            I18nManager.t("env.col.name"),
            I18nManager.t("env.col.url"),
            I18nManager.t("env.col.protect")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(tableModel)

    init {
        title = I18nManager.t("env.dialog.title")
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        reloadTable()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(640, 340)
        root.border = JBUI.Borders.empty(6)

        val hintLabel = JBLabel("<html>${I18nManager.t("env.dialog.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        root.add(hintLabel, BorderLayout.NORTH)

        table.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 25
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).preferredWidth = 120
            columnModel.getColumn(1).preferredWidth = 370
            columnModel.getColumn(2).preferredWidth = 110
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && selectedRow in profiles.indices) {
                        editSelectedProfile()
                    }
                }
            })
        }
        root.add(JBScrollPane(table), BorderLayout.CENTER)

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn = JButton(I18nManager.t("env.add")).apply {
            addActionListener { addNewProfile() }
        }
        val editBtn = JButton(I18nManager.t("env.edit")).apply {
            addActionListener { editSelectedProfile() }
        }
        val deleteBtn = JButton(I18nManager.t("env.delete")).apply {
            addActionListener { deleteSelectedProfile() }
        }
        val resetLocalBtn = JButton(I18nManager.t("env.resetLocal")).apply {
            addActionListener {
                val detected = localPortDetector()
                val localIdx = profiles.indexOfFirst { it.builtInLocal || it.name.equals("Local", ignoreCase = true) }
                if (localIdx >= 0) {
                    profiles[localIdx].baseUrl = detected
                    reloadTable()
                    table.setRowSelectionInterval(localIdx, localIdx)
                }
            }
        }
        btnBar.add(addBtn)
        btnBar.add(editBtn)
        btnBar.add(deleteBtn)
        btnBar.add(resetLocalBtn)
        root.add(btnBar, BorderLayout.SOUTH)

        return root
    }

    private fun addNewProfile() {
        val dialog = EnvironmentProfileEditDialog(
            project = project,
            existing = null,
            existingNames = profiles.map { it.name }
        )
        if (dialog.showAndGet()) {
            profiles.add(dialog.toProfile())
            reloadTable()
            table.setRowSelectionInterval(profiles.lastIndex, profiles.lastIndex)
        }
    }

    private fun editSelectedProfile() {
        val row = table.selectedRow
        if (row !in profiles.indices) return
        val current = profiles[row]
        val otherNames = profiles.filterIndexed { idx, _ -> idx != row }.map { it.name }
        val dialog = EnvironmentProfileEditDialog(
            project = project,
            existing = current,
            existingNames = otherNames
        )
        if (dialog.showAndGet()) {
            profiles[row] = dialog.toProfile()
            reloadTable()
            table.setRowSelectionInterval(row, row)
        }
    }

    private fun deleteSelectedProfile() {
        val row = table.selectedRow
        if (row !in profiles.indices) return
        val target = profiles[row]
        if (target.builtInLocal || target.name.equals("Local", ignoreCase = true)) {
            Messages.showWarningDialog(project, I18nManager.t("env.cannotDeleteLocal"), I18nManager.t("env.dialog.title"))
            return
        }
        profiles.removeAt(row)
        reloadTable()
    }

    private fun reloadTable() {
        tableModel.rowCount = 0
        for (p in profiles) {
            val protectLabel = if (p.confirmBeforeRun) I18nManager.t("env.protect.on") else "-"
            tableModel.addRow(arrayOf<Any>(p.name, p.baseUrl, protectLabel))
        }
    }

    fun getUpdatedProfiles(): MutableList<EnvironmentProfile> =
        profiles.map { it.deepCopy() }.toMutableList()
}

private class EnvironmentProfileEditDialog(
    project: Project,
    private val existing: EnvironmentProfile?,
    private val existingNames: List<String>
) : DialogWrapper(project, true) {

    private val isLocalProfile = existing?.builtInLocal == true || existing?.name.equals("Local", ignoreCase = true)
    private val nameField = JBTextField(existing?.name.orEmpty(), 24).apply {
        isEnabled = !isLocalProfile
    }
    private val urlField = JBTextField(existing?.baseUrl.orEmpty(), 34)
    private val confirmBeforeRunCheckbox = JBCheckBox(
        I18nManager.t("env.editDialog.confirmBeforeRun"),
        existing?.confirmBeforeRun ?: false
    ).apply {
        foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
        font = font.deriveFont(Font.BOLD)
    }
    private var userToggledConfirm = existing != null

    init {
        title = if (existing == null) {
            I18nManager.t("env.editDialog.addTitle")
        } else {
            I18nManager.t("env.editDialog.editTitle")
        }
        setOKButtonText(I18nManager.t("global.save"))
        setCancelButtonText(I18nManager.t("global.cancel"))
        confirmBeforeRunCheckbox.addActionListener { userToggledConfirm = true }
        // [v0.3.2] 신규 프로필 이름이 PRD인 경우 보호 모드 기본 활성화 강조
        nameField.document.addDocumentListener(object : DocumentListener {
            private fun autoSuggestPrd() {
                if (!userToggledConfirm && nameField.text.trim().equals("PRD", ignoreCase = true)) {
                    confirmBeforeRunCheckbox.isSelected = true
                }
            }
            override fun insertUpdate(e: DocumentEvent?) = autoSuggestPrd()
            override fun removeUpdate(e: DocumentEvent?) = autoSuggestPrd()
            override fun changedUpdate(e: DocumentEvent?) = autoSuggestPrd()
        })
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
        panel.add(JBLabel(I18nManager.t("env.editDialog.name")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(nameField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel(I18nManager.t("env.editDialog.url")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(urlField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(confirmBeforeRunCheckbox, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo(I18nManager.t("env.editDialog.emptyName"), nameField)
        }
        if (existingNames.any { it.equals(name, ignoreCase = true) }) {
            return ValidationInfo(I18nManager.t("env.editDialog.duplicateName", name), nameField)
        }
        if (urlField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.t("env.editDialog.emptyUrl"), urlField)
        }
        return null
    }

    fun toProfile(): EnvironmentProfile = EnvironmentProfile(
        name = nameField.text.trim(),
        baseUrl = urlField.text.trim(),
        builtInLocal = isLocalProfile,
        confirmBeforeRun = confirmBeforeRunCheckbox.isSelected
    )
}

/**
 * Bulk JSON Import Dialog shared across:
 * 1) Project Global Variables tab (.maekom/global-variables.json)
 * 2) Scenario Variables tab (scenario JSON)
 * 3) Local Secrets tab (.maekom/secrets.local.json)
 *
 * Parses both Key-Value JSON objects and arrays, runs [VariableNameValidator] duplicate checks,
 * and supports Overwrite-Existing or Keep-Existing merge modes.
 */
class BulkJsonImportDialog(
    private val project: Project,
    targetName: String,
    private val globalVariablesProvider: () -> List<GlobalVariableItem>,
    private val scenarioProvider: () -> ApiScenario,
    private val isImportingIntoGlobalVars: Boolean
) : DialogWrapper(project, true) {

    private val jsonArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
        text = "{\n  \"memberId\": \"1001\",\n  \"apiKey\": \"sample-key\"\n}"
    }
    private val overwriteRadio = JBRadioButton(I18nManager.t("bulk.mode.overwrite"), true)
    private val keepRadio = JBRadioButton(I18nManager.t("bulk.mode.keep"), false)

    private var validatedItems: List<GlobalVariableItem> = emptyList()

    init {
        title = I18nManager.t("bulk.title", targetName)
        setOKButtonText(I18nManager.t("bulk.apply"))
        setCancelButtonText(I18nManager.t("global.cancel"))

        val group = ButtonGroup()
        group.add(overwriteRadio)
        group.add(keepRadio)

        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(620, 380)
        root.border = JBUI.Borders.empty(6)

        val hintLabel = JBLabel("<html>${I18nManager.t("bulk.hint")}</html>").apply {
            foreground = JBColor.GRAY
        }
        root.add(hintLabel, BorderLayout.NORTH)
        root.add(JBScrollPane(jsonArea), BorderLayout.CENTER)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        modePanel.add(JBLabel(I18nManager.t("bulk.modeLabel")))
        modePanel.add(overwriteRadio)
        modePanel.add(keepRadio)
        root.add(modePanel, BorderLayout.SOUTH)

        return root
    }

    override fun doOKAction() {
        val raw = jsonArea.text.trim()
        if (raw.isEmpty()) {
            Messages.showWarningDialog(project, I18nManager.t("bulk.emptyJson"), title)
            return
        }

        val parsedResult = BulkJsonVariableParser.parse(raw)
        if (parsedResult.isFailure) {
            Messages.showWarningDialog(
                project,
                parsedResult.exceptionOrNull()?.message ?: I18nManager.t("bulk.emptyJson"),
                title
            )
            return
        }

        val parsedItems = parsedResult.getOrThrow()
        if (parsedItems.isEmpty()) {
            Messages.showWarningDialog(project, I18nManager.t("bulk.noItems"), title)
            return
        }

        val selectedMode = getSelectedMergeMode()
        val existingGlobals = globalVariablesProvider()
        val scenario = scenarioProvider()

        data class ConflictEntry(
            val index: Int,
            val originalKey: String,
            val sourceLabel: String,
            val recommendedName: String
        )

        val conflicts = mutableListOf<ConflictEntry>()
        val reservedNames = VariableNameValidator.collectAllExistingNames(existingGlobals, scenario).toMutableSet()

        for ((idx, item) in parsedItems.withIndex()) {
            val validation = VariableNameValidator.validate(
                candidateName = item.key,
                globalVariables = existingGlobals,
                scenario = scenario
            )
            if (!validation.isValid) {
                // When overwriting existing keys inside Global Variables tab itself, allow same global key intentionally
                val isIntentionalOverwrite = isImportingIntoGlobalVars &&
                    selectedMode == BulkMergeMode.OVERWRITE_EXISTING &&
                    validation.conflictType == VariableConflictType.GLOBAL_VARIABLE
                if (!isIntentionalOverwrite) {
                    val uniqueSuggested = VariableNameValidator.generateRecommendedName(
                        baseName = item.key,
                        existingNames = reservedNames
                    )
                    reservedNames.add(uniqueSuggested)
                    conflicts.add(
                        ConflictEntry(
                            index = idx,
                            originalKey = item.key,
                            sourceLabel = validation.conflictSourceLabel,
                            recommendedName = uniqueSuggested
                        )
                    )
                }
            } else {
                reservedNames.add(item.key)
            }
        }

        if (conflicts.isEmpty()) {
            validatedItems = parsedItems
            super.doOKAction()
            return
        }

        val conflictSummary = conflicts.joinToString("\n") { c ->
            "• ${c.originalKey} (${c.sourceLabel}) → ${c.recommendedName}"
        }
        val choice = Messages.showYesNoCancelDialog(
            project,
            I18nManager.t("bulk.conflict.msg", conflictSummary),
            I18nManager.t("bulk.conflict.title"),
            I18nManager.t("bulk.conflict.applyRecommended"),
            I18nManager.t("bulk.conflict.skipConflicts"),
            I18nManager.t("bulk.conflict.cancel"),
            Messages.getWarningIcon()
        )

        val conflictMap = conflicts.associateBy { it.index }
        when (choice) {
            Messages.YES -> {
                validatedItems = parsedItems.mapIndexed { idx, item ->
                    val c = conflictMap[idx]
                    if (c != null) item.copy(key = c.recommendedName) else item
                }
                super.doOKAction()
            }
            Messages.NO -> {
                validatedItems = parsedItems.filterIndexed { idx, _ -> !conflictMap.containsKey(idx) }
                if (validatedItems.isEmpty()) return
                super.doOKAction()
            }
            else -> {
                // Cancel — let user inspect/edit JSON
                return
            }
        }
    }

    fun getImportedItems(): List<GlobalVariableItem> = validatedItems

    fun getSelectedMergeMode(): BulkMergeMode =
        if (keepRadio.isSelected) BulkMergeMode.KEEP_EXISTING else BulkMergeMode.OVERWRITE_EXISTING
}
