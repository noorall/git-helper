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

package com.noorall.githelper.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.noorall.githelper.git.GitHooksManager
import com.noorall.githelper.logging.GitHelperLogger

/**
*Git Hooks configuration operation
*
*Provide a user interface to manage Git pre commit hooks
*/
class GitHooksConfigAction : AnAction("Configure Git Hooks") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = getProjectFromContext(e) ?: return

        val hooksManager = GitHooksManager()
        val status = hooksManager.getHookStatus()

        when (status) {
            GitHooksManager.HookStatus.NO_GIT_REPO -> {
                Messages.showErrorDialog(
                    project,
                    "This is not a Git repository. Git hooks can only be configured in Git repositories.",
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
    /**
     * Get project from action event context, following JetBrains official best practices
     */
    private fun getProjectFromContext(e: AnActionEvent): Project? {
        // Method 1: Use standard AnActionEvent.getProject() - this is the official recommended way
        val project = e.project
        if (project != null) {
            GitHelperLogger.info("Using project from event.getProject(): ${project.name}")
            return validateProjectAccess(project)
        }

        // Method 3: Handle no project case
        GitHelperLogger.warn("No project available in action context")
        Messages.showErrorDialog(
            "No project is currently available. Please ensure a project is open and try again.",
            "No Project Available"
        )
        return null
    }

    /**
     * Validate that we can access project path and Git repository
     */
    private fun validateProjectAccess(project: Project): Project? {
        return try {
            val basePath = project.basePath
            if (basePath == null) {
                GitHelperLogger.warn("Project ${project.name} has no base path")
                Messages.showWarningDialog(
                    project,
                    "Project '${project.name}' doesn't have a valid base path. Git hooks cannot be configured.",
                    "Invalid Project Path"
                )
                return null
            }

            // Verify the project path exists
            val projectDir = java.io.File(basePath)
            if (!projectDir.exists() || !projectDir.isDirectory) {
                GitHelperLogger.warn("Project ${project.name} base path doesn't exist: $basePath")
                Messages.showWarningDialog(
                    project,
                    "Project '${project.name}' path doesn't exist: $basePath\nGit hooks cannot be configured.",
                    "Invalid Project Path"
                )
                return null
            }

            GitHelperLogger.info("Successfully validated project access: ${project.name} at $basePath")
            project
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to validate project access for ${project.name}: ${e.message}")
            Messages.showErrorDialog(
                project,
                "Failed to validate project access: ${e.message}\nGit hooks cannot be configured.",
                "Project Access Error"
            )
            null
        }
    }



    private fun showInstallDialog(project: Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoDialog(
            project,
            "No Git pre-commit hook is currently installed.\n\n" +
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
                    "Git pre-commit hook has been successfully installed. " +
                            "Java files will now be automatically formatted before commit.",
                    NotificationType.INFORMATION
                )
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to install Git pre-commit hook. Please check the logs for details.",
                    "Installation Failed"
                )
            }
        }
    }

    private fun showManageDialog(project: Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoCancelDialog(
            project,
            "GitHelper Spotless pre-commit hook is currently installed.\n\n" +
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
                        "Git pre-commit hook has been successfully reinstalled.",
                        NotificationType.INFORMATION
                    )
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to reinstall Git pre-commit hook. Please check the logs for details.",
                        "Reinstallation Failed"
                    )
                }
            }
            Messages.NO -> {
                // Uninstall
                val confirmUninstall = Messages.showYesNoDialog(
                    project,
                    "Are you sure you want to uninstall the Git pre-commit hook?\n\n" +
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
                            "Git pre-commit hook has been successfully uninstalled.",
                            NotificationType.INFORMATION
                        )
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "Failed to uninstall Git pre-commit hook. Please check the logs for details.",
                            "Uninstallation Failed"
                        )
                    }
                }
            }
        }
    }

    private fun showReplaceDialog(project: Project, hooksManager: GitHooksManager) {
        val choice = Messages.showYesNoDialog(
            project,
            "Another pre-commit hook is already installed.\n\n" +
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
                    "Existing pre-commit hook has been backed up and replaced with GitHelper Spotless hook.",
                    NotificationType.INFORMATION
                )
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to replace Git pre-commit hook. Please check the logs for details.",
                    "Replacement Failed"
                )
            }
        }
    }

    private fun showNotification(project: Project, title: String, content: String, type: NotificationType) {
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

    override fun update(e: AnActionEvent) {
        // Use the same project detection logic as actionPerformed for consistency
        val project = e.project ?: e.getData(CommonDataKeys.PROJECT)
        val hasValidProject = project != null && !project.isDisposed

        e.presentation.isEnabledAndVisible = hasValidProject

        if (hasValidProject) {
            // Optional: Set description based on project Git status
            try {
                val hooksManager = GitHooksManager()
                val status = hooksManager.getHookStatus()
                when (status) {
                    GitHooksManager.HookStatus.NO_GIT_REPO -> {
                        e.presentation.description = "Not a Git repository"
                    }
                    GitHooksManager.HookStatus.NOT_INSTALLED -> {
                        e.presentation.description = "Install Git hooks for automatic code formatting"
                    }
                    GitHooksManager.HookStatus.INSTALLED -> {
                        e.presentation.description = "Manage installed GitHelper hooks"
                    }
                    GitHooksManager.HookStatus.OTHER_HOOK_EXISTS -> {
                        e.presentation.description = "Replace existing Git hooks with GitHelper hooks"
                    }
                }
            } catch (ex: Exception) {
                e.presentation.description = "Configure Git hooks for automatic code formatting"
            }
        }
    }
}