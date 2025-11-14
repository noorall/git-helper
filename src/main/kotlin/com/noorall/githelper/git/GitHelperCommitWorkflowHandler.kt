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

package com.noorall.githelper.git

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vfs.VirtualFile
import com.noorall.githelper.logging.GitHelperLogger
import com.noorall.githelper.maven.SpotlessExecutor
import com.noorall.githelper.settings.GitHelperSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Git commit workflow interceptor, implementing a legitimate spotless formatting + commit process
 *
 * Core idea:
 * 1. Intercept commit operations
 * 2. Use IntelliJ's officially recommended runProcessWithProgressSynchronously to execute formatting
 * 3. Continue normal commit process after formatting is complete
 * 4. Users see progress dialog and can cancel, completely legitimate and user-friendly
 */
class GitHelperCommitWorkflowHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext
) : CheckinHandler() {

    private val settings = GitHelperSettings.getInstance()
    private val project: Project = panel.project
    private var isProcessing = AtomicBoolean(false)

    companion object {
        private const val NOTIFICATION_GROUP_ID = "GitHelper"

        // Used to track if formatting has already been done, avoid infinite loop
        private val formattedSessions = mutableSetOf<String>()
    }

    override fun beforeCheckin(): ReturnResult {
        GitHelperLogger.info("GitHelperCommitWorkflowHandler beforeCheckin called")

        if (!settings.spotlessEnabled) {
            GitHelperLogger.info("Spotless is disabled, proceeding with commit")
            return ReturnResult.COMMIT
        }

        val changedFiles = getChangedJavaFiles()
        if (changedFiles.isEmpty()) {
            GitHelperLogger.info("No Java files changed, proceeding with commit")
            return ReturnResult.COMMIT
        }

        // Generate session ID to avoid duplicate formatting
        val sessionId = generateSessionId(changedFiles)
        if (formattedSessions.contains(sessionId)) {
            GitHelperLogger.info("Files already formatted in this session, proceeding with commit")
            formattedSessions.remove(sessionId) // Clean up session
            return ReturnResult.COMMIT
        }

        // Avoid duplicate execution if already processing
        if (!isProcessing.compareAndSet(false, true)) {
            GitHelperLogger.info("Already processing, proceeding with commit")
            return ReturnResult.COMMIT
        }

        try {
            GitHelperLogger.info("Starting synchronous spotless formatting for ${changedFiles.size} files")
            return executeSynchronousFormatting(changedFiles, sessionId)
        } catch (e: Exception) {
            GitHelperLogger.error("Error in commit workflow handler", e)
            isProcessing.set(false)
            return ReturnResult.COMMIT // Allow normal commit on error
        }
    }

    /**
     * Execute formatting using IntelliJ's officially recommended runProcessWithProgressSynchronously
     * This is a completely legitimate and officially supported synchronous execution method!
     */
    private fun executeSynchronousFormatting(files: List<String>, sessionId: String): ReturnResult {
        try {
            var formatSuccess = false
            var formatError: Exception? = null

            // Use official recommended synchronous progress execution method
            val success = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    val indicator = ProgressManager.getInstance().progressIndicator
                    try {
                        indicator?.text = "Formatting ${files.size} file(s) with Spotless..."
                        indicator?.isIndeterminate = false
                        indicator?.fraction = 0.0

                        val executor = SpotlessExecutor(project, settings.mavenExecutable)

                        formatSuccess = executor.runSpotlessApply(
                            files = files,
                            progressIndicator = indicator,
                            progressCallback = { progress: Double ->
                                indicator?.let { ind ->
                                    if (!ind.isCanceled) {
                                        ind.fraction = progress
                                        ind.text2 = "Progress: ${(progress * 100).toInt()}%"
                                    }
                                }
                            }
                        )

                        if (indicator?.isCanceled == true) {
                            GitHelperLogger.info("User cancelled formatting")
                            formatSuccess = false
                        } else if (formatSuccess) {
                            GitHelperLogger.info("Spotless formatting completed successfully")
                        } else {
                            GitHelperLogger.error("Spotless formatting failed")
                        }
                    } catch (e: Exception) {
                        GitHelperLogger.error("Exception during formatting", e)
                        formatError = e
                        formatSuccess = false
                    }
                },
                "Spotless Code Formatting", // Progress dialog title
                true, // Cancelable
                project
            )

            isProcessing.set(false)

            return when {
                !success -> {
                    // User cancelled the operation
                    GitHelperLogger.info("User cancelled formatting, proceeding with commit")
                    showNotification(
                        "Formatting Cancelled",
                        "Code formatting was cancelled. Proceeding with commit.",
                        NotificationType.WARNING
                    )
                    ReturnResult.COMMIT
                }
                formatError != null -> {
                    // Formatting error
                    showNotification(
                        "Formatting Failed",
                        "Code formatting failed: ${formatError.message}. Proceeding with commit.",
                        NotificationType.ERROR
                    )
                    ReturnResult.COMMIT
                }
                formatSuccess -> {
                    // Formatting successful
                    formattedSessions.add(sessionId)
                    showNotification(
                        "Formatting Complete",
                        "Code formatting completed successfully. Continuing with commit...",
                        NotificationType.INFORMATION
                    )
                    ReturnResult.COMMIT // Continue normal commit process
                }
                else -> {
                    // Formatting failed
                    showNotification(
                        "Formatting Failed",
                        "Code formatting failed. Proceeding with commit.",
                        NotificationType.ERROR
                    )
                    ReturnResult.COMMIT
                }
            }
        } catch (e: Exception) {
            GitHelperLogger.error("Exception in synchronous formatting", e)
            isProcessing.set(false)
            showNotification(
                "Formatting Error",
                "An error occurred during formatting: ${e.message}. Proceeding with commit.",
                NotificationType.ERROR
            )
            return ReturnResult.COMMIT
        }
    }

    /**
     * Get list of changed Java files
     */
    private fun getChangedJavaFiles(): List<String> {
        val javaFiles = mutableListOf<String>()

        for (change in panel.selectedChanges) {
            val virtualFile = getVirtualFileFromChange(change)
            if (virtualFile != null && isJavaFile(virtualFile)) {
                val relativePath = getRelativePath(virtualFile)
                if (relativePath != null) {
                    javaFiles.add(relativePath)
                }
            }
        }

        GitHelperLogger.info("Found ${javaFiles.size} Java files: ${javaFiles.joinToString(", ")}")
        return javaFiles
    }

    private fun getVirtualFileFromChange(change: Change): VirtualFile? {
        return change.afterRevision?.file?.virtualFile
    }

    private fun isJavaFile(file: VirtualFile): Boolean {
        return file.extension?.lowercase() == "java"
    }

    private fun getRelativePath(file: VirtualFile): String? {
        val projectBasePath = project.basePath ?: return null
        val filePath = file.path
        return if (filePath.startsWith(projectBasePath)) {
            filePath.substring(projectBasePath.length + 1)
        } else null
    }

    /**
     * Generate session ID to avoid duplicate formatting
     */
    private fun generateSessionId(files: List<String>): String {
        return files.sorted().joinToString("|") + "_" + System.currentTimeMillis() / 10000 // 10 second precision
    }

    /**
     * Show notification
     */
    private fun showNotification(title: String, content: String, type: NotificationType) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)

            val notification = if (notificationGroup != null) {
                notificationGroup.createNotification(title, content, type)
            } else {
                Notification(NOTIFICATION_GROUP_ID, title, content, type)
            }

            Notifications.Bus.notify(notification, project)
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to show notification", e)
        }
    }
}