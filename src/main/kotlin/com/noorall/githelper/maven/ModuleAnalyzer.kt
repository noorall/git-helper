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

import com.intellij.openapi.project.Project
import com.noorall.githelper.logging.GitHelperLogger
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Maven 模块分析器，用于发现项目中的所有 Maven 模块并分析文件与模块的关系
 */
class ModuleAnalyzer(private val project: Project) {

    data class MavenModule(
        val path: String,           // Module relative path, e.g. "a", "b", "" (root directory)
        val pomFile: File,          // pom.xml file
        val isRoot: Boolean = false // Whether this is the root module
    ) {
        val displayName: String
            get() = if (isRoot) "root" else path
    }

    data class ExecutionPlan(
        val modules: List<MavenModule>,
        val shouldExecuteRoot: Boolean = false
    )

    private val projectBasePath: String by lazy {
        project.basePath ?: throw IllegalStateException("Project base path is null")
    }

    private val projectBaseDir: File by lazy {
        File(projectBasePath)
    }

    /**
     * Discover all Maven modules in the project
     */
    fun discoverModules(): List<MavenModule> {
        GitHelperLogger.info("Starting module discovery in: $projectBasePath")
        
        val modules = mutableListOf<MavenModule>()
        val visited = mutableSetOf<String>()
        
        // Discover all pom.xml files
        discoverPomFiles(projectBaseDir, "", modules, visited)

        // Sort by path depth, root directory first
        modules.sortBy { it.path.count { char -> char == '/' } }
        
        GitHelperLogger.info("Discovered ${modules.size} Maven modules:")
        modules.forEach { module ->
            GitHelperLogger.info("  - ${module.displayName}: ${module.pomFile.absolutePath}")
        }
        
        return modules
    }

    /**
     * 递归发现 pom.xml 文件
     */
    private fun discoverPomFiles(
        dir: File, 
        relativePath: String, 
        modules: MutableList<MavenModule>,
        visited: MutableSet<String>
    ) {
        if (!dir.isDirectory || visited.contains(dir.absolutePath)) {
            return
        }
        
        visited.add(dir.absolutePath)
        
        // Check if current directory has pom.xml
        val pomFile = File(dir, "pom.xml")
        if (pomFile.exists()) {
            val module = MavenModule(
                path = relativePath,
                pomFile = pomFile,
                isRoot = relativePath.isEmpty()
            )
            modules.add(module)
            GitHelperLogger.debug("Found Maven module: ${module.displayName} at ${pomFile.absolutePath}")
        }
        
        // Recursively search subdirectories
        dir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory && !shouldSkipDirectory(subDir.name)) {
                val subPath = if (relativePath.isEmpty()) subDir.name else "$relativePath/${subDir.name}"
                discoverPomFiles(subDir, subPath, modules, visited)
            }
        }
    }

    /**
     * 判断是否应该跳过某个目录
     */
    private fun shouldSkipDirectory(dirName: String): Boolean {
        return dirName.startsWith(".") || 
               dirName == "target" || 
               dirName == "node_modules" ||
               dirName == "build" ||
               dirName == "out"
    }

    /**
     * Analyze modules that need Spotless execution based on the list of changed files
     */
    fun analyzeAffectedModules(changedFiles: List<String>): ExecutionPlan {
        GitHelperLogger.info("Analyzing affected modules for ${changedFiles.size} changed files")
        
        val allModules = discoverModules()
        if (allModules.isEmpty()) {
            GitHelperLogger.warn("No Maven modules found")
            return ExecutionPlan(emptyList())
        }
        
        val affectedModules = mutableSetOf<MavenModule>()
        
        for (filePath in changedFiles) {
            GitHelperLogger.debug("Analyzing file: $filePath")
            
            // Check if file is in root directory
            if (isInRootDirectory(filePath)) {
                GitHelperLogger.info("File $filePath is in root directory, will execute root module only")
                val rootModule = allModules.find { it.isRoot }
                return if (rootModule != null) {
                    ExecutionPlan(listOf(rootModule), shouldExecuteRoot = true)
                } else {
                    GitHelperLogger.warn("Root module not found")
                    ExecutionPlan(emptyList())
                }
            }
            
            // Find the module that the file belongs to
            val module = findModuleForFile(filePath, allModules)
            if (module != null) {
                affectedModules.add(module)
                GitHelperLogger.debug("File $filePath belongs to module: ${module.displayName}")
            } else {
                GitHelperLogger.warn("No module found for file: $filePath")
            }
        }
        
        val result = ExecutionPlan(affectedModules.toList().sortedBy { it.path })
        GitHelperLogger.info("Execution plan: ${result.modules.size} modules affected")
        result.modules.forEach { module ->
            GitHelperLogger.info("  - Will execute: ${module.displayName}")
        }
        
        return result
    }

    /**
     * 检查文件是否在根目录（不在任何子模块中）
     */
    private fun isInRootDirectory(filePath: String): Boolean {
        // If file path doesn't contain '/', it's in the root directory
        return !filePath.contains('/')
    }

    /**
     * 查找文件所属的模块
     */
    private fun findModuleForFile(filePath: String, modules: List<MavenModule>): MavenModule? {
        // Sort by path depth in descending order, prioritize matching deeper modules
        val sortedModules = modules.sortedByDescending { it.path.count { char -> char == '/' } }
        
        for (module in sortedModules) {
            if (module.isRoot) continue // Skip root module, root module is handled in isInRootDirectory
            
            if (filePath.startsWith(module.path + "/")) {
                return module
            }
        }
        
        return null
    }

    /**
     * 获取模块的执行参数
     */
    fun getExecutionArgs(module: MavenModule): List<String> {
        return listOf("-f", module.pomFile.absolutePath)
    }

    /**
     * 验证模块是否有效（pom.xml 存在且可读）
     */
    fun validateModule(module: MavenModule): Boolean {
        return module.pomFile.exists() && module.pomFile.canRead()
    }
}