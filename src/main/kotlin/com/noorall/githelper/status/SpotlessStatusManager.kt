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

package com.noorall.githelper.status

import com.intellij.openapi.project.Project
import com.noorall.githelper.logging.GitHelperLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Spotless Status Manager
 *
 * Responsible for managing asynchronous communication between IDEA plugin and Git Hook through status files:
 * 1. IDEA plugin creates status files to indicate Spotless execution status
 * 2. Git Hook listens to status files to get execution results
 * 3. Avoid IDEA thread blocking and provide real-time status feedback
 */
class SpotlessStatusManager(private val project: Project) {

    companion object {
        private const val STATUS_DIR = ".idea/githelper"
        private const val STATUS_FILE = "spotless.status"
        private const val LOCK_FILE = "spotless.lock"
        private const val TIMEOUT_SECONDS = 300 // 5 minute timeout
    }

    private val projectPath = project.basePath
    private val statusDir = File(projectPath, STATUS_DIR)
    private val statusFile = File(statusDir, STATUS_FILE)
    private val lockFile = File(statusDir, LOCK_FILE)

    /**
     * Spotless execution status
     */
    enum class SpotlessStatus {
        STARTING,    // Execution started
        RUNNING,     // Execution in progress
        SUCCESS,     // Execution successful
        FAILED,      // Execution failed
        TIMEOUT,     // Execution timed out
        CANCELLED    // Cancelled by user
    }

    /**
     * Status information data class
     */
    data class StatusInfo(
        val status: SpotlessStatus,
        val message: String = "",
        val progress: Double = 0.0,
        val timestamp: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        val processId: String = "",
        val files: List<String> = emptyList()
    )

    /**
     * Initialize status directory
     */
    private fun initStatusDirectory(): Boolean {
        return try {
            if (!statusDir.exists()) {
                val created = statusDir.mkdirs()
                if (created) {
                    GitHelperLogger.info("Created status directory: ${statusDir.absolutePath}")
                } else {
                    GitHelperLogger.error("Failed to create status directory: ${statusDir.absolutePath}")
                    return false
                }
            }

            // Verify directory is writable
            if (!statusDir.canWrite()) {
                GitHelperLogger.error("Status directory is not writable: ${statusDir.absolutePath}")
                return false
            }

            GitHelperLogger.debug("Status directory is ready: ${statusDir.absolutePath}")
            true
        } catch (e: Exception) {
            GitHelperLogger.error("Exception while initializing status directory: ${statusDir.absolutePath}", e)
            false
        }
    }

    /**
     * Create lock file to prevent concurrent execution
     */
    fun acquireLock(processId: String): Boolean {
        return try {
            if (!initStatusDirectory()) {
                GitHelperLogger.error("Cannot acquire lock: failed to initialize status directory")
                return false
            }

            if (lockFile.exists()) {
                // Check if lock file is expired
                val lockAge = System.currentTimeMillis() - lockFile.lastModified()
                if (lockAge > TIMEOUT_SECONDS * 1000) {
                    GitHelperLogger.warn("Lock file expired, removing old lock")
                    lockFile.delete()
                } else {
                    GitHelperLogger.info("Another Spotless process is running")
                    return false
                }
            }

            // Create lock file
            val lockContent = """
                processId=$processId
                timestamp=${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}
                pid=${ProcessHandle.current().pid()}
            """.trimIndent()

            Files.write(lockFile.toPath(), lockContent.toByteArray(), StandardOpenOption.CREATE)

            // Verify lock file was created successfully
            if (!lockFile.exists()) {
                GitHelperLogger.error("Lock file was not created successfully: ${lockFile.absolutePath}")
                return false
            }

            GitHelperLogger.info("Acquired lock for process: $processId")
            true
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to acquire lock", e)
            false
        }
    }

    /**
     * Release lock file
     */
    fun releaseLock() {
        try {
            if (lockFile.exists()) {
                lockFile.delete()
                GitHelperLogger.info("Released lock file")
            }
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to release lock", e)
        }
    }

    /**
     * Write status information
     */
    fun writeStatus(statusInfo: StatusInfo) {
        try {
            if (!initStatusDirectory()) {
                GitHelperLogger.error("Cannot write status: failed to initialize status directory")
                return
            }

            val statusContent = """
                status=${statusInfo.status.name}
                message=${statusInfo.message}
                progress=${statusInfo.progress}
                timestamp=${statusInfo.timestamp}
                processId=${statusInfo.processId}
                files=${statusInfo.files.joinToString(",")}
            """.trimIndent()

            GitHelperLogger.debug("Writing status to: ${statusFile.absolutePath}")
            GitHelperLogger.debug("Status content: $statusContent")

            Files.write(statusFile.toPath(), statusContent.toByteArray(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            // Verify file was written successfully
            if (!statusFile.exists()) {
                GitHelperLogger.error("Status file was not created: ${statusFile.absolutePath}")
                return
            }

            // Verify file content was written correctly
            val writtenContent = statusFile.readText()
            if (writtenContent != statusContent) {
                GitHelperLogger.error("Status file content mismatch. Expected length: ${statusContent.length}, Actual length: ${writtenContent.length}")
                GitHelperLogger.debug("Expected content: $statusContent")
                GitHelperLogger.debug("Actual content: $writtenContent")
                return
            }

            GitHelperLogger.info("Successfully updated status: ${statusInfo.status} - ${statusInfo.message}")
            GitHelperLogger.debug("Status file size: ${statusFile.length()} bytes")
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to write status to: ${statusFile.absolutePath}", e)
            // Try to output more debug information
            GitHelperLogger.error("Status directory exists: ${statusDir.exists()}")
            GitHelperLogger.error("Status directory writable: ${statusDir.canWrite()}")
            GitHelperLogger.error("Status file exists: ${statusFile.exists()}")
        }
    }

    /**
     * Read status information
     */
    fun readStatus(): StatusInfo? {
        return try {
            if (!statusFile.exists()) {
                return null
            }

            val content = statusFile.readText()
            val lines = content.lines()
            val statusMap = lines.associate { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }

            StatusInfo(
                status = SpotlessStatus.valueOf(statusMap["status"] ?: "STARTING"),
                message = statusMap["message"] ?: "",
                progress = statusMap["progress"]?.toDoubleOrNull() ?: 0.0,
                timestamp = statusMap["timestamp"] ?: "",
                processId = statusMap["processId"] ?: "",
                files = statusMap["files"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            )
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to read status", e)
            null
        }
    }

    /**
     * Clean up status files
     */
    fun cleanup() {
        try {
            if (statusFile.exists()) {
                statusFile.delete()
                GitHelperLogger.debug("Cleaned up status file")
            }
            releaseLock()
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to cleanup status files", e)
        }
    }

    /**
     * Check if there is a running Spotless process
     */
    fun isSpotlessRunning(): Boolean {
        val status = readStatus()
        return status != null && (status.status == SpotlessStatus.STARTING || status.status == SpotlessStatus.RUNNING)
    }
}