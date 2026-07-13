package eu.kennytv.dependencymanager.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import eu.kennytv.dependencymanager.apply.UpdateApplier
import eu.kennytv.dependencymanager.ignore.IgnoreRule
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.CheckResult
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate
import eu.kennytv.dependencymanager.model.UpdateType
import eu.kennytv.dependencymanager.resolve.Versions
import eu.kennytv.dependencymanager.service.DependencyUpdateService
import java.awt.BorderLayout
import java.nio.file.Files
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

class DependencyUpdatesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = DependencyUpdateService.getInstance(project)
    private val rootNode = CheckedTreeNode(null)
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree: CheckboxTree
    private val changelogHeader = JBLabel()
    private val changelogView = ChangelogView()
    private val statusLabel = JBLabel()

    private var commitAfterApply = true
    private var verifyBeforeCommit = true
    private var currentCandidates: List<UpdateCandidate> = emptyList()
    private var checking = false

    init {
        tree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer(true) {
            override fun customizeRenderer(
                tree: JTree,
                value: Any,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val node = value as? CheckedTreeNode ?: return
                when (val userObject = node.userObject) {
                    is String -> textRenderer.append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    is UpdateCandidate -> {
                        textRenderer.append(userObject.dependency.displayName)
                        val attributes = if (userObject.updateType == UpdateType.MAJOR) {
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
                        } else {
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        }
                        textRenderer.append(
                            "  ${userObject.dependency.currentVersion} → ${userObject.newVersion}",
                            attributes,
                        )
                    }
                }
            }
        }, rootNode, CheckboxTreeBase.CheckPolicy(true, true, true, true))
        tree.model = treeModel
        tree.isRootVisible = false
        tree.emptyText.text = "Press the refresh button to check for dependency updates"
        tree.selectionModel.addTreeSelectionListener {
            selectedCandidate()?.let { showChangelog(it) }
        }
        PopupHandler.installPopupMenu(tree, buildPopupActions(), "DependencyManagerTreePopup")

        Disposer.register(this, changelogView)
        changelogHeader.border = JBUI.Borders.empty(6, 8)

        val detailPanel = JPanel(BorderLayout()).apply {
            add(changelogHeader, BorderLayout.NORTH)
            add(changelogView.component, BorderLayout.CENTER)
        }

        // Vertical split
        val splitter = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = detailPanel
        }
        statusLabel.border = JBUI.Borders.empty(4, 8)

        val content = JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        setContent(content)

        val actionToolbar =
            ActionManager.getInstance().createActionToolbar("DependencyManagerToolbar", buildToolbarActions(), true)
        actionToolbar.targetComponent = this
        toolbar = actionToolbar.component
    }

    private fun buildToolbarActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction(
            "Check for Updates",
            "Scan the project and check for dependency updates",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !checking
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(object : DumbAwareAction(
            "Apply Selected Updates",
            "Write the checked updates to the project files",
            AllIcons.Actions.Execute
        ) {
            override fun actionPerformed(e: AnActionEvent) = applySelected()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !checking && currentCandidates.isNotEmpty()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(object : DumbAwareToggleAction(
            "Commit After Apply",
            "Record all applied updates as a single git commit",
            AllIcons.Vcs.CommitNode
        ) {
            override fun isSelected(e: AnActionEvent) = commitAfterApply
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                commitAfterApply = state
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(object : DumbAwareToggleAction(
            "Verify Before Commit",
            "Run 'gradlew build' after applying; skip the commit if it fails",
            AllIcons.RunConfigurations.TestPassed
        ) {
            override fun isSelected(e: AnActionEvent) = verifyBeforeCommit
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                verifyBeforeCommit = state
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Select All", "Check every update", AllIcons.Actions.Selectall) {
            override fun actionPerformed(e: AnActionEvent) = setAllChecked(true)
        })
        group.add(object : DumbAwareAction("Unselect All", "Uncheck every update", AllIcons.Actions.Unselectall) {
            override fun actionPerformed(e: AnActionEvent) = setAllChecked(false)
        })
        group.addSeparator()
        group.add(object :
            DumbAwareAction("Open Ignore Rules", "Open ${IgnoreRules.FILE_NAME}", AllIcons.General.Filter) {
            override fun actionPerformed(e: AnActionEvent) = openIgnoreFile()
        })
        return group
    }

    private fun buildPopupActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Ignore This Dependency") {
            override fun actionPerformed(e: AnActionEvent) {
                val candidate = selectedCandidate() ?: return
                addIgnoreRule(IgnoreRule(candidate.dependency.matchKeys.first()))
            }
        })
        group.add(object : DumbAwareAction("Ignore This Version") {
            override fun actionPerformed(e: AnActionEvent) {
                val candidate = selectedCandidate() ?: return
                addIgnoreRule(
                    IgnoreRule(
                        candidate.dependency.matchKeys.first(),
                        versions = listOf(Versions.normalize(candidate.newVersion))
                    )
                )
            }
        })
        group.add(object : DumbAwareAction("Ignore Major Updates") {
            override fun actionPerformed(e: AnActionEvent) {
                val candidate = selectedCandidate() ?: return
                addIgnoreRule(IgnoreRule(candidate.dependency.matchKeys.first(), type = UpdateType.MAJOR))
            }
        })
        return group
    }

    private fun selectedCandidate(): UpdateCandidate? =
        (tree.lastSelectedPathComponent as? CheckedTreeNode)?.userObject as? UpdateCandidate

    private fun setAllChecked(checked: Boolean) {
        fun walk(node: CheckedTreeNode) {
            node.isChecked = checked
            for (child in node.children()) {
                (child as? CheckedTreeNode)?.let { walk(it) }
            }
        }
        walk(rootNode)
        tree.repaint()
    }

    fun refresh() {
        if (checking) return
        checking = true
        statusLabel.text = "Checking for updates…"
        // Tool window content can be created in a write-unsafe context (project open), and
        // saveAllDocuments is a model change; invokeLater with a concrete modality gets us
        // to a write-safe one (see TransactionGuard docs).
        ApplicationManager.getApplication().invokeLater({ startCheck() }, ModalityState.nonModal())
    }

    private fun startCheck() {
        if (project.isDisposed) {
            checking = false
            return
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, "Checking dependency updates", true) {
            private var result: CheckResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = service.check(indicator)
            }

            override fun onSuccess() {
                result?.let { renderResult(it) }
            }

            override fun onFinished() {
                checking = false
                if (result == null) statusLabel.text = "Check cancelled"
            }
        }.queue()
    }

    private fun renderResult(result: CheckResult) {
        currentCandidates = result.updates
        rootNode.removeAllChildren()
        for ((label, candidates) in result.updates.groupBy { it.dependency.groupLabel }) {
            val groupNode = CheckedTreeNode("$label (${candidates.size})")
            candidates.forEach { groupNode.add(CheckedTreeNode(it)) }
            rootNode.add(groupNode)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
        tree.emptyText.text = "Everything is up to date"

        val parts = mutableListOf(
            "${result.updates.size} update${if (result.updates.size == 1) "" else "s"}",
            "${result.upToDateCount} up to date"
        )
        if (result.skipped.isNotEmpty()) parts += "${result.skipped.size} skipped"
        if (result.errors.isNotEmpty()) parts += "${result.errors.size} failed"
        statusLabel.text = parts.joinToString(" · ")
        statusLabel.toolTipText = buildString {
            append("<html>")
            result.skipped.forEach { append("Skipped ${it.dependency.displayName}: ${it.reason}<br>") }
            result.errors.forEach { append("Error: $it<br>") }
            append("</html>")
        }.takeIf { result.skipped.isNotEmpty() || result.errors.isNotEmpty() }

        if (result.errors.isNotEmpty() && !service.hasGitHubToken) {
            notify(
                "GitHub rate limit?",
                "${result.errors.size} lookups failed and no GitHub token was found. Set GITHUB_TOKEN or log in with the gh CLI to raise API limits.",
                NotificationType.WARNING,
            )
        }
    }

    private fun showChangelog(candidate: UpdateCandidate) {
        changelogHeader.text =
            "<html><b>${candidate.dependency.displayName}</b> &nbsp; ${candidate.dependency.currentVersion} → ${candidate.newVersion}</html>"
        changelogView.showLoading()
        val changelogService = service.changelogService ?: return
        AppExecutorUtil.getAppExecutorService().submit {
            val changelog = changelogService.fetch(candidate)
            ApplicationManager.getApplication().invokeLater {
                if (selectedCandidate() != candidate) return@invokeLater
                changelogView.show(changelog)
            }
        }
    }

    override fun dispose() {}

    private fun addIgnoreRule(rule: IgnoreRule) {
        val root = service.projectRoot ?: return
        val added = IgnoreRules.append(root, rule)
        // Drop every entry the rule now covers; the same dependency can be listed more than
        // once (inline in one module, via the catalog in another)
        currentCandidates = currentCandidates.filterNot {
            rule.matches(it.dependency) && rule.ignoresCandidate(Versions.normalize(it.newVersion), it.updateType)
        }
        val result = service.lastResult
        if (result != null) renderResult(result.copy(updates = currentCandidates))
        if (added) {
            notify(
                "Ignore rule added",
                "Added to ${IgnoreRules.FILE_NAME}: ${rule.dependency}",
                NotificationType.INFORMATION
            )
        } else {
            notify(
                "Ignore rule already exists",
                "${IgnoreRules.FILE_NAME} already contains this rule.",
                NotificationType.INFORMATION
            )
        }
    }

    private fun openIgnoreFile() {
        val root = service.projectRoot ?: return
        val path = IgnoreRules.filePath(root)
        if (!Files.exists(path)) {
            Files.writeString(path, IgnoreRules.FILE_HEADER + "ignore = [\n]\n")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }

    private fun applySelected() {
        val selected = tree.getCheckedNodes(UpdateCandidate::class.java, null).toList()
        if (selected.isEmpty()) {
            notify("Nothing selected", "Check at least one update to apply.", NotificationType.INFORMATION)
            return
        }
        val root = service.projectRoot ?: return
        val shouldCommit = commitAfterApply
        val shouldVerify = verifyBeforeCommit
        FileDocumentManager.getInstance().saveAllDocuments()

        object : Task.Backgroundable(project, "Applying dependency updates", true) {
            private var applyResult: UpdateApplier.ApplyResult? = null
            private var wrapperResult: UpdateApplier.ExecResult? = null
            private var verifyResult: UpdateApplier.ExecResult? = null
            private var commitResult: UpdateApplier.ExecResult? = null

            override fun run(indicator: ProgressIndicator) {
                val applier = UpdateApplier(root)
                indicator.text = "Applying updates…"
                val result = applier.apply(selected)
                applyResult = result
                LocalFileSystem.getInstance().refreshNioFiles(result.changedFiles)
                if (result.applied.isEmpty()) return
                val filesToCommit = result.changedFiles.toMutableSet()

                if (result.applied.any { it.dependency is ScannedDependency.GradleWrapper }) {
                    indicator.text = "Refreshing wrapper files (gradlew wrapper)…"
                    wrapperResult = applier.runGradle(listOf("wrapper")) { indicator.isCanceled }
                    for (name in listOf(
                        "gradlew",
                        "gradlew.bat",
                        "gradle/wrapper/gradle-wrapper.jar",
                        "gradle/wrapper/gradle-wrapper.properties"
                    )) {
                        val path = root.resolve(name)
                        if (Files.exists(path)) filesToCommit.add(path)
                    }
                    LocalFileSystem.getInstance().refreshNioFiles(filesToCommit)
                }

                if (shouldVerify) {
                    indicator.text = "Verifying (gradlew build)…"
                    verifyResult = applier.runGradle(listOf("build")) { indicator.isCanceled }
                }

                val verified = verifyResult?.success != false
                if (shouldCommit && verified) {
                    indicator.text = "Committing…"
                    val message = buildString {
                        append("Update dependencies\n\n")
                        result.applied.forEach {
                            append("- ${it.dependency.displayName} ${it.dependency.currentVersion} -> ${it.newVersion}\n")
                        }
                    }
                    commitResult = applier.commit(filesToCommit, message.trimEnd())
                }
            }

            override fun onSuccess() {
                val result = applyResult ?: return
                val message = buildString {
                    append("${result.applied.size} update${if (result.applied.size == 1) "" else "s"} applied")
                    wrapperResult?.let {
                        if (it.success) append(", wrapper files refreshed")
                        else append("<br><code>gradlew wrapper</code> failed: ${it.output.takeLast(300)}")
                    }
                    verifyResult?.let {
                        if (it.success) append(", build verified")
                        else append(
                            "<br><b>Verification failed, commit skipped.</b> Changes are applied but uncommitted.<br><code>${
                                it.output.takeLast(
                                    400
                                )
                            }</code>"
                        )
                    }
                    commitResult?.let {
                        if (it.success) append(", committed")
                        else append("<br>git commit failed: ${it.output.takeLast(300)}")
                    }
                    result.failed.forEach { (candidate, reason) ->
                        append("<br>Failed: ${candidate.dependency.displayName} ($reason)")
                    }
                }
                val anyFailure = result.failed.isNotEmpty() ||
                    wrapperResult?.success == false ||
                    verifyResult?.success == false ||
                    commitResult?.success == false
                notify(
                    "Dependency updates",
                    message,
                    if (anyFailure) NotificationType.WARNING else NotificationType.INFORMATION
                )
                refresh()
            }
        }.queue()
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Dependency Manager")
            .createNotification(title, content, type)
            .notify(project)
    }
}
