package com.maekom.apirunner.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.model.FolderEntryInfo
import com.maekom.apirunner.model.ScenarioEntryInfo
import com.maekom.apirunner.model.ScenarioTreeSnapshot
import com.maekom.apirunner.storage.MoveStatus
import com.maekom.apirunner.storage.RenameStatus
import com.maekom.apirunner.storage.ScenarioFileRepository
import com.maekom.apirunner.storage.ScenarioStorageService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

// [v0.3.0] 폴더·시나리오 트리 관리 모달 다이얼로그(ScenarioExplorerDialog) 신규 구현
// 변경 의도: 좌측 AllIcons 컴팩트 툴바·ColoredTreeCellRenderer 트리·양방향 폴더/시나리오 드래그 앤 드롭(빈 여백=루트 포함)과
//          우측 Base Info(Name/Description) 편집 및 Apply/OK 즉시 반영을 통해 대규모 시나리오를 체계적으로 관리

sealed class ExplorerNodeData {
    data object Root : ExplorerNodeData()
    data class Folder(val info: FolderEntryInfo) : ExplorerNodeData()
    data class Scenario(val info: ScenarioEntryInfo) : ExplorerNodeData()
}

private data class ExplorerDragPayload(
    val isFolder: Boolean,
    val relativePath: String
)

class ScenarioExplorerDialog(
    private val project: Project,
    private val storage: ScenarioStorageService,
    initialActiveScenarioPath: String,
    private val defaultBaseUrlProvider: () -> String
) : DialogWrapper(project, true) {

    var trackedActiveScenarioPath: String = ScenarioFileRepository.normalizeRelativePath(initialActiveScenarioPath)
        private set

    var activeScenarioWasDeleted: Boolean = false
        private set

    var activeScenarioMetadataUpdated: Boolean = false
        private set

    var selectedScenarioPathToOpen: String? = null
        private set

    private var currentSnapshot: ScenarioTreeSnapshot = ScenarioTreeSnapshot(emptyList(), emptyList(), emptyMap())

    // Left Tree
    private val rootNode = DefaultMutableTreeNode(ExplorerNodeData.Root)
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    // Right Base Info Form
    private val rightCardLayout = CardLayout()
    private val rightCardPanel = JPanel(rightCardLayout)
    private val typeBadgeLabel = JBLabel()
    private val pathValueLabel = JBLabel()
    private val nameField = JBTextField()
    private val validationLabel = JBLabel()
    private val descriptionArea = JBTextArea(5, 28).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val metaSummaryLabel = JBLabel()
    private val footerStatusLabel = JBLabel(" ")

    private var editingNode: ExplorerNodeData? = null
    private var isPopulatingForm: Boolean = false
    private var isUpdatingSelection: Boolean = false
    private var isFormDirty: Boolean = false

    private val applyDialogAction = object : AbstractAction(I18nManager.t("explorer.apply")) {
        override fun actionPerformed(e: ActionEvent?) {
            applyCurrentBaseInfoEdits(showFeedback = true)
        }
    }

    init {
        title = I18nManager.t("explorer.title")
        setOKButtonText(I18nManager.t("explorer.ok"))
        setCancelButtonText(I18nManager.t("explorer.cancel"))
        applyDialogAction.isEnabled = false
        init()
        reloadTree(
            preserveExpanded = false,
            selectPath = trackedActiveScenarioPath.takeIf { it.isNotBlank() },
            selectIsFolder = false
        )
        expandAllFolders()
        if (trackedActiveScenarioPath.isNotBlank()) {
            selectNodeInTree(trackedActiveScenarioPath, isFolder = false)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, applyDialogAction, cancelAction)
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(840, 520)
            minimumSize = Dimension(680, 420)
        }

        val splitter = JBSplitter(false, 0.48f).apply {
            firstComponent = buildLeftTreePane()
            secondComponent = buildRightBaseInfoPane()
        }

        footerStatusLabel.apply {
            foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            border = JBUI.Borders.empty(2, 4, 0, 4)
        }

        val bottomBar = JPanel(BorderLayout(8, 0)).apply {
            val dndHint = JBLabel(I18nManager.t("explorer.hint.dnd")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11.5f)
            }
            add(dndHint, BorderLayout.CENTER)
            add(footerStatusLabel, BorderLayout.SOUTH)
        }

        rootPanel.add(splitter, BorderLayout.CENTER)
        rootPanel.add(bottomBar, BorderLayout.SOUTH)
        return rootPanel
    }

    // --- Left Pane: Compact Icon ActionToolbar + Tree with Drag & Drop ---

    private fun buildLeftTreePane(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createLineBorder(JBColor.border())

        val toolbar = buildTreeActionToolbar()
        val toolbarWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            add(toolbar.component, BorderLayout.WEST)
        }

        configureTree()

        panel.add(toolbarWrapper, BorderLayout.NORTH)
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        return panel
    }

    private fun buildTreeActionToolbar() = run {
        val group = DefaultActionGroup()

        // 1) 새로고침 (Refresh)
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.refresh"),
            I18nManager.t("explorer.action.refresh"),
            AllIcons.Actions.Refresh
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) {
                if (!flushEditsBeforeAction()) return
                val sel = getSelectedNodeData()
                reloadTree(
                    preserveExpanded = true,
                    selectPath = nodePathOf(sel),
                    selectIsFolder = sel is ExplorerNodeData.Folder
                )
            }
        })

        // 2) 선택 삭제 (Delete)
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.delete"),
            I18nManager.t("explorer.action.delete"),
            AllIcons.General.Delete
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = getSelectedNodeData() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                deleteSelectedNode()
            }
        })

        // 3) 폴더/시나리오 추가 (폴더 아이콘 + 드롭다운 화살표: 새 폴더 생성 / 새 시나리오 생성)
        val addPopupGroup = object : DefaultActionGroup(
            I18nManager.t("explorer.action.add"),
            true
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.icon = AllIcons.Actions.NewFolder
                e.presentation.isPopupGroup = true
            }
        }.apply {
            templatePresentation.icon = AllIcons.Actions.NewFolder
            templatePresentation.text = I18nManager.t("explorer.action.add")
            templatePresentation.description = I18nManager.t("explorer.action.add")

            add(object : DumbAwareAction(
                I18nManager.t("explorer.action.newFolder"),
                I18nManager.t("explorer.action.newFolder"),
                AllIcons.Actions.NewFolder
            ) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = promptCreateFolder()
            })

            add(object : DumbAwareAction(
                I18nManager.t("explorer.action.newScenario"),
                I18nManager.t("explorer.action.newScenario"),
                AllIcons.FileTypes.Json
            ) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = promptCreateScenario()
            })
        }
        group.add(addPopupGroup)

        // 4) 구분선 + 복제 (Copy/Duplicate)
        group.add(Separator.create())
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.duplicate"),
            I18nManager.t("explorer.action.duplicate"),
            AllIcons.Actions.Copy
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = getSelectedNodeData() is ExplorerNodeData.Scenario
            }

            override fun actionPerformed(e: AnActionEvent) = duplicateSelectedScenario()
        })

        // 5) 구분선 + 모두 펼치기 / 모두 접기
        group.add(Separator.create())
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.expandAll"),
            I18nManager.t("explorer.action.expandAll"),
            AllIcons.Actions.Expandall
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = expandAllFolders()
        })
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.collapseAll"),
            I18nManager.t("explorer.action.collapseAll"),
            AllIcons.Actions.Collapseall
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = collapseAllFolders()
        })

        // 6) 구분선 + 내보내기 (Export) / 가져오기 (Import)
        group.add(Separator.create())
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.export"),
            I18nManager.t("explorer.action.export"),
            AllIcons.ToolbarDecorator.Export
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = getSelectedNodeData() is ExplorerNodeData.Scenario
            }

            override fun actionPerformed(e: AnActionEvent) = exportSelectedScenario()
        })
        group.add(object : DumbAwareAction(
            I18nManager.t("explorer.action.import"),
            I18nManager.t("explorer.action.import"),
            AllIcons.ToolbarDecorator.Import
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = importScenarioToSelectedLocation()
        })

        ActionManager.getInstance()
            .createActionToolbar("MaekomScenarioExplorerToolbar", group, true)
            .apply {
                targetComponent = tree
            }
    }

    private fun configureTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 25
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = ExplorerTreeCellRenderer()
            dragEnabled = true
            dropMode = DropMode.ON_OR_INSERT
            transferHandler = ExplorerTransferHandler()
        }

        tree.addTreeSelectionListener {
            if (isUpdatingSelection) return@addTreeSelectionListener
            val newSelected = getSelectedNodeData()
            val prevEditing = editingNode
            if (prevEditing != null && isFormDirty && nodeIdentity(prevEditing) != nodeIdentity(newSelected)) {
                if (!applyCurrentBaseInfoEdits(showFeedback = false)) {
                    // Revert selection back to node with invalid unsaved edits
                    isUpdatingSelection = true
                    try {
                        selectNodeInTree(nodePathOf(prevEditing), prevEditing is ExplorerNodeData.Folder)
                    } finally {
                        isUpdatingSelection = false
                    }
                    return@addTreeSelectionListener
                }
                // After reloadTree inside applyCurrentBaseInfoEdits, re-select the target node
                isUpdatingSelection = true
                try {
                    if (newSelected != null) {
                        selectNodeInTree(nodePathOf(newSelected), newSelected is ExplorerNodeData.Folder)
                    }
                } finally {
                    isUpdatingSelection = false
                }
            }
            populateBaseInfoForm(getSelectedNodeData())
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                    if (node is ExplorerNodeData.Scenario) {
                        if (!flushEditsBeforeAction()) return
                        val refreshed = getSelectedNodeData() as? ExplorerNodeData.Scenario ?: node
                        selectedScenarioPathToOpen = refreshed.info.relativePath
                        close(OK_EXIT_CODE)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)

            private fun maybeShowContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y)
                if (path != null) {
                    tree.selectionPath = path
                } else {
                    tree.clearSelection()
                }
                val selected = getSelectedNodeData()
                val menu = JPopupMenu()
                menu.add(JMenuItem(I18nManager.t("explorer.action.newFolder"), AllIcons.Actions.NewFolder).apply {
                    addActionListener { promptCreateFolder() }
                })
                menu.add(JMenuItem(I18nManager.t("explorer.action.newScenario"), AllIcons.FileTypes.Json).apply {
                    addActionListener { promptCreateScenario() }
                })
                if (selected is ExplorerNodeData.Scenario) {
                    menu.addSeparator()
                    menu.add(JMenuItem(I18nManager.t("explorer.action.duplicate"), AllIcons.Actions.Copy).apply {
                        addActionListener { duplicateSelectedScenario() }
                    })
                    menu.add(JMenuItem(I18nManager.t("explorer.action.export"), AllIcons.ToolbarDecorator.Export).apply {
                        addActionListener { exportSelectedScenario() }
                    })
                }
                menu.addSeparator()
                menu.add(JMenuItem(I18nManager.t("explorer.action.import"), AllIcons.ToolbarDecorator.Import).apply {
                    addActionListener { importScenarioToSelectedLocation() }
                })
                if (selected != null) {
                    menu.addSeparator()
                    menu.add(JMenuItem(I18nManager.t("explorer.action.delete"), AllIcons.General.Delete).apply {
                        addActionListener { deleteSelectedNode() }
                    })
                }
                menu.show(tree, e.x, e.y)
            }
        })
    }

    // --- Right Pane: Base Info Editor (Name & Description) ---

    private fun buildRightBaseInfoPane(): JPanel {
        val emptyPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(24)
            val emptyMsg = JBLabel(
                "<html><div style='text-align:center;'>${
                    I18nManager.t("explorer.baseInfo.empty").replace("\n", "<br/>")
                }</div></html>"
            ).apply {
                foreground = JBColor.GRAY
                horizontalAlignment = JBLabel.CENTER
            }
            add(emptyMsg, BorderLayout.CENTER)
        }

        val editorPanel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(12, 16, 12, 16)
        }

        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            val headerTitle = JBLabel(I18nManager.t("explorer.baseInfo.header")).apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }
            typeBadgeLabel.apply {
                font = font.deriveFont(Font.BOLD, 11f)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(1, 6)
                )
            }
            add(headerTitle)
            add(typeBadgeLabel)
        }

        val topSection = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.emptyBottom(8)
            )
            add(headerRow, BorderLayout.NORTH)
            pathValueLabel.foreground = JBColor.GRAY
            add(pathValueLabel, BorderLayout.SOUTH)
        }

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(4, 0, 4, 8)
        }

        // Row 0: Name
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        formPanel.add(JBLabel(I18nManager.t("explorer.baseInfo.nameLabel")).apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)

        gbc.gridx = 1
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 0, 2, 0)
        nameField.addActionListener {
            if (applyDialogAction.isEnabled) {
                applyCurrentBaseInfoEdits(showFeedback = true)
            }
        }
        formPanel.add(nameField, gbc)

        // Row 1: Inline validation error
        gbc.gridx = 1
        gbc.gridy = 1
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(0, 0, 8, 0)
        validationLabel.apply {
            foreground = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
            font = font.deriveFont(11.5f)
            text = " "
        }
        formPanel.add(validationLabel, gbc)

        // Row 2: Description
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        gbc.insets = JBUI.insets(4, 0, 4, 8)
        formPanel.add(JBLabel(I18nManager.t("explorer.baseInfo.descLabel")).apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)

        gbc.gridx = 1
        gbc.gridy = 2
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = JBUI.insets(4, 0, 8, 0)
        formPanel.add(JBScrollPane(descriptionArea), gbc)

        // Row 3: Meta summary
        gbc.gridx = 1
        gbc.gridy = 3
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(4, 0, 0, 0)
        metaSummaryLabel.foreground = JBColor.GRAY
        formPanel.add(metaSummaryLabel, gbc)

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onFormEdited()
            override fun removeUpdate(e: DocumentEvent?) = onFormEdited()
            override fun changedUpdate(e: DocumentEvent?) = onFormEdited()
        }
        nameField.document.addDocumentListener(docListener)
        descriptionArea.document.addDocumentListener(docListener)

        editorPanel.add(topSection, BorderLayout.NORTH)
        editorPanel.add(formPanel, BorderLayout.CENTER)

        rightCardPanel.add(emptyPanel, "EMPTY")
        rightCardPanel.add(editorPanel, "EDITOR")
        rightCardLayout.show(rightCardPanel, "EMPTY")
        return rightCardPanel
    }

    private fun populateBaseInfoForm(node: ExplorerNodeData?) {
        editingNode = node
        isPopulatingForm = true
        try {
            when (node) {
                is ExplorerNodeData.Folder -> {
                    typeBadgeLabel.text = I18nManager.t("explorer.baseInfo.type.folder")
                    typeBadgeLabel.foreground = JBColor(Color(0xB2, 0x6A, 0x00), Color(0xFF, 0xB7, 0x4D))
                    pathValueLabel.text = "${I18nManager.t("explorer.baseInfo.pathLabel")} ${ScenarioFileRepository.formatDisplayPath(node.info.relativePath)}"
                    nameField.text = node.info.name
                    descriptionArea.text = node.info.description
                    descriptionArea.caretPosition = 0
                    metaSummaryLabel.text = I18nManager.t("explorer.baseInfo.metaFolder", node.info.scenarioCount)
                    validationLabel.text = " "
                    rightCardLayout.show(rightCardPanel, "EDITOR")
                }

                is ExplorerNodeData.Scenario -> {
                    typeBadgeLabel.text = I18nManager.t("explorer.baseInfo.type.scenario")
                    typeBadgeLabel.foreground = JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
                    pathValueLabel.text = "${I18nManager.t("explorer.baseInfo.pathLabel")} ${ScenarioFileRepository.formatDisplayPath(node.info.relativePath)}"
                    nameField.text = node.info.name
                    descriptionArea.text = node.info.description
                    descriptionArea.caretPosition = 0
                    metaSummaryLabel.text = I18nManager.t(
                        "explorer.baseInfo.metaScenario",
                        node.info.stepCount,
                        node.info.baseUrl.ifBlank { "http://localhost:8080" }
                    )
                    validationLabel.text = " "
                    rightCardLayout.show(rightCardPanel, "EDITOR")
                }

                else -> {
                    nameField.text = ""
                    descriptionArea.text = ""
                    validationLabel.text = " "
                    rightCardLayout.show(rightCardPanel, "EMPTY")
                }
            }
            isFormDirty = false
            applyDialogAction.isEnabled = false
        } finally {
            isPopulatingForm = false
        }
    }

    private fun onFormEdited() {
        if (isPopulatingForm) return
        val node = editingNode ?: return
        val editedName = nameField.text.trim()
        val editedDesc = descriptionArea.text
        val (origName, origDesc) = when (node) {
            is ExplorerNodeData.Folder -> node.info.name to node.info.description
            is ExplorerNodeData.Scenario -> node.info.name to node.info.description
            else -> "" to ""
        }
        isFormDirty = (editedName != origName) || (editedDesc != origDesc)
        val validationError = validateEditedName(node, editedName)
        validationLabel.text = validationError ?: " "
        applyDialogAction.isEnabled = isFormDirty && validationError == null
    }

    private fun validateEditedName(node: ExplorerNodeData, candidateName: String): String? {
        if (candidateName.isEmpty()) {
            return I18nManager.t("explorer.validation.empty")
        }
        if (!ScenarioFileRepository.isValidItemName(candidateName)) {
            return I18nManager.t("explorer.validation.forbidden")
        }
        when (node) {
            is ExplorerNodeData.Folder -> {
                val dup = currentSnapshot.folders.any { sibling ->
                    sibling.parentPath == node.info.parentPath &&
                        sibling.name.equals(candidateName, ignoreCase = true) &&
                        !sibling.name.equals(node.info.name, ignoreCase = true)
                }
                if (dup) return I18nManager.t("explorer.validation.duplicate", candidateName)
            }

            is ExplorerNodeData.Scenario -> {
                val dup = currentSnapshot.scenarios.any { sibling ->
                    sibling.folderPath == node.info.folderPath &&
                        sibling.name.equals(candidateName, ignoreCase = true) &&
                        !sibling.name.equals(node.info.name, ignoreCase = true)
                }
                if (dup) return I18nManager.t("explorer.validation.duplicate", candidateName)
            }

            else -> Unit
        }
        return null
    }

    private fun applyCurrentBaseInfoEdits(showFeedback: Boolean): Boolean {
        val node = editingNode ?: return true
        if (!isFormDirty) return true

        val newName = nameField.text.trim()
        val newDesc = descriptionArea.text
        val validationError = validateEditedName(node, newName)
        if (validationError != null) {
            validationLabel.text = validationError
            Messages.showWarningDialog(project, validationError, I18nManager.t("explorer.title"))
            return false
        }

        val expanded = captureExpandedFolderPaths()
        return when (node) {
            is ExplorerNodeData.Folder -> {
                val oldPath = node.info.relativePath
                val result = storage.renameFolder(oldPath, newName, newDesc)
                if (result.status != RenameStatus.SUCCESS) {
                    val msg = if (result.status == RenameStatus.ALREADY_EXISTS) {
                        I18nManager.t("explorer.validation.duplicate", newName)
                    } else {
                        I18nManager.t("dialog.rename.failed.msg")
                    }
                    Messages.showWarningDialog(project, msg, I18nManager.t("explorer.title"))
                    false
                } else {
                    val newPath = result.folderPath
                    if (oldPath != newPath) {
                        trackedActiveScenarioPath = ScenarioFileRepository.remapPathAfterFolderChange(
                            trackedActiveScenarioPath,
                            oldPath,
                            newPath
                        )
                    }
                    val remappedExpanded = expanded.map {
                        ScenarioFileRepository.remapPathAfterFolderChange(it, oldPath, newPath)
                    }.toSet()
                    isFormDirty = false
                    applyDialogAction.isEnabled = false
                    reloadTreeWithExpandedSet(remappedExpanded, selectPath = newPath, selectIsFolder = true)
                    if (showFeedback) {
                        setFooterStatus(I18nManager.t("explorer.status.applied", ScenarioFileRepository.formatDisplayPath(newPath)))
                    }
                    true
                }
            }

            is ExplorerNodeData.Scenario -> {
                val oldPath = node.info.relativePath
                val result = storage.renameScenarioAtPath(oldPath, newName, newDesc)
                if (result.status != RenameStatus.SUCCESS) {
                    val msg = if (result.status == RenameStatus.ALREADY_EXISTS) {
                        I18nManager.t("explorer.validation.duplicate", newName)
                    } else {
                        I18nManager.t("dialog.rename.failed.msg")
                    }
                    Messages.showWarningDialog(project, msg, I18nManager.t("explorer.title"))
                    false
                } else {
                    val newPath = result.newRelativePath
                    if (trackedActiveScenarioPath == oldPath) {
                        trackedActiveScenarioPath = newPath
                        activeScenarioMetadataUpdated = true
                    }
                    isFormDirty = false
                    applyDialogAction.isEnabled = false
                    reloadTreeWithExpandedSet(expanded, selectPath = newPath, selectIsFolder = false)
                    if (showFeedback) {
                        setFooterStatus(I18nManager.t("explorer.status.applied", ScenarioFileRepository.formatDisplayPath(newPath)))
                    }
                    true
                }
            }

            else -> true
        }
    }

    private fun flushEditsBeforeAction(): Boolean {
        if (!isFormDirty) return true
        return applyCurrentBaseInfoEdits(showFeedback = false)
    }

    override fun doOKAction() {
        if (!applyCurrentBaseInfoEdits(showFeedback = false)) {
            return
        }
        val selected = getSelectedNodeData()
        if (selected is ExplorerNodeData.Scenario) {
            selectedScenarioPathToOpen = selected.info.relativePath
        }
        super.doOKAction()
    }

    // --- Tree CRUD Actions ---

    private fun targetFolderForCreation(): String {
        return when (val sel = getSelectedNodeData()) {
            is ExplorerNodeData.Folder -> sel.info.relativePath
            is ExplorerNodeData.Scenario -> sel.info.folderPath
            else -> ""
        }
    }

    private fun promptCreateFolder() {
        if (!flushEditsBeforeAction()) return
        val targetFolder = targetFolderForCreation()
        val locLabel = targetFolder.ifBlank { I18nManager.t("explorer.location.root") }
        val input = Messages.showInputDialog(
            project,
            I18nManager.t("explorer.prompt.newFolder.msg", locLabel),
            I18nManager.t("explorer.prompt.newFolder.title"),
            Messages.getQuestionIcon()
        )?.trim()
        if (input.isNullOrBlank()) return

        val result = storage.createFolder(targetFolder, input)
        when (result.status) {
            RenameStatus.SUCCESS -> {
                val expanded = captureExpandedFolderPaths().toMutableSet()
                if (targetFolder.isNotEmpty()) expanded.add(targetFolder)
                expanded.add(result.folderPath)
                reloadTreeWithExpandedSet(expanded, selectPath = result.folderPath, selectIsFolder = true)
                setFooterStatus(I18nManager.t("explorer.status.createdFolder", ScenarioFileRepository.formatDisplayPath(result.folderPath)))
            }

            RenameStatus.INVALID_NAME -> {
                Messages.showWarningDialog(project, I18nManager.t("explorer.validation.forbidden"), I18nManager.t("explorer.prompt.newFolder.title"))
            }

            RenameStatus.ALREADY_EXISTS -> {
                Messages.showWarningDialog(project, I18nManager.t("explorer.validation.duplicate", input), I18nManager.t("explorer.prompt.newFolder.title"))
            }

            RenameStatus.FAILED -> {
                Messages.showErrorDialog(project, I18nManager.t("dialog.rename.failed.msg"), I18nManager.t("dialog.error.title"))
            }
        }
    }

    private fun promptCreateScenario() {
        if (!flushEditsBeforeAction()) return
        val targetFolder = targetFolderForCreation()
        val locLabel = targetFolder.ifBlank { I18nManager.t("explorer.location.root") }
        val input = Messages.showInputDialog(
            project,
            I18nManager.t("explorer.prompt.newScenario.msg", locLabel),
            I18nManager.t("explorer.prompt.newScenario.title"),
            Messages.getQuestionIcon()
        )?.trim()
        if (input.isNullOrBlank()) return

        val result = storage.createScenario(targetFolder, input, defaultBaseUrlProvider())
        when (result.status) {
            RenameStatus.SUCCESS -> {
                val expanded = captureExpandedFolderPaths().toMutableSet()
                if (targetFolder.isNotEmpty()) expanded.add(targetFolder)
                reloadTreeWithExpandedSet(expanded, selectPath = result.newRelativePath, selectIsFolder = false)
                setFooterStatus(I18nManager.t("explorer.status.createdScenario", ScenarioFileRepository.formatDisplayPath(result.newRelativePath)))
            }

            RenameStatus.INVALID_NAME -> {
                Messages.showWarningDialog(project, I18nManager.t("explorer.validation.forbidden"), I18nManager.t("explorer.prompt.newScenario.title"))
            }

            RenameStatus.ALREADY_EXISTS -> {
                Messages.showWarningDialog(project, I18nManager.t("explorer.validation.duplicate", input), I18nManager.t("explorer.prompt.newScenario.title"))
            }

            RenameStatus.FAILED -> {
                Messages.showErrorDialog(project, I18nManager.t("dialog.rename.failed.msg"), I18nManager.t("dialog.error.title"))
            }
        }
    }

    private fun duplicateSelectedScenario() {
        if (!flushEditsBeforeAction()) return
        val selected = getSelectedNodeData() as? ExplorerNodeData.Scenario ?: return
        val expanded = captureExpandedFolderPaths()
        val result = storage.duplicateScenarioAtPath(selected.info.relativePath)
        if (result.status == RenameStatus.SUCCESS) {
            reloadTreeWithExpandedSet(expanded, selectPath = result.newRelativePath, selectIsFolder = false)
            setFooterStatus(I18nManager.t("explorer.status.duplicated", ScenarioFileRepository.formatDisplayPath(result.newRelativePath)))
        }
    }

    private fun deleteSelectedNode() {
        val selected = getSelectedNodeData() ?: return
        val expanded = captureExpandedFolderPaths().toMutableSet()
        when (selected) {
            is ExplorerNodeData.Folder -> {
                val confirm = Messages.showYesNoDialog(
                    project,
                    I18nManager.t("explorer.deleteFolder.msg", selected.info.name, selected.info.scenarioCount),
                    I18nManager.t("explorer.deleteFolder.title"),
                    Messages.getWarningIcon()
                )
                if (confirm != Messages.YES) return
                val path = selected.info.relativePath
                if (storage.deleteFolder(path)) {
                    if (trackedActiveScenarioPath == path || trackedActiveScenarioPath.startsWith("$path/")) {
                        activeScenarioWasDeleted = true
                    }
                    expanded.removeAll { it == path || it.startsWith("$path/") }
                    isFormDirty = false
                    applyDialogAction.isEnabled = false
                    reloadTreeWithExpandedSet(expanded, selectPath = null, selectIsFolder = false)
                }
            }

            is ExplorerNodeData.Scenario -> {
                val confirm = Messages.showYesNoDialog(
                    project,
                    I18nManager.t("explorer.deleteScenario.msg", ScenarioFileRepository.formatDisplayPath(selected.info.relativePath)),
                    I18nManager.t("explorer.deleteScenario.title"),
                    Messages.getWarningIcon()
                )
                if (confirm != Messages.YES) return
                val path = selected.info.relativePath
                if (storage.deleteScenario(path)) {
                    if (trackedActiveScenarioPath == path) {
                        activeScenarioWasDeleted = true
                    }
                    isFormDirty = false
                    applyDialogAction.isEnabled = false
                    reloadTreeWithExpandedSet(expanded, selectPath = null, selectIsFolder = false)
                }
            }

            else -> Unit
        }
    }

    private fun exportSelectedScenario() {
        if (!flushEditsBeforeAction()) return
        val selected = getSelectedNodeData() as? ExplorerNodeData.Scenario ?: return
        val loaded = storage.loadScenario(selected.info.relativePath) ?: return
        val saverDescriptor = FileSaverDescriptor(
            I18nManager.t("dialog.export.title"),
            I18nManager.t("dialog.export.desc"),
            "json"
        )
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(saverDescriptor, project)
        val wrapper = saveDialog.save("${selected.info.name}.json") ?: return
        try {
            storage.exportScenario(loaded, wrapper.file)
            setFooterStatus(I18nManager.t("explorer.status.exported", wrapper.file.name))
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.export.errMsg", e.message ?: ""),
                I18nManager.t("dialog.error.title")
            )
        }
    }

    private fun importScenarioToSelectedLocation() {
        if (!flushEditsBeforeAction()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").apply {
            title = I18nManager.t("dialog.import.title")
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val targetFolder = targetFolderForCreation()
        try {
            val (relPath, _) = storage.importScenario(File(chosen.path), targetFolder)
            val expanded = captureExpandedFolderPaths().toMutableSet()
            if (targetFolder.isNotEmpty()) expanded.add(targetFolder)
            reloadTreeWithExpandedSet(expanded, selectPath = relPath, selectIsFolder = false)
            setFooterStatus(I18nManager.t("explorer.status.imported", ScenarioFileRepository.formatDisplayPath(relPath)))
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                I18nManager.t("dialog.import.errMsg", e.message ?: ""),
                I18nManager.t("dialog.error.title")
            )
        }
    }

    // --- Tree Model Construction & Expansion Preservation ---

    private fun reloadTree(preserveExpanded: Boolean, selectPath: String?, selectIsFolder: Boolean) {
        val expanded = if (preserveExpanded) captureExpandedFolderPaths() else emptySet()
        reloadTreeWithExpandedSet(expanded, selectPath, selectIsFolder)
    }

    private fun reloadTreeWithExpandedSet(
        expandedFolderPaths: Set<String>,
        selectPath: String?,
        selectIsFolder: Boolean
    ) {
        currentSnapshot = storage.scanTreeSnapshot()

        isUpdatingSelection = true
        try {
            rootNode.removeAllChildren()
            buildChildrenInto(rootNode, parentPath = "")
            treeModel.reload(rootNode)
            restoreExpandedFolderPaths(expandedFolderPaths)
            if (!selectPath.isNullOrBlank()) {
                selectNodeInTree(selectPath, selectIsFolder)
            } else {
                tree.clearSelection()
            }
        } finally {
            isUpdatingSelection = false
        }
        populateBaseInfoForm(getSelectedNodeData())
    }

    private fun buildChildrenInto(parentNode: DefaultMutableTreeNode, parentPath: String) {
        val childFolders = currentSnapshot.folders.filter { it.parentPath == parentPath }
        val childScenarios = currentSnapshot.scenarios.filter { it.folderPath == parentPath }
        val customOrder = currentSnapshot.folderOrders[parentPath].orEmpty()

        data class ChildEntry(val token: String, val isFolder: Boolean, val name: String, val node: DefaultMutableTreeNode)

        val entries = mutableListOf<ChildEntry>()
        for (folder in childFolders) {
            val node = DefaultMutableTreeNode(ExplorerNodeData.Folder(folder))
            buildChildrenInto(node, folder.relativePath)
            entries.add(ChildEntry(ScenarioFileRepository.folderOrderToken(folder.name), true, folder.name, node))
        }
        for (scenario in childScenarios) {
            val node = DefaultMutableTreeNode(ExplorerNodeData.Scenario(scenario))
            entries.add(ChildEntry(ScenarioFileRepository.scenarioOrderToken(scenario.name), false, scenario.name, node))
        }

        val orderIndex = customOrder.withIndex().associate { (idx, token) -> token to idx }
        val sorted = entries.sortedWith(
            compareBy<ChildEntry>(
                { orderIndex[it.token] ?: Int.MAX_VALUE },
                { if (it.isFolder) 0 else 1 },
                { it.name.lowercase() }
            )
        )
        for (entry in sorted) {
            parentNode.add(entry.node)
        }
    }

    private fun captureExpandedFolderPaths(): Set<String> {
        val result = linkedSetOf<String>()
        val enum = tree.getExpandedDescendants(TreePath(rootNode)) ?: return result
        while (enum.hasMoreElements()) {
            val tp = enum.nextElement()
            val data = (tp.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            if (data is ExplorerNodeData.Folder) {
                result.add(data.info.relativePath)
            }
        }
        return result
    }

    private fun restoreExpandedFolderPaths(expandedPaths: Set<String>) {
        if (expandedPaths.isEmpty()) return
        fun visit(node: DefaultMutableTreeNode) {
            val data = node.userObject
            if (data is ExplorerNodeData.Folder && data.info.relativePath in expandedPaths) {
                tree.expandPath(TreePath(node.path))
            }
            for (i in 0 until node.childCount) {
                visit(node.getChildAt(i) as DefaultMutableTreeNode)
            }
        }
        visit(rootNode)
    }

    private fun expandAllFolders() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun collapseAllFolders() {
        for (row in tree.rowCount - 1 downTo 0) {
            tree.collapseRow(row)
        }
    }

    private fun selectNodeInTree(relativePath: String?, isFolder: Boolean) {
        if (relativePath.isNullOrBlank()) return
        val found = findTreeNode(rootNode, relativePath, isFolder) ?: return
        val treePath = TreePath(found.path)
        treePath.parentPath?.let { tree.expandPath(it) }
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
    }

    private fun findTreeNode(
        current: DefaultMutableTreeNode,
        relativePath: String,
        isFolder: Boolean
    ): DefaultMutableTreeNode? {
        val data = current.userObject
        if (isFolder && data is ExplorerNodeData.Folder && data.info.relativePath == relativePath) return current
        if (!isFolder && data is ExplorerNodeData.Scenario && data.info.relativePath == relativePath) return current
        for (i in 0 until current.childCount) {
            val match = findTreeNode(current.getChildAt(i) as DefaultMutableTreeNode, relativePath, isFolder)
            if (match != null) return match
        }
        return null
    }

    private fun getSelectedNodeData(): ExplorerNodeData? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val data = node.userObject as? ExplorerNodeData ?: return null
        return if (data is ExplorerNodeData.Root) null else data
    }

    private fun nodePathOf(data: ExplorerNodeData?): String? = when (data) {
        is ExplorerNodeData.Folder -> data.info.relativePath
        is ExplorerNodeData.Scenario -> data.info.relativePath
        else -> null
    }

    private fun nodeIdentity(data: ExplorerNodeData?): String? = when (data) {
        is ExplorerNodeData.Folder -> "F:${data.info.relativePath}"
        is ExplorerNodeData.Scenario -> "S:${data.info.relativePath}"
        else -> null
    }

    private fun setFooterStatus(message: String, isWarning: Boolean = false) {
        footerStatusLabel.text = message
        footerStatusLabel.foreground = if (isWarning) {
            JBColor(Color(0xE6, 0x51, 0x00), Color(0xFF, 0xB7, 0x4D))
        } else {
            JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
        }
    }

    // --- ColoredTreeCellRenderer (Method Badge + Folder Count) ---

    private inner class ExplorerTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val data = node.userObject) {
                is ExplorerNodeData.Folder -> {
                    icon = AllIcons.Nodes.Folder
                    append(data.info.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${data.info.scenarioCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (data.info.description.isNotBlank()) {
                        toolTipText = "${data.info.name} — ${data.info.description}"
                    } else {
                        toolTipText = data.info.name
                    }
                }

                is ExplorerNodeData.Scenario -> {
                    icon = AllIcons.FileTypes.Json
                    val method = data.info.primaryMethod
                    if (!method.isNullOrBlank()) {
                        val methodColor = methodBadgeColor(method)
                        append(
                            "[$method] ",
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor)
                        )
                    }
                    val isActive = data.info.relativePath == trackedActiveScenarioPath
                    append(
                        data.info.name,
                        if (isActive) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
                    )
                    if (isActive) {
                        append("  (${I18nManager.t("explorer.activeTag")})", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
                    }
                    if (!data.info.primaryUrl.isNullOrBlank()) {
                        append("  ${data.info.primaryUrl}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    toolTipText = buildString {
                        append(ScenarioFileRepository.formatDisplayPath(data.info.relativePath))
                        if (data.info.description.isNotBlank()) {
                            append(" — ").append(data.info.description)
                        }
                    }
                }

                else -> Unit
            }
        }

        private fun methodBadgeColor(method: String): Color = when (method.uppercase()) {
            "GET" -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x6C, 0xD9, 0x7E))
            "POST" -> JBColor(Color(0x15, 0x65, 0xC0), Color(0x64, 0xB5, 0xF6))
            "PUT" -> JBColor(Color(0xEF, 0x6C, 0x00), Color(0xFF, 0xB7, 0x4D))
            "DELETE" -> JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
            "PATCH" -> JBColor(Color(0x6A, 0x1B, 0x9A), Color(0xCE, 0x93, 0xD8))
            else -> JBColor.GRAY
        }
    }

    // --- Drag & Drop TransferHandler (Folders & Scenarios, Empty-Area Root Drop, Circular Guard) ---

    private inner class ExplorerTransferHandler : TransferHandler() {
        private val nodeFlavor = DataFlavor(ExplorerDragPayload::class.java, "MaekomExplorerNode")

        override fun getSourceActions(c: JComponent?): Int = MOVE

        override fun createTransferable(c: JComponent?): Transferable? {
            val selected = getSelectedNodeData() ?: return null
            val payload = when (selected) {
                is ExplorerNodeData.Folder -> ExplorerDragPayload(isFolder = true, relativePath = selected.info.relativePath)
                is ExplorerNodeData.Scenario -> ExplorerDragPayload(isFolder = false, relativePath = selected.info.relativePath)
                else -> return null
            }
            return object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(nodeFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == nodeFlavor
                override fun getTransferData(flavor: DataFlavor?): Any {
                    if (flavor != nodeFlavor) throw UnsupportedFlavorException(flavor)
                    return payload
                }
            }
        }

        private fun resolveDropTarget(dl: JTree.DropLocation): Pair<String, Int?> {
            val dropPath = dl.path
            // Drop on empty space below tree -> Root folder (end)
            if (dropPath == null) {
                return "" to null
            }
            val targetNode = dropPath.lastPathComponent as? DefaultMutableTreeNode ?: return "" to null
            val childIndex = dl.childIndex

            return when (val targetData = targetNode.userObject) {
                is ExplorerNodeData.Root -> {
                    "" to if (childIndex >= 0) childIndex else null
                }

                is ExplorerNodeData.Folder -> {
                    targetData.info.relativePath to if (childIndex >= 0) childIndex else null
                }

                is ExplorerNodeData.Scenario -> {
                    // Dropping onto a scenario places the dragged item into the same parent folder right after it
                    val parentNode = targetNode.parent as? DefaultMutableTreeNode
                    val idxInParent = parentNode?.getIndex(targetNode)?.let { it + 1 }
                    targetData.info.folderPath to idxInParent
                }

                else -> "" to null
            }
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(nodeFlavor)) return false
            val dl = support.dropLocation as? JTree.DropLocation ?: return false
            val payload = runCatching { support.transferable.getTransferData(nodeFlavor) as? ExplorerDragPayload }.getOrNull()
                ?: return false

            val (targetFolder, insertIndex) = resolveDropTarget(dl)

            // Guard against circular folder drop (folder dropped onto itself or inside its descendant)
            if (payload.isFolder) {
                if (targetFolder == payload.relativePath || targetFolder.startsWith("${payload.relativePath}/")) {
                    return false
                }
            }

            // If dropping directly ON the same parent without reordering index, still allow if moving out/in; block self-drop
            val sourceParent = ScenarioFileRepository.parentFolderOf(payload.relativePath)
            if (sourceParent == targetFolder && insertIndex == null && dl.path != null) {
                val targetObj = (dl.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                if (payload.isFolder && targetObj is ExplorerNodeData.Folder && targetObj.info.relativePath == payload.relativePath) {
                    return false
                }
                if (!payload.isFolder && targetObj is ExplorerNodeData.Scenario && targetObj.info.relativePath == payload.relativePath) {
                    return false
                }
            }

            support.setShowDropLocation(true)
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            if (!flushEditsBeforeAction()) return false

            val dl = support.dropLocation as? JTree.DropLocation ?: return false
            val payload = runCatching { support.transferable.getTransferData(nodeFlavor) as? ExplorerDragPayload }.getOrNull()
                ?: return false

            val (targetFolder, insertIndex) = resolveDropTarget(dl)
            val sourceParent = ScenarioFileRepository.parentFolderOf(payload.relativePath)
            val expanded = captureExpandedFolderPaths().toMutableSet()

            if (payload.isFolder) {
                val moveResult = storage.moveFolder(payload.relativePath, targetFolder, autoRenameOnCollision = true)
                when (moveResult.status) {
                    MoveStatus.CIRCULAR_MOVE -> {
                        setFooterStatus(I18nManager.t("explorer.status.circularBlocked"), isWarning = true)
                        return false
                    }

                    MoveStatus.SUCCESS -> {
                        val newPath = moveResult.newRelativePath
                        if (payload.relativePath != newPath) {
                            trackedActiveScenarioPath = ScenarioFileRepository.remapPathAfterFolderChange(
                                trackedActiveScenarioPath,
                                payload.relativePath,
                                newPath
                            )
                        }
                        val remappedExpanded = expanded.map {
                            ScenarioFileRepository.remapPathAfterFolderChange(it, payload.relativePath, newPath)
                        }.toMutableSet()
                        if (targetFolder.isNotEmpty()) remappedExpanded.add(targetFolder)

                        applySiblingReorderIfNeeded(
                            parentFolderPath = targetFolder,
                            movedToken = ScenarioFileRepository.folderOrderToken(ScenarioFileRepository.baseNameOf(newPath)),
                            insertIndex = insertIndex,
                            sameParent = (sourceParent == targetFolder)
                        )
                        reloadTreeWithExpandedSet(remappedExpanded, selectPath = newPath, selectIsFolder = true)
                        if (moveResult.renamedDueToCollision) {
                            setFooterStatus(
                                I18nManager.t("explorer.status.movedRenamed", ScenarioFileRepository.formatDisplayPath(newPath)),
                                isWarning = true
                            )
                        } else if (sourceParent != targetFolder) {
                            setFooterStatus(I18nManager.t("explorer.status.moved", ScenarioFileRepository.formatDisplayPath(newPath)))
                        }
                        return true
                    }

                    else -> return false
                }
            } else {
                val moveResult = storage.moveScenario(payload.relativePath, targetFolder, autoRenameOnCollision = true)
                if (moveResult.status != MoveStatus.SUCCESS) return false

                val newPath = moveResult.newRelativePath
                if (trackedActiveScenarioPath == payload.relativePath) {
                    trackedActiveScenarioPath = newPath
                    if (moveResult.renamedDueToCollision) {
                        activeScenarioMetadataUpdated = true
                    }
                }
                if (targetFolder.isNotEmpty()) expanded.add(targetFolder)

                applySiblingReorderIfNeeded(
                    parentFolderPath = targetFolder,
                    movedToken = ScenarioFileRepository.scenarioOrderToken(ScenarioFileRepository.baseNameOf(newPath)),
                    insertIndex = insertIndex,
                    sameParent = (sourceParent == targetFolder)
                )
                reloadTreeWithExpandedSet(expanded, selectPath = newPath, selectIsFolder = false)
                if (moveResult.renamedDueToCollision) {
                    setFooterStatus(
                        I18nManager.t("explorer.status.movedRenamed", ScenarioFileRepository.formatDisplayPath(newPath)),
                        isWarning = true
                    )
                } else if (sourceParent != targetFolder) {
                    setFooterStatus(I18nManager.t("explorer.status.moved", ScenarioFileRepository.formatDisplayPath(newPath)))
                }
                return true
            }
        }

        private fun applySiblingReorderIfNeeded(
            parentFolderPath: String,
            movedToken: String,
            insertIndex: Int?,
            sameParent: Boolean
        ) {
            if (insertIndex == null) return
            val parentTreeNode = if (parentFolderPath.isEmpty()) {
                rootNode
            } else {
                findTreeNode(rootNode, parentFolderPath, isFolder = true) ?: return
            }
            val currentTokens = mutableListOf<String>()
            for (i in 0 until parentTreeNode.childCount) {
                val child = (parentTreeNode.getChildAt(i) as DefaultMutableTreeNode).userObject
                when (child) {
                    is ExplorerNodeData.Folder -> currentTokens.add(ScenarioFileRepository.folderOrderToken(child.info.name))
                    is ExplorerNodeData.Scenario -> currentTokens.add(ScenarioFileRepository.scenarioOrderToken(child.info.name))
                    else -> Unit
                }
            }
            val oldIndex = currentTokens.indexOf(movedToken)
            if (sameParent && oldIndex >= 0) {
                currentTokens.removeAt(oldIndex)
                val adjustedIdx = if (oldIndex < insertIndex) (insertIndex - 1) else insertIndex
                currentTokens.add(adjustedIdx.coerceIn(0, currentTokens.size), movedToken)
            } else {
                currentTokens.remove(movedToken)
                currentTokens.add(insertIndex.coerceIn(0, currentTokens.size), movedToken)
            }
            storage.updateSiblingOrder(parentFolderPath, currentTokens)
        }
    }
}
