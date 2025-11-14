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

package com.noorall.githelper.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "GitHelperSettings",
    storages = [Storage("GitHelperSettings.xml")]
)
@Service(Service.Level.APP)
class GitHelperSettings : PersistentStateComponent<GitHelperSettings> {
    
    var spotlessEnabled: Boolean = true
    var showProgressDialog: Boolean = true
    var mavenExecutable: String = "mvn"
    var useIntellijHandler: Boolean = false  // Default changed to false
    var useGitHooks: Boolean = true  // New: Default to use Git Hooks
    var autoInstallHooksForNewProjects: Boolean = true  // New: Auto-install option

    override fun getState(): GitHelperSettings = this
    
    override fun loadState(state: GitHelperSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        fun getInstance(): GitHelperSettings {
            return ApplicationManager.getApplication().getService(GitHelperSettings::class.java)
        }
    }
}