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

package com.noorall.githelper.maven

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.noorall.githelper.logging.GitHelperLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SpotlessExecutor(
    private val project: Project,
    private val mavenExecutable: String = "mvn"
) {

    private val cancelled = AtomicBoolean(false)
    private var currentProcessHandler: OSProcessHandler? = null
    private val moduleAnalyzer = ModuleAnalyzer(project)
    private var parallelExecutor: ParallelSpotlessExecutor? = null

    companion object {
        private const val DEFAULT_TIMEOUT_MINUTES = 5L
        private const val MAX_TIMEOUT_MINUTES = 10L
    }

    /**
     * Intelligent Spotless execution, automatically select related modules based on changed files
     */
    fun runSpotlessApply(
        files: List<String>,
        progressIndicator: ProgressIndicator? = null,
        progressCallback: ((Double) -> Unit)? = null,
        timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES
    ): Boolean {
        GitHelperLogger.info("Starting intelligent Spotless execution for ${files.size} files")

        return try {
            // Analyze affected modules
            val executionPlan = moduleAnalyzer.analyzeAffectedModules(files)

            if (executionPlan.modules.isEmpty()) {
                GitHelperLogger.warn("No affected modules found, falling back to legacy execution")
                return runLegacySpotlessApply(files, progressIndicator, progressCallback, timeoutMinutes)
            }

            // Validate all modules
            val validModules = executionPlan.modules.filter { module ->
                if (moduleAnalyzer.validateModule(module)) {
                    true
                } else {
                    GitHelperLogger.warn("Invalid module: ${module.displayName}, skipping")
                    false
                }
            }

            if (validModules.isEmpty()) {
                GitHelperLogger.error("No valid modules found")
                return false
            }

            GitHelperLogger.info("Execution plan: ${validModules.size} modules to process")
            validModules.forEach { module ->
                GitHelperLogger.info("  - ${module.displayName}: ${module.pomFile.absolutePath}")
            }

            // Choose execution strategy based on number of modules
            return if (validModules.size == 1) {
                // Single module execution
                executeSingleModule(validModules.first(), files, progressIndicator, progressCallback, timeoutMinutes)
            } else {
                // Multi-module parallel execution
                executeMultipleModules(validModules, files, progressIndicator, timeoutMinutes)
            }

        } catch (e: Exception) {
            GitHelperLogger.error("Error during intelligent Spotless execution", e)
            // Fall back to legacy execution mode on error
            runLegacySpotlessApply(files, progressIndicator, progressCallback, timeoutMinutes)
        }
    }

    /**
     * Execute a single module
     */
    private fun executeSingleModule(
        module: ModuleAnalyzer.MavenModule,
        files: List<String>,
        progressIndicator: ProgressIndicator?,
        progressCallback: ((Double) -> Unit)?,
        timeoutMinutes: Long
    ): Boolean {
        GitHelperLogger.info("Executing single module: ${module.displayName}")

        progressIndicator?.text = "Formatting files in ${module.displayName} module..."

        val filesParam = files.joinToString(",")
        val commandLine = GeneralCommandLine()
            .withExePath(mavenExecutable)
            .withParameters("spotless:apply", "-f", module.pomFile.absolutePath, "-Dspotless.includes=$filesParam", "-X")
            .withWorkDirectory(module.pomFile.parentFile)

        GitHelperLogger.info("Executing command: ${commandLine.commandLineString}")

        return try {
            executeCommand(commandLine, progressIndicator, progressCallback, timeoutMinutes)
        } catch (e: ExecutionException) {
            GitHelperLogger.error("Failed to execute Maven command for module: ${module.displayName}", e)
            false
        } catch (e: Exception) {
            GitHelperLogger.error("Unexpected error during Maven execution for module: ${module.displayName}", e)
            false
        }
    }

    /**
     * Execute multiple modules in parallel
     */
    private fun executeMultipleModules(
        modules: List<ModuleAnalyzer.MavenModule>,
        files: List<String>,
        progressIndicator: ProgressIndicator?,
        timeoutMinutes: Long
    ): Boolean {
        GitHelperLogger.info("Executing ${modules.size} modules in parallel")

        progressIndicator?.text = "Formatting files in ${modules.size} modules (parallel)..."

        parallelExecutor = ParallelSpotlessExecutor(project, mavenExecutable)

        return try {
            val summary = parallelExecutor!!.executeModulesParallel(
                modules, files, progressIndicator, timeoutMinutes
            )

            // Record execution results
            GitHelperLogger.info("Parallel execution summary:")
            GitHelperLogger.info("  Total modules: ${summary.results.size}")
            GitHelperLogger.info("  Successful: ${summary.successCount}")
            GitHelperLogger.info("  Failed: ${summary.failureCount}")
            GitHelperLogger.info("  Total time: ${summary.totalDuration}ms")

            summary.results.forEach { result ->
                if (result.success) {
                    GitHelperLogger.info("  ✓ ${result.module.displayName}: ${result.duration}ms")
                } else {
                    GitHelperLogger.error("  ✗ ${result.module.displayName}: ${result.error ?: "Unknown error"}")
                }
            }

            summary.totalSuccess

        } finally {
            parallelExecutor?.shutdown()
            parallelExecutor = null
        }
    }

    /**
     * Traditional Spotless execution method (backward compatible)
     */
    private fun runLegacySpotlessApply(
        files: List<String>,
        progressIndicator: ProgressIndicator?,
        progressCallback: ((Double) -> Unit)?,
        timeoutMinutes: Long
    ): Boolean {
        GitHelperLogger.info("Running legacy Spotless execution")

        val projectBasePath = project.basePath ?: run {
            GitHelperLogger.error("Project base path is null")
            return false
        }

        val projectDir = File(projectBasePath)
        GitHelperLogger.info("Starting Spotless execution in directory: ${projectDir.absolutePath}")

        // Check if pom.xml exists
        val pomFile = File(projectDir, "pom.xml")
        if (!pomFile.exists()) {
            GitHelperLogger.error("pom.xml not found in project directory: ${projectDir.absolutePath}")
            return false
        }

        GitHelperLogger.info("Found pom.xml, proceeding with Spotless execution")
        GitHelperLogger.info("Files to format: ${files.joinToString(", ")}")
        GitHelperLogger.info("Timeout set to: $timeoutMinutes minutes")

        val filesParam = files.joinToString(",")
        val commandLine = GeneralCommandLine()
            .withExePath(mavenExecutable)
            .withParameters("spotless:apply", "-Dspotless.includes=$filesParam", "-X")
            .withWorkDirectory(projectDir)

        GitHelperLogger.info("Executing command: ${commandLine.commandLineString}")

        return try {
            executeCommand(commandLine, progressIndicator, progressCallback, timeoutMinutes)
        } catch (e: ExecutionException) {
            GitHelperLogger.error("Failed to execute Maven command", e)
            false
        } catch (e: Exception) {
            GitHelperLogger.error("Unexpected error during Maven execution", e)
            false
        }
    }

    fun cancel() {
        GitHelperLogger.info("Cancellation requested")
        cancelled.set(true)

        // Cancel current process
        currentProcessHandler?.let { handler ->
            if (!handler.isProcessTerminated) {
                GitHelperLogger.info("Destroying Maven process")
                handler.destroyProcess()
            }
        }

        // Cancel parallel execution
        parallelExecutor?.cancel()
    }

    private fun executeCommand(
        commandLine: GeneralCommandLine,
        progressIndicator: ProgressIndicator?,
        progressCallback: ((Double) -> Unit)?,
        timeoutMinutes: Long
    ): Boolean {
        cancelled.set(false)

        val processHandler = OSProcessHandler(commandLine)
        currentProcessHandler = processHandler

        var success = false
        val totalFiles = AtomicInteger(0)
        val processedFiles = AtomicInteger(0)
        val outputLines = mutableListOf<String>()
        val latch = CountDownLatch(1)
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutMinutes * 60 * 1000L

        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text.trim()
                if (text.isNotEmpty()) {
                    outputLines.add(text)

                    // Log all Maven output
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> GitHelperLogger.mavenOutput("STDOUT: $text")
                        ProcessOutputTypes.STDERR -> GitHelperLogger.mavenOutput("STDERR: $text")
                        else -> GitHelperLogger.mavenOutput("OTHER: $text")
                    }

                    // Parse Maven output to track progress
                    if (outputType == ProcessOutputTypes.STDOUT) {
                        when {
                            // Better progress tracking patterns
                            text.contains("Processing") && text.contains(".java") -> {
                                totalFiles.incrementAndGet()
                                GitHelperLogger.debug("Detected Java file processing, total files: ${totalFiles.get()}")
                            }
                            text.contains("Spotless.Java") || text.contains("spotless:apply") -> {
                                // This indicates Spotless is starting
                                GitHelperLogger.debug("Spotless Java plugin detected")
                            }
                            text.contains("BUILD SUCCESS") -> {
                                success = true
                                GitHelperLogger.info("Maven build completed successfully")
                            }
                            text.contains("BUILD FAILURE") -> {
                                GitHelperLogger.error("Maven build failed")
                            }
                            // More comprehensive progress patterns
                            text.contains("Formatted") ||
                            text.contains("Already formatted") ||
                            text.contains("Up-to-date") ||
                            text.contains("Applying") ||
                            (text.contains("Processing") && text.contains("files")) -> {
                                val processed = processedFiles.incrementAndGet()
                                GitHelperLogger.debug("File processed, count: $processed")

                                // Improved progress calculation - use provided files list size
                                val total = maxOf(totalFiles.get(), processedFiles.get() + 1)
                                if (total > 0) {
                                    val progress = minOf(processed.toDouble() / total, 1.0)
                                    progressCallback?.invoke(progress)
                                    progressIndicator?.fraction = progress
                                    progressIndicator?.text2 = "Processed $processed of $total files"
                                    GitHelperLogger.debug("Progress updated: ${(progress * 100).toInt()}% ($processed/$total)")
                                }
                            }
                            // Detect when Spotless starts processing files
                            text.contains("Checking") && text.contains("files") -> {
                                // Extract file count if possible
                                val fileCountRegex = """(\d+)\s+files?""".toRegex()
                                val matchResult = fileCountRegex.find(text)
                                if (matchResult != null) {
                                    val detectedCount = matchResult.groupValues[1].toIntOrNull()
                                    if (detectedCount != null && detectedCount > totalFiles.get()) {
                                        totalFiles.set(detectedCount)
                                        GitHelperLogger.debug("Updated total files from Maven output: $detectedCount")
                                    }
                                }
                            }
                        }
                    }

                    // Enhanced cancellation and timeout checking
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - startTime

                    if (cancelled.get() || progressIndicator?.isCanceled == true) {
                        GitHelperLogger.info("Process cancellation detected, destroying process")
                        processHandler.destroyProcess()
                        return
                    }

                    if (elapsed > timeoutMs) {
                        GitHelperLogger.warn("Process timeout after ${timeoutMinutes} minutes, destroying process")
                        processHandler.destroyProcess()
                        return
                    }

                    // Update progress indicator text with time information
                    if (progressIndicator != null && elapsed > 0) {
                        val elapsedSeconds = elapsed / 1000
                        val remainingSeconds = maxOf(0, (timeoutMs - elapsed) / 1000)
                        progressIndicator.text = "Formatting files with Spotless... (${elapsedSeconds}s elapsed, ${remainingSeconds}s remaining)"
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                val exitCode = event.exitCode
                val elapsed = System.currentTimeMillis() - startTime
                GitHelperLogger.info("Maven process terminated with exit code: $exitCode after ${elapsed}ms")

                if (cancelled.get() || progressIndicator?.isCanceled == true) {
                    GitHelperLogger.info("Process was cancelled by user")
                    success = false
                } else if (elapsed > timeoutMs) {
                    GitHelperLogger.error("Process was terminated due to timeout (${timeoutMinutes} minutes)")
                    success = false
                } else {
                    success = success && exitCode == 0
                    if (!success) {
                        GitHelperLogger.error("Maven process failed with exit code: $exitCode")
                        GitHelperLogger.error("Last 10 output lines:")
                        outputLines.takeLast(10).forEach { line ->
                            GitHelperLogger.error("  $line")
                        }
                    }
                }
                latch.countDown()
            }
        })

        GitHelperLogger.info("Starting Maven process...")
        processHandler.startNotify()

        // Wait for process to complete with proper timeout and cancellation support
        try {
            val maxWaitTime = timeoutMinutes + 1 // Add 1 minute buffer for cleanup
            if (!latch.await(maxWaitTime, TimeUnit.MINUTES)) {
                GitHelperLogger.error("Process did not complete within ${maxWaitTime} minutes, forcing termination")
                if (!processHandler.isProcessTerminated) {
                    processHandler.destroyProcess()
                }
                return false
            }
        } catch (e: InterruptedException) {
            GitHelperLogger.error("Process execution was interrupted", e)
            if (!processHandler.isProcessTerminated) {
                processHandler.destroyProcess()
            }
            return false
        }

        GitHelperLogger.info("Maven execution completed, success: $success")
        return success
    }
}