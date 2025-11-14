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
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parallel Spotless Executor, supports executing Spotless tasks for multiple modules simultaneously
 */
class ParallelSpotlessExecutor(
    private val project: Project,
    private val mavenExecutable: String = "mvn"
) {

    data class ModuleExecutionResult(
        val module: ModuleAnalyzer.MavenModule,
        val success: Boolean,
        val duration: Long,
        val error: String? = null
    )

    data class ExecutionSummary(
        val results: List<ModuleExecutionResult>,
        val totalSuccess: Boolean,
        val totalDuration: Long
    ) {
        val successCount: Int get() = results.count { it.success }
        val failureCount: Int get() = results.count { !it.success }
    }

    private val cancelled = AtomicBoolean(false)
    private val activeProcesses = mutableSetOf<OSProcessHandler>()
    private val executorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpotlessExecutor-${Thread.currentThread().id}").apply {
            isDaemon = true
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MINUTES = 5L
    }

    /**
     * Execute Spotless tasks for multiple modules in parallel
     */
    fun executeModulesParallel(
        modules: List<ModuleAnalyzer.MavenModule>,
        files: List<String>,
        progressIndicator: ProgressIndicator? = null,
        timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES
    ): ExecutionSummary {
        GitHelperLogger.info("Starting parallel execution for ${modules.size} modules")
        
        if (modules.isEmpty()) {
            return ExecutionSummary(emptyList(), true, 0)
        }

        val startTime = System.currentTimeMillis()
        cancelled.set(false)

        try {
            val futures = modules.mapIndexed { index, module ->
                executorService.submit<ModuleExecutionResult> {
                    executeModule(module, files, progressIndicator, timeoutMinutes, index, modules.size)
                }
            }

            // Wait for all tasks to complete
            val results = mutableListOf<ModuleExecutionResult>()
            for ((index, future) in futures.withIndex()) {
                try {
                    val result = future.get(timeoutMinutes + 1, TimeUnit.MINUTES)
                    results.add(result)
                    
                    // Update overall progress
                    progressIndicator?.let { indicator ->
                        val overallProgress = (index + 1).toDouble() / modules.size
                        indicator.fraction = overallProgress
                        indicator.text = "Completed ${index + 1} of ${modules.size} modules"
                    }
                } catch (e: TimeoutException) {
                    GitHelperLogger.error("Module execution timed out: ${modules[index].displayName}")
                    results.add(ModuleExecutionResult(
                        modules[index], 
                        false, 
                        timeoutMinutes * 60 * 1000,
                        "Execution timed out"
                    ))
                } catch (e: Exception) {
                    GitHelperLogger.error("Module execution failed: ${modules[index].displayName}", e)
                    results.add(ModuleExecutionResult(
                        modules[index], 
                        false, 
                        0,
                        e.message ?: "Unknown error"
                    ))
                }
            }

            val totalDuration = System.currentTimeMillis() - startTime
            val totalSuccess = results.all { it.success }

            val summary = ExecutionSummary(results, totalSuccess, totalDuration)
            
            GitHelperLogger.info("Parallel execution completed:")
            GitHelperLogger.info("  Total time: ${totalDuration}ms")
            GitHelperLogger.info("  Success: ${summary.successCount}/${modules.size}")
            GitHelperLogger.info("  Failed: ${summary.failureCount}/${modules.size}")

            return summary

        } finally {
            // Clean up resources
            synchronized(activeProcesses) {
                activeProcesses.forEach { process ->
                    if (!process.isProcessTerminated) {
                        process.destroyProcess()
                    }
                }
                activeProcesses.clear()
            }
        }
    }

    /**
     * Execute Spotless task for a single module
     */
    private fun executeModule(
        module: ModuleAnalyzer.MavenModule,
        files: List<String>,
        progressIndicator: ProgressIndicator?,
        timeoutMinutes: Long,
        moduleIndex: Int,
        totalModules: Int
    ): ModuleExecutionResult {
        val startTime = System.currentTimeMillis()
        
        GitHelperLogger.info("Executing Spotless for module: ${module.displayName}")

        try {
            // Build command line
            val filesParam = files.joinToString(",")
            val commandLine = GeneralCommandLine()
                .withExePath(mavenExecutable)
                .withParameters("spotless:apply", "-f", module.pomFile.absolutePath, "-Dspotless.includes=$filesParam", "-X")
                .withWorkDirectory(module.pomFile.parentFile)

            GitHelperLogger.info("Executing command for ${module.displayName}: ${commandLine.commandLineString}")

            // Execute command
            val success = executeCommand(commandLine, module, progressIndicator, timeoutMinutes, moduleIndex, totalModules)
            val duration = System.currentTimeMillis() - startTime

            return ModuleExecutionResult(module, success, duration)

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            GitHelperLogger.error("Failed to execute Spotless for module: ${module.displayName}", e)
            return ModuleExecutionResult(module, false, duration, e.message)
        }
    }

    /**
     * Execute a single command
     */
    private fun executeCommand(
        commandLine: GeneralCommandLine,
        module: ModuleAnalyzer.MavenModule,
        progressIndicator: ProgressIndicator?,
        timeoutMinutes: Long,
        moduleIndex: Int,
        totalModules: Int
    ): Boolean {
        val processHandler = OSProcessHandler(commandLine)
        
        synchronized(activeProcesses) {
            activeProcesses.add(processHandler)
        }

        try {
            var success = false
            val outputLines = mutableListOf<String>()
            val latch = CountDownLatch(1)
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutMinutes * 60 * 1000L

            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trim()
                    if (text.isNotEmpty()) {
                        outputLines.add(text)

                        // Record output
                        when (outputType) {
                            ProcessOutputTypes.STDOUT -> GitHelperLogger.mavenOutput("[${module.displayName}] STDOUT: $text")
                            ProcessOutputTypes.STDERR -> GitHelperLogger.mavenOutput("[${module.displayName}] STDERR: $text")
                            else -> GitHelperLogger.mavenOutput("[${module.displayName}] OTHER: $text")
                        }

                        // Check build status
                        if (outputType == ProcessOutputTypes.STDOUT) {
                            when {
                                text.contains("BUILD SUCCESS") -> {
                                    success = true
                                    GitHelperLogger.info("Module ${module.displayName} build completed successfully")
                                }
                                text.contains("BUILD FAILURE") -> {
                                    GitHelperLogger.error("Module ${module.displayName} build failed")
                                }
                            }
                        }

                        // Check for cancellation and timeout
                        val elapsed = System.currentTimeMillis() - startTime
                        if (cancelled.get() || progressIndicator?.isCanceled == true) {
                            GitHelperLogger.info("Module ${module.displayName} execution cancelled")
                            processHandler.destroyProcess()
                            return
                        }

                        if (elapsed > timeoutMs) {
                            GitHelperLogger.warn("Module ${module.displayName} execution timed out")
                            processHandler.destroyProcess()
                            return
                        }

                        // Update progress indicator
                        progressIndicator?.let { indicator ->
                            val moduleProgress = (moduleIndex.toDouble() / totalModules) + (elapsed.toDouble() / (timeoutMs * totalModules))
                            indicator.fraction = minOf(moduleProgress, 1.0)
                            indicator.text2 = "Processing ${module.displayName}... (${elapsed / 1000}s)"
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode
                    val elapsed = System.currentTimeMillis() - startTime
                    GitHelperLogger.info("Module ${module.displayName} process terminated with exit code: $exitCode after ${elapsed}ms")

                    if (cancelled.get() || progressIndicator?.isCanceled == true) {
                        success = false
                    } else if (elapsed > timeoutMs) {
                        success = false
                    } else {
                        success = success && exitCode == 0
                        if (!success) {
                            GitHelperLogger.error("Module ${module.displayName} failed with exit code: $exitCode")
                            GitHelperLogger.error("Last 5 output lines:")
                            outputLines.takeLast(5).forEach { line ->
                                GitHelperLogger.error("  $line")
                            }
                        }
                    }
                    latch.countDown()
                }
            })

            processHandler.startNotify()

            // Wait for process to complete
            val maxWaitTime = timeoutMinutes + 1
            if (!latch.await(maxWaitTime, TimeUnit.MINUTES)) {
                GitHelperLogger.error("Module ${module.displayName} did not complete within ${maxWaitTime} minutes")
                processHandler.destroyProcess()
                return false
            }

            return success

        } finally {
            synchronized(activeProcesses) {
                activeProcesses.remove(processHandler)
            }
        }
    }

    /**
     * Cancel all currently executing tasks
     */
    fun cancel() {
        GitHelperLogger.info("Cancelling all parallel executions")
        cancelled.set(true)
        
        synchronized(activeProcesses) {
            activeProcesses.forEach { process ->
                if (!process.isProcessTerminated) {
                    process.destroyProcess()
                }
            }
        }
        
        executorService.shutdownNow()
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        cancel()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                GitHelperLogger.warn("Executor service did not terminate gracefully")
            }
        } catch (e: InterruptedException) {
            GitHelperLogger.error("Interrupted while waiting for executor service termination", e)
        }
    }
}