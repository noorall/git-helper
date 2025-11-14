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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vfs.VirtualFile
import com.noorall.githelper.logging.GitHelperLogger
import com.noorall.githelper.maven.SpotlessExecutor
import com.noorall.githelper.settings.GitHelperSettings
import com.noorall.githelper.status.SpotlessStatusManager
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
*Asynchronous Git commit processor
*
*Implementing asynchronous communication between IDEA plugin and Git Hook using state files:
* 1. IDEA plugin returns immediately without blocking the commit process
* 2. Asynchronous execution of Spotless formatting in the background
* 3. Communicate with Git Hook through state files
* 4. Git Hook listens to status files to obtain execution results
*/
class GitHelperAsyncHandler(
    private val panel: CheckinProjectPanel,
    private val commitContext: CommitContext
) : CheckinHandler() {

    private val settings = GitHelperSettings.getInstance()
    private val project: Project = panel.project
    private val statusManager = SpotlessStatusManager(project)
    private var isProcessing = AtomicBoolean(false)

    companion object {
        private const val NOTIFICATION_GROUP_ID = "GitHelper"
        
        //Used to track processed sessions and avoid duplicate formatting
        private val processedSessions = mutableSetOf<String>()
    }

    override fun beforeCheckin(): ReturnResult {
        GitHelperLogger.info("GitHelperAsyncHandler beforeCheckin called")

        if (!settings.spotlessEnabled) {
            GitHelperLogger.info("Spotless is disabled, proceeding with commit")
            return ReturnResult.COMMIT
        }

        val changedFiles = getChangedJavaFiles()
        if (changedFiles.isEmpty()) {
            GitHelperLogger.info("No Java files changed, proceeding with commit")
            return ReturnResult.COMMIT
        }

        // Generate session ID to avoid duplicate processing
        val sessionId = generateSessionId(changedFiles)
        if (processedSessions.contains(sessionId)) {
            GitHelperLogger.info("Files already processed in this session, proceeding with commit")
            processedSessions.remove(sessionId) // Clean up session
            return ReturnResult.COMMIT
        }

        // Check if there are any other Spotless processes running
        if (statusManager.isSpotlessRunning()) {
            GitHelperLogger.info("Another Spotless process is running, proceeding with commit")
            showNotification(
                "Spotless Already Running",
                "Another Spotless formatting process is already running. Proceeding with commit.",
                NotificationType.WARNING
            )
            return ReturnResult.COMMIT
        }

        // Start asynchronous Spotless processing
        startAsyncSpotlessProcessing(changedFiles, sessionId)
        
        // Return immediately and let the commit continue
        GitHelperLogger.info("Started async Spotless processing, proceeding with commit")
        return ReturnResult.COMMIT
    }

    /**
     * Start asynchronous Spotless processing
     */
    private fun startAsyncSpotlessProcessing(files: List<String>, sessionId: String) {
        if (!isProcessing.compareAndSet(false, true)) {
            GitHelperLogger.info("Already processing, skipping")
            return
        }

        try {
            // Attempt to acquire lock
            if (!statusManager.acquireLock(sessionId)) {
                GitHelperLogger.info("Failed to acquire lock, another process is running")
                isProcessing.set(false)
                return
            }

            // Write initial status
            statusManager.writeStatus(
                SpotlessStatusManager.StatusInfo(
                    status = SpotlessStatusManager.SpotlessStatus.STARTING,
                    message = "Initializing Spotless formatting...",
                    processId = sessionId,
                    files = files
                )
            )

            // Show start notification
            showNotification(
                "Spotless Formatting Started",
                "Started formatting ${files.size} Java file(s) in background...",
                NotificationType.INFORMATION
            )

            // Execute Spotless in background thread
            val task = object : Task.Backgroundable(project, "Spotless Code Formatting", true) {
                override fun run(indicator: ProgressIndicator) {
                    executeSpotlessFormatting(files, sessionId, indicator)
                }

                override fun onFinished() {
                    isProcessing.set(false)
                }

                override fun onCancel() {
                    handleSpotlessCancel(sessionId)
                    isProcessing.set(false)
                }
            }

            ProgressManager.getInstance().run(task)

        } catch (e: Exception) {
            GitHelperLogger.error("Failed to start async Spotless processing", e)
            handleSpotlessError(sessionId, e)
            isProcessing.set(false)
        }
    }

    /**
     * Execute Spotless formatting
     */
    private fun executeSpotlessFormatting(files: List<String>, sessionId: String, indicator: ProgressIndicator) {
        try {
            GitHelperLogger.info("Starting Spotless formatting for ${files.size} files")
            
            // Update status to running
            statusManager.writeStatus(
                SpotlessStatusManager.StatusInfo(
                    status = SpotlessStatusManager.SpotlessStatus.RUNNING,
                    message = "Formatting ${files.size} file(s)...",
                    processId = sessionId,
                    files = files
                )
            )

            indicator.text = "Formatting ${files.size} file(s) with Spotless..."
            indicator.isIndeterminate = false
            indicator.fraction = 0.0

            val executor = SpotlessExecutor(project, settings.mavenExecutable)
            
            val success = executor.runSpotlessApply(
                files = files,
                progressIndicator = indicator,
                progressCallback = { progress: Double ->
                    if (!indicator.isCanceled) {
                        indicator.fraction = progress
                        indicator.text2 = "Progress: ${(progress * 100).toInt()}%"
                        
                        // Update progress in status file
                        statusManager.writeStatus(
                            SpotlessStatusManager.StatusInfo(
                                status = SpotlessStatusManager.SpotlessStatus.RUNNING,
                                message = "Formatting in progress...",
                                progress = progress,
                                processId = sessionId,
                                files = files
                            )
                        )
                    }
                }
            )

            if (indicator.isCanceled) {
                handleSpotlessCancel(sessionId)
            } else if (success) {
                handleSpotlessSuccess(sessionId, files)
            } else {
                handleSpotlessFailure(sessionId, "Spotless formatting failed")
            }

        } catch (e: Exception) {
            GitHelperLogger.error("Exception during Spotless formatting", e)
            handleSpotlessError(sessionId, e)
        }
    }

    /**
     * Handle Spotless success
     */
    private fun handleSpotlessSuccess(sessionId: String, files: List<String>) {
        GitHelperLogger.info("Spotless formatting completed successfully")
        
        statusManager.writeStatus(
            SpotlessStatusManager.StatusInfo(
                status = SpotlessStatusManager.SpotlessStatus.SUCCESS,
                message = "Formatting completed successfully",
                progress = 1.0,
                processId = sessionId,
                files = files
            )
        )

        processedSessions.add(sessionId)

        ApplicationManager.getApplication().invokeLater {
            showNotification(
                "Spotless Formatting Complete",
                "Successfully formatted ${files.size} Java file(s). Files are ready for commit.",
                NotificationType.INFORMATION
            )
        }

        // Delay cleanup of status files to give Git Hook enough time to read results
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(5000) // Wait for 5 seconds
            statusManager.cleanup()
        }
    }

    /**
     * Handle Spotless failure
     */
    private fun handleSpotlessFailure(sessionId: String, message: String) {
        GitHelperLogger.error("Spotless formatting failed: $message")
        
        statusManager.writeStatus(
            SpotlessStatusManager.StatusInfo(
                status = SpotlessStatusManager.SpotlessStatus.FAILED,
                message = message,
                processId = sessionId
            )
        )

        ApplicationManager.getApplication().invokeLater {
            showNotification(
                "Spotless Formatting Failed",
                "Code formatting failed: $message. Please check the logs for details.",
                NotificationType.ERROR
            )
        }

        // Delay cleanup of status files
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(5000)
            statusManager.cleanup()
        }
    }

    /**
     * Handle Spotless cancellation
     */
    private fun handleSpotlessCancel(sessionId: String) {
        GitHelperLogger.info("Spotless formatting was cancelled")

        statusManager.writeStatus(
            SpotlessStatusManager.StatusInfo(
                status = SpotlessStatusManager.SpotlessStatus.CANCELLED,
                message = "Formatting was cancelled by user",
                processId = sessionId
            )
        )

        ApplicationManager.getApplication().invokeLater {
            showNotification(
                "Spotless Formatting Cancelled",
                "Code formatting was cancelled.",
                NotificationType.WARNING
            )
        }

        // Delay cleanup of status files
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(2000)
            statusManager.cleanup()
        }
    }

    /**
     * Handle Spotless error
     */
    private fun handleSpotlessError(sessionId: String, error: Exception) {
        GitHelperLogger.error("Spotless formatting error", error)

        statusManager.writeStatus(
            SpotlessStatusManager.StatusInfo(
                status = SpotlessStatusManager.SpotlessStatus.FAILED,
                message = "Error: ${error.message}",
                processId = sessionId
            )
        )

        ApplicationManager.getApplication().invokeLater {
            showNotification(
                "Spotless Formatting Error",
                "An error occurred during formatting: ${error.message}",
                NotificationType.ERROR
            )
        }

        // Delay cleanup of status files
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(2000)
            statusManager.cleanup()
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