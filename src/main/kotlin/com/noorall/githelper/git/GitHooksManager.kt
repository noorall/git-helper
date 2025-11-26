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
            if (preCommitFile.exists() && !isPreCommitHookInstalled()) {
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

            // Delete our hook with retry mechanism
            var deleteSuccess = false
            var attempts = 0
            val maxAttempts = 3

            while (!deleteSuccess && attempts < maxAttempts) {
                attempts++
                deleteSuccess = preCommitFile.delete()

                if (!deleteSuccess) {
                    GitHelperLogger.warn("Failed to delete pre-commit hook file on attempt $attempts: ${preCommitFile.absolutePath}")
                    if (attempts < maxAttempts) {
                        // Wait a bit before retrying
                        Thread.sleep(10)
                    }
                }
            }

            if (!deleteSuccess) {
                GitHelperLogger.error("Failed to delete pre-commit hook file after $maxAttempts attempts: ${preCommitFile.absolutePath}")
                return false
            }

            // Restore backup hook (if exists)
            if (backupFile.exists()) {
                if (!backupFile.renameTo(preCommitFile)) {
                    GitHelperLogger.error("Failed to restore backup hook from: ${backupFile.absolutePath}")
                    return false
                }
                GitHelperLogger.info("Original pre-commit hook has been restored from backup")
            } else {
                GitHelperLogger.info("GitHelper pre-commit hook uninstalled successfully")
            }
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
            content.contains("GitHelper Spotless Pre-commit Hook") ||
            content.contains("GitHelper Spotless Pre-commit Hook with Async Communication")
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
        val settings = com.noorall.githelper.settings.GitHelperSettings.getInstance()
        val hookTimeout = settings.hookTimeout

        return """#!/bin/sh
# GitHelper Spotless Pre-commit Hook with Async Communication
# This hook works with IDEA plugin to format Java files using async status monitoring

# Global timeout for the entire hook execution
HOOK_START_TIME=$(date +%s)
HOOK_TIMEOUT=${hookTimeout}  # Timeout from plugin settings
HOOK_MAX_TIMEOUT=600  # 10 minutes maximum

# Validate hook timeout
if [ ${'$'}HOOK_TIMEOUT -gt ${'$'}HOOK_MAX_TIMEOUT ]; then
    HOOK_TIMEOUT=${'$'}HOOK_MAX_TIMEOUT
    echo "GitHelper: Hook timeout capped at ${'$'}HOOK_MAX_TIMEOUT seconds"
elif [ ${'$'}HOOK_TIMEOUT -lt 30 ]; then
    HOOK_TIMEOUT=30
    echo "GitHelper: Hook timeout minimum is 30 seconds"
fi

echo "GitHelper: Starting pre-commit formatting process (global timeout: ${'$'}HOOK_TIMEOUT seconds)..."

# Get list of staged Java files
STAGED_JAVA_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.java$')

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
    local timeout=${'$'}HOOK_TIMEOUT     # Default from plugin settings
    local check_interval=1        # 1 second check interval
    
    local elapsed=0
    
    echo "GitHelper: Waiting for IDEA plugin to complete formatting (timeout: ${'$'}timeout seconds)..."
    
    while [ ${'$'}elapsed -lt ${'$'}timeout ]; do
        if [ -f "${'$'}STATUS_FILE" ]; then
            # Read status from file
            if grep -q "status=SUCCESS" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting completed successfully"
                return 0
            elif grep -q "status=FAILED" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting failed"
                return 1
            elif grep -q "status=CANCELLED" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting was cancelled"
                return 1
            elif grep -q "status=TIMEOUT" "${'$'}STATUS_FILE"; then
                echo "GitHelper: IDEA plugin formatting timed out"
                return 1
            fi
        fi
        sleep ${'$'}check_interval
        elapsed=$((elapsed + check_interval))
    done
    echo "GitHelper: Timeout after ${'$'}timeout seconds waiting for IDEA plugin"
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
    # Wait for IDEA plugin to start
    sleep 3

    # Check if IDEA plugin is available and running
    if [ -f "${'$'}LOCK_FILE" ] || [ -f "${'$'}STATUS_FILE" ]; then
        # IDEA plugin is handling the formatting
        echo "GitHelper: IDEA plugin detected, using async communication mode..."
        
        if wait_for_idea_plugin; then
            echo "GitHelper: Re-staging formatted files..."
            echo "${'$'}STAGED_JAVA_FILES" | xargs git add
            cleanup_status_files
            echo "GitHelper: Commit ready to proceed"
            exit 0
        else
            echo "GitHelper: IDEA plugin formatting failed"
            cleanup_status_files
            # Handle fallback - no standalone Maven execution
            handle_no_idea_plugin
            echo "GitHelper: Commit proceeding without formatting"
            exit 0
        fi
    else
        # No IDEA plugin detected
        echo "GitHelper: No IDEA plugin detected"
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