// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.recentFiles.backend

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.recentFiles.shared.FileSwitcherApi
import com.intellij.platform.recentFiles.shared.RecentFilesBackendRequest
import com.intellij.platform.recentFiles.shared.RecentFilesEvent
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private class FilesSwitcherBackendApi : FileSwitcherApi {

  override suspend fun getRecentFileEvents(projectId: ProjectId): Flow<RecentFilesEvent> {
    return getProjectFileEventsHolderOrNull(projectId)?.getRecentFiles() ?: emptyFlow()
  }

  override suspend fun updateRecentFilesBackendState(request: RecentFilesBackendRequest): Boolean {
    val perProjectRecentFileEventsHolder = getProjectFileEventsHolderOrNull(request.projectId) ?: return false
    when (request) {
      is RecentFilesBackendRequest.NewSearchWithParameters -> perProjectRecentFileEventsHolder.emitRecentFiles(request)
      is RecentFilesBackendRequest.HideFile -> perProjectRecentFileEventsHolder.hideAlreadyShownFile(request)
      is RecentFilesBackendRequest.ScheduleRehighlighting -> perProjectRecentFileEventsHolder.scheduleRehighlightUnopenedFiles(request.projectId)
    }
    return true
  }

  private fun getProjectFileEventsHolderOrNull(projectId: ProjectId): RecentFileEventsPerProjectHolder? {
    val project = projectId.findProjectOrNull()
    if (project == null) {
      thisLogger().warn("Unable to resolve project from projectId, recent files request will be ignored for projectId: $projectId")
      return null
    }
    return RecentFileEventsPerProjectHolder.getInstance(project)
  }
}

private class RecentFilesBackendApiProvider : RemoteApiProvider {
  override fun RemoteApiProvider.Sink.remoteApis() {
    remoteApi(remoteApiDescriptor<FileSwitcherApi>()) {
      FilesSwitcherBackendApi()
    }
  }
}