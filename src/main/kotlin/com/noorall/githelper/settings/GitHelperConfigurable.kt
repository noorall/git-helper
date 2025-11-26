/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.noorall.githelper.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.components.DslLabel
import com.noorall.githelper.git.GitHooksManager
import com.noorall.githelper.logging.GitHelperLogger
import javax.swing.JComponent

class GitHelperConfigurable : Configurable {

    private var settingsPanel: DialogPanel? = null
    private val settings = GitHelperSettings.getInstance()
    private var statusLabel: javax.swing.JLabel? = null

    override fun getDisplayName(): String = "GitHelper"

    override fun createComponent(): JComponent {
        settingsPanel = panel {
            group("Integration Method") {
                row {
                    comment("Choose how GitHelper should integrate with your Git workflow:")
                }

                buttonsGroup {
                    row {
                        radioButton("Git Pre-commit Hook with Async Communication (Recommended)", true)
                            .comment("IDEA plugin runs Spotless in background, Git hook monitors status file - no UI blocking!")
                    }

                    row {
                        radioButton("IntelliJ Commit Handler (Synchronous)", false)
                            .comment("Traditional synchronous mode - may block UI during formatting")
                    }
                }.bind({ settings.useGitHooks }, {
                    settings.useGitHooks = it
                    settings.useIntellijHandler = !it
                })

                }

            group("Git Hooks Configuration") {
                row {
                    checkBox("Auto-install hooks for new projects")
                        .bindSelected(settings::autoInstallHooksForNewProjects)
                        .comment("Automatically install Git hooks when opening new Git repositories")
                }

                row("Hook timeout (seconds):") {
                    intTextField(range = 30..600)
                        .bindIntText(settings::hookTimeout)
                        .columns(COLUMNS_SHORT)
                        .comment("Timeout for pre-commit hook execution (30-600 seconds, default: 60)")
                }

                row {
                    button("Configure Git Hooks...") {
                        configureGitHooks()
                    }.comment("Manage Git pre-commit hooks for current project")
                }

                row {
                    statusLabel = label(getGitHooksStatus()).component
                }
            }

            group("Spotless Integration") {
                row {
                    checkBox("Enable Spotless formatting on commit")
                        .bindSelected(settings::spotlessEnabled)
                        .comment("Automatically run 'mvn spotless:apply' on changed files before commit")
                }
                row {
                    checkBox("Show progress dialog")
                        .bindSelected(settings::showProgressDialog)
                        .comment("Display progress indicator while running Spotless (IntelliJ handler mode only)")
                }
                row("Maven executable:") {
                    textField()
                        .bindText(settings::mavenExecutable)
                        .columns(COLUMNS_MEDIUM)
                        .comment("Path to Maven executable (default: mvn)")
                }
            }
        }
        return settingsPanel!!
    }

    private fun configureGitHooks() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            Messages.showErrorDialog(
                "No project is currently open. Please open a project first.",
                "Configure Git Hooks"
            )
            return
        }

        val hooksManager = GitHooksManager(project)
        val status = hooksManager.getHookStatus()

        when (status) {
            GitHooksManager.HookStatus.NO_GIT_REPO -> {
                Messages.showErrorDialog(
                    "Project '${project.name}' is not a Git repository. Git hooks can only be configured in Git repositories.",
                    "Git Hooks Configuration"
                )
            }
            GitHooksManager.HookStatus.NOT_INSTALLED -> {
                showInstallDialog(project, hooksManager)
            }
            GitHooksManager.HookStatus.INSTALLED -> {
                showManageDialog(project, hooksManager)
            }
            GitHooksManager.HookStatus.OTHER_HOOK_EXISTS -> {
                showReplaceDialog(project, hooksManager)
            }
        }
    }

    private fun showInstallDialog(project: com.intellij.openapi.project.Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoDialog(
            "No Git pre-commit hook is currently installed in project '${project.name}'.\n\n" +
                    "Would you like to install the GitHelper Spotless pre-commit hook?\n\n" +
                    "This will automatically format Java files using Spotless before each commit.",
            "Install Git Pre-commit Hook",
            "Install Hook",
            "Cancel",
            Messages.getQuestionIcon()
        )

        if (choice == Messages.YES) {
            if (hooksManager.installPreCommitHook()) {
                showNotification(
                    project,
                    "Git Hook Installed",
                    "Git pre-commit hook has been successfully installed in project '${project.name}'. " +
                            "Java files will now be automatically formatted before commit.",
                    NotificationType.INFORMATION
                )
                refreshGitHooksStatus()
            } else {
                Messages.showErrorDialog(
                    "Failed to install Git pre-commit hook in project '${project.name}'. Please check the logs for details.",
                    "Installation Failed"
                )
            }
        }
    }

    private fun showManageDialog(project: com.intellij.openapi.project.Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoCancelDialog(
            "GitHelper Spotless pre-commit hook is currently installed in project '${project.name}'.\n\n" +
                    "What would you like to do?",
            "Manage Git Pre-commit Hook",
            "Reinstall Hook",
            "Uninstall Hook",
            "Cancel",
            Messages.getQuestionIcon()
        )

        when (choice) {
            Messages.YES -> {
                // Reinstall
                if (hooksManager.installPreCommitHook()) {
                    showNotification(
                        project,
                        "Git Hook Reinstalled",
                        "Git pre-commit hook has been successfully reinstalled in project '${project.name}'.",
                        NotificationType.INFORMATION
                    )
                    refreshGitHooksStatus()
                } else {
                    Messages.showErrorDialog(
                        "Failed to reinstall Git pre-commit hook in project '${project.name}'. Please check the logs for details.",
                        "Reinstallation Failed"
                    )
                }
            }
            Messages.NO -> {
                // Uninstall
                val confirmUninstall = Messages.showYesNoDialog(
                    "Are you sure you want to uninstall the Git pre-commit hook from project '${project.name}'?\n\n" +
                            "Java files will no longer be automatically formatted before commit.",
                    "Confirm Uninstall",
                    "Uninstall",
                    "Cancel",
                    Messages.getWarningIcon()
                )

                if (confirmUninstall == Messages.YES) {
                    if (hooksManager.uninstallPreCommitHook()) {
                        showNotification(
                            project,
                            "Git Hook Uninstalled",
                            "Git pre-commit hook has been successfully uninstalled from project '${project.name}'.",
                            NotificationType.INFORMATION
                        )
                        refreshGitHooksStatus()
                    } else {
                        Messages.showErrorDialog(
                            "Failed to uninstall Git pre-commit hook from project '${project.name}'. Please check the logs for details.",
                            "Uninstallation Failed"
                        )
                    }
                }
            }
        }
    }

    private fun showReplaceDialog(project: com.intellij.openapi.project.Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoDialog(
            "Another pre-commit hook is already installed in project '${project.name}'.\n\n" +
                    "Would you like to replace it with the GitHelper Spotless hook?\n\n" +
                    "Note: The existing hook will be backed up and can be restored later.",
            "Replace Existing Git Hook",
            "Replace Hook",
            "Cancel",
            Messages.getWarningIcon()
        )

        if (choice == Messages.YES) {
            if (hooksManager.installPreCommitHook()) {
                showNotification(
                    project,
                    "Git Hook Replaced",
                    "Existing pre-commit hook has been backed up and replaced with GitHelper Spotless hook in project '${project.name}'.",
                    NotificationType.INFORMATION
                )
                refreshGitHooksStatus()
            } else {
                Messages.showErrorDialog(
                    "Failed to replace Git pre-commit hook in project '${project.name}'. Please check the logs for details.",
                    "Replacement Failed"
                )
            }
        }
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, title: String, content: String, type: NotificationType) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("GitHelper")

            val notification = notificationGroup?.createNotification(title, content, type)
                ?: com.intellij.notification.Notification("GitHelper", title, content, type)

            Notifications.Bus.notify(notification, project)
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to show notification", e)
        }
    }

    /**
     * Refresh Git Hooks status display
     */
    private fun refreshGitHooksStatus() {
        // Refresh VFS to ensure file system changes are detected
        com.intellij.openapi.vfs.VirtualFileManager.getInstance().syncRefresh()

        statusLabel?.let { label ->
            val newStatus = getGitHooksStatus()
            label.text = newStatus
        }
    }

    private fun getGitHooksStatus(): String {
        // Try to get the current project from various sources
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            ?: ProjectManager.getInstance().defaultProject.takeIf { !it.isDisposed }

        if (project == null) {
            return "📁 Open a project to see Git hooks status"
        }

        val hooksManager = GitHooksManager(project)
        return when (hooksManager.getHookStatus()) {
            GitHooksManager.HookStatus.NO_GIT_REPO ->
                "❌ Current project '${project.name}' is not a Git repository"
            GitHooksManager.HookStatus.NOT_INSTALLED ->
                "⚪ No pre-commit hook installed in project '${project.name}'"
            GitHooksManager.HookStatus.INSTALLED ->
                "✅ GitHelper pre-commit hook is installed in project '${project.name}'"
            GitHooksManager.HookStatus.OTHER_HOOK_EXISTS ->
                "⚠️ Another pre-commit hook exists in project '${project.name}'"
        }
    }

    override fun isModified(): Boolean {
        return settingsPanel?.isModified() ?: false
    }

    override fun apply() {
        settingsPanel?.apply()
    }

    override fun reset() {
        settingsPanel?.reset()
        refreshGitHooksStatus()
    }
}