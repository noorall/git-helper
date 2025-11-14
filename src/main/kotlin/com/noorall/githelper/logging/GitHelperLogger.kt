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

package com.noorall.githelper.logging

import com.intellij.openapi.diagnostic.Logger

object GitHelperLogger {
    private val LOG = Logger.getInstance(GitHelperLogger::class.java)
    
    fun info(message: String) {
        LOG.info("[GitHelper] $message")
        println("[GitHelper INFO] $message")
    }
    
    fun debug(message: String) {
        LOG.debug("[GitHelper] $message")
        println("[GitHelper DEBUG] $message")
    }
    
    fun warn(message: String) {
        LOG.warn("[GitHelper] $message")
        println("[GitHelper WARN] $message")
    }
    
    fun error(message: String, throwable: Throwable? = null) {
        LOG.error("[GitHelper] $message", throwable)
        println("[GitHelper ERROR] $message")
        throwable?.printStackTrace()
    }
    
    fun mavenOutput(line: String) {
        LOG.info("[GitHelper Maven] $line")
        println("[GitHelper Maven] $line")
    }
}