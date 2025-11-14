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

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.noorall.githelper.logging.GitHelperLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Git Hooks Manager
 *
 * Responsible for installing and managing Git pre-commit hooks, implementing true Git-level code formatting
 * This is a more elegant and standard solution that avoids the complexity of IntelliJ UI threads
 */
class GitHooksManager(private val project: Project) {

    companion object {
        private const val PRE_COMMIT_HOOK_NAME = "pre-commit"
        private const val BACKUP_SUFFIX = ".githelper-backup"
    }

    /**
     * Install Git pre-commit hook
     */
    fun installPreCommitHook(): Boolean {
        try {
            val gitDir = findGitDirectory() ?: return false
            val hooksDir = File(gitDir, "hooks")
            
            if (!hooksDir.exists()) {
                hooksDir.mkdirs()
            }

            val preCommitFile = File(hooksDir, PRE_COMMIT_HOOK_NAME)
            
            // Backup existing hook (if exists)
            if (preCommitFile.exists()) {
                backupExistingHook(preCommitFile)
            }

            // Create new pre-commit hook
            val hookContent = generatePreCommitHookContent()
            Files.write(preCommitFile.toPath(), hookContent.toByteArray(), 
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            
            // Set execution permissions
            preCommitFile.setExecutable(true)
            
            GitHelperLogger.info("Successfully installed Git pre-commit hook at: ${preCommitFile.absolutePath}")
            return true
            
        } catch (e: Exception) {
            GitHelperLogger.error("Failed to install Git pre-commit hook", e)
            return false
        }
    }

    /**
     * Uninstall Git pre-commit hook
     */
    fun uninstallPreCommitHook(): Boolean {
        try {
            val gitDir = findGitDirectory() ?: return false
            val hooksDir = File(gitDir, "hooks")
            val preCommitFile = File(hooksDir, PRE_COMMIT_HOOK_NAME)
            val backupFile = File(hooksDir, "$PRE_COMMIT_HOOK_NAME$BACKUP_SUFFIX")

            if (!preCommitFile.exists()) {
                GitHelperLogger.info("No pre-commit hook found to uninstall")
                return true
            }

            // Delete our hook
            preCommitFile.delete()

            // Restore backup hook (if exists)
            if (backupFile.exists()) {
                backupFile.renameTo(preCommitFile)
                GitHelperLogger.info("Restored original pre-commit hook from backup")
            }

            GitHelperLogger.info("Successfully uninstalled Git pre-commit hook")
            return true

        } catch (e: Exception) {
            GitHelperLogger.error("Failed to uninstall Git pre-commit hook", e)
            return false
        }
    }

    /**
     * Check if pre-commit hook is installed
     */
    fun isPreCommitHookInstalled(): Boolean {
        val gitDir = findGitDirectory() ?: return false
        val preCommitFile = File(File(gitDir, "hooks"), PRE_COMMIT_HOOK_NAME)
        
        if (!preCommitFile.exists()) {
            return false
        }

        // Check if this is our installed hook
        return try {
            val content = preCommitFile.readText()
            content.contains("GitHelper Spotless Pre-commit Hook")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Find Git directory
     */
    private fun findGitDirectory(): File? {
        val projectPath = project.basePath ?: return null
        var currentDir = File(projectPath)

        // Search upward for .git directory
        while (currentDir != null) {
            val gitDir = File(currentDir, ".git")
            if (gitDir.exists()) {
                return if (gitDir.isDirectory) {
                    gitDir
                } else {
                    // Handle git worktree case, .git is a file
                    val gitFileContent = gitDir.readText().trim()
                    if (gitFileContent.startsWith("gitdir: ")) {
                        val gitDirPath = gitFileContent.substring(8)
                        File(if (Paths.get(gitDirPath).isAbsolute) gitDirPath else File(currentDir, gitDirPath).absolutePath)
                    } else {
                        null
                    }
                }
            }
            currentDir = currentDir.parentFile
        }
        return null
    }

    /**
     * Backup existing hook
     */
    private fun backupExistingHook(preCommitFile: File) {
        val backupFile = File(preCommitFile.parent, "$PRE_COMMIT_HOOK_NAME$BACKUP_SUFFIX")
        
        // Delete existing backup if it exists
        if (backupFile.exists()) {
            backupFile.delete()
        }
        
        preCommitFile.renameTo(backupFile)
        GitHelperLogger.info("Backed up existing pre-commit hook to: ${backupFile.absolutePath}")
    }

    /**
     * Generate pre-commit hook content
     */
    private fun generatePreCommitHookContent(): String {
        val projectPath = project.basePath ?: ""
        
        return """#!/bin/sh
# GitHelper Spotless Pre-commit Hook with Async Communication
# This hook works with IDEA plugin to format Java files using async status monitoring

# Global timeout for the entire hook execution
HOOK_START_TIME=${'$'}(date +%s)
HOOK_TIMEOUT=${'$'}{GITHELPER_HOOK_TIMEOUT:-900}  # 15 minutes default
HOOK_MAX_TIMEOUT=1800  # 30 minutes maximum

# Validate hook timeout
if [ ${'$'}HOOK_TIMEOUT -gt ${'$'}HOOK_MAX_TIMEOUT ]; then
    HOOK_TIMEOUT=${'$'}HOOK_MAX_TIMEOUT
    echo "GitHelper: Hook timeout capped at ${'$'}HOOK_MAX_TIMEOUT seconds"
elif [ ${'$'}HOOK_TIMEOUT -lt 300 ]; then
    HOOK_TIMEOUT=300
    echo "GitHelper: Hook timeout minimum is 300 seconds"
fi

# Function to check global timeout
check_global_timeout() {
    local current_time=${'$'}(date +%s)
    local elapsed=${'$'}((current_time - HOOK_START_TIME))
    local remaining=${'$'}((HOOK_TIMEOUT - elapsed))
    
    if [ ${'$'}elapsed -ge ${'$'}HOOK_TIMEOUT ]; then
        echo "GitHelper: Global hook timeout reached (${'$'}HOOK_TIMEOUT seconds)"
        echo "GitHelper: You can increase timeout by setting GITHELPER_HOOK_TIMEOUT environment variable"
        cleanup_status_files
        exit 1
    fi
    
    # Warn when approaching timeout
    if [ ${'$'}remaining -le 60 ] && [ ${'$'}((remaining % 20)) -eq 0 ]; then
        echo "GitHelper: Warning - Hook will timeout in ${'$'}remaining seconds"
    fi
}

echo "GitHelper: Starting pre-commit formatting process (global timeout: ${'$'}HOOK_TIMEOUT seconds)..."

# Get list of staged Java files
STAGED_JAVA_FILES=${'$'}(git diff --cached --name-only --diff-filter=ACM | grep '\.java${'$'}')

if [ -z "${'$'}STAGED_JAVA_FILES" ]; then
    echo "GitHelper: No Java files to format"
    exit 0
fi

echo "GitHelper: Found Java files to format:"
echo "${'$'}STAGED_JAVA_FILES"

# Change to project directory
cd "$projectPath"

# Status file paths
STATUS_DIR=".idea/githelper"
STATUS_FILE="${'$'}STATUS_DIR/spotless.status"
LOCK_FILE="${'$'}STATUS_DIR/spotless.lock"

# Function to cleanup status files
cleanup_status_files() {
    if [ -f "${'$'}STATUS_FILE" ]; then
        rm -f "${'$'}STATUS_FILE"
    fi
    if [ -f "${'$'}LOCK_FILE" ]; then
        rm -f "${'$'}LOCK_FILE"
    fi
}

# Function to wait for IDEA plugin status with enhanced timeout handling
wait_for_idea_plugin() {
    # Configurable timeout values
    local default_timeout=180      # 3 minutes default timeout
    local max_timeout=600         # 10 minutes maximum timeout
    local warning_timeout=120     # 2 minutes warning threshold
    local check_interval=1        # 1 second check interval
    local progress_timeout=30     # 30 seconds without progress = stalled
    
    # Try to read timeout from environment or use default
    local timeout=${'$'}{GITHELPER_TIMEOUT:-${'$'}default_timeout}
    
    # Validate timeout range
    if [ ${'$'}timeout -gt ${'$'}max_timeout ]; then
        timeout=${'$'}max_timeout
        echo "GitHelper: Timeout capped at ${'$'}max_timeout seconds"
    elif [ ${'$'}timeout -lt 30 ]; then
        timeout=30
        echo "GitHelper: Timeout minimum is 30 seconds"
    fi
    
    local elapsed=0
    local last_progress_time=0
    local last_progress_value=""
    local warning_shown=false
    
    echo "GitHelper: Waiting for IDEA plugin to complete formatting (timeout: ${'$'}timeout seconds)..."
    
    while [ ${'$'}elapsed -lt ${'$'}timeout ]; do
        # Check global timeout at each iteration
        check_global_timeout
        
        if [ -f "${'$'}STATUS_FILE" ]; then
            # Read status from file
            if grep -q "status=SUCCESS" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting completed successfully"
                return 0
            elif grep -q "status=FAILED" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting failed"
                if grep -q "message=" "${'$'}STATUS_FILE"; then
                    local message=${'$'}(grep "message=" "${'$'}STATUS_FILE" | cut -d'=' -f2-)
                    echo "GitHelper: Error: ${'$'}message"
                fi
                return 1
            elif grep -q "status=CANCELLED" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting was cancelled"
                return 1
            elif grep -q "status=TIMEOUT" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting timed out"
                return 1
            elif grep -q "status=RUNNING" "${'$'}STATUS_FILE"; then
                # Check progress and detect stalls
                if grep -q "progress=" "${'$'}STATUS_FILE"; then
                    local current_progress=${'$'}(grep "progress=" "${'$'}STATUS_FILE" | cut -d'=' -f2)
                    local progress_percent=${'$'}(echo "${'$'}current_progress * 100" | bc 2>/dev/null || echo "0")
                    
                    # Check if progress has changed
                    if [ "${'$'}current_progress" != "${'$'}last_progress_value" ]; then
                        last_progress_time=${'$'}elapsed
                        last_progress_value="${'$'}current_progress"
                        echo "GitHelper: Formatting in progress... ${'$'}{progress_percent%.*}%"
                    else
                        # Check for stalled progress
                        local progress_stall_time=${'$'}((elapsed - last_progress_time))
                        if [ ${'$'}progress_stall_time -gt ${'$'}progress_timeout ]; then
                            echo "GitHelper: Warning - No progress for ${'$'}progress_stall_time seconds, formatting may be stalled"
                            # Consider this a timeout condition
                            echo "GitHelper: Formatting appears to be stalled, timing out..."
                            return 1
                        fi
                    fi
                else
                    echo "GitHelper: Formatting in progress... (no progress info)"
                fi
            elif grep -q "status=STARTING" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin is starting formatting..."
            fi
        else
            # No status file yet, check if we should warn about delay
            if [ ${'$'}elapsed -gt 10 ] && [ ${'$'}elapsed -lt 15 ]; then
                echo "GitHelper: Still waiting for IDEA plugin to start..."
            fi
        fi
        
        # Show warning at warning threshold
        if [ ${'$'}elapsed -eq ${'$'}warning_timeout ] && [ "${'$'}warning_shown" = "false" ]; then
            echo "GitHelper: Warning - Formatting is taking longer than expected (${'$'}warning_timeout seconds)"
            echo "GitHelper: Will timeout in ${'$'}((timeout - elapsed)) seconds if not completed"
            warning_shown=true
        fi
        
        # Show countdown in final 30 seconds
        local remaining=${'$'}((timeout - elapsed))
        if [ ${'$'}remaining -le 30 ] && [ ${'$'}((remaining % 10)) -eq 0 ]; then
            echo "GitHelper: Timeout in ${'$'}remaining seconds..."
        fi
        
        sleep ${'$'}check_interval
        elapsed=${'$'}((elapsed + check_interval))
    done
    
    echo "GitHelper: Timeout after ${'$'}timeout seconds waiting for IDEA plugin"
    echo "GitHelper: You can increase timeout by setting GITHELPER_TIMEOUT environment variable"
    return 1
}

# Function to handle fallback when IDEA plugin is not available
handle_no_idea_plugin() {
    echo "GitHelper: IDEA plugin not detected or failed"
    echo "GitHelper: Spotless formatting should be handled by IDEA plugin only"
    echo "GitHelper: Please ensure IDEA plugin is running and try again"
    echo ""
    echo "GitHelper: Skipping formatting - commit will proceed without formatting"
    echo "GitHelper: Note: Files may not be properly formatted"
    return 0  # Allow commit to proceed
}

# Main execution logic
main() {
    # Initial timeout check
    check_global_timeout
    
    # Check if IDEA plugin is available and running
    if [ -f "${'$'}LOCK_FILE" ] || [ -f "${'$'}STATUS_FILE" ]; then
        # IDEA plugin is handling the formatting
        echo "GitHelper: IDEA plugin detected, using async communication mode..."
        check_global_timeout
        
        if wait_for_idea_plugin; then
            check_global_timeout
            echo "GitHelper: Re-staging formatted files..."
            echo "${'$'}STAGED_JAVA_FILES" | xargs git add
            cleanup_status_files
            echo "GitHelper: Commit ready to proceed"
            exit 0
        else
            echo "GitHelper: IDEA plugin formatting failed"
            cleanup_status_files
            check_global_timeout
            
            # Handle fallback - no standalone Maven execution
            handle_no_idea_plugin
            echo "GitHelper: Commit proceeding without formatting"
            exit 0
        fi
    else
        # No IDEA plugin detected
        echo "GitHelper: No IDEA plugin detected"
        check_global_timeout
        
        # Handle fallback - no standalone Maven execution
        handle_no_idea_plugin
        echo "GitHelper: Commit proceeding without formatting"
        exit 0
    fi
}

# Trap to ensure cleanup on exit
trap cleanup_status_files EXIT

# Run main logic
main
"""
    }

    /**
     * Get hook status information
     */
    fun getHookStatus(): HookStatus {
        val gitDir = findGitDirectory()
        if (gitDir == null) {
            return HookStatus.NO_GIT_REPO
        }

        val preCommitFile = File(File(gitDir, "hooks"), PRE_COMMIT_HOOK_NAME)
        val backupFile = File(File(gitDir, "hooks"), "$PRE_COMMIT_HOOK_NAME$BACKUP_SUFFIX")

        return when {
            !preCommitFile.exists() -> HookStatus.NOT_INSTALLED
            isPreCommitHookInstalled() -> HookStatus.INSTALLED
            else -> HookStatus.OTHER_HOOK_EXISTS
        }
    }

    enum class HookStatus {
        NOT_INSTALLED,      // No pre-commit hook installed
        INSTALLED,          // Our hook is installed
        OTHER_HOOK_EXISTS,  // Another pre-commit hook exists
        NO_GIT_REPO        // Not a Git repository
    }
}