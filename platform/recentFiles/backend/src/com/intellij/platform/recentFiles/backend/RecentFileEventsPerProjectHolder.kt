// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.recentFiles.backend

import com.intellij.codeInsight.daemon.HighlightingPassesCache
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.recentFiles.shared.RecentFilesBackendRequest
import com.intellij.platform.recentFiles.shared.RecentFilesEvent
import kotlinx.coroutines.flow.*

@Service(Service.Level.PROJECT)
internal class RecentFileEventsPerProjectHolder {
  private val recentFiles = MutableSharedFlow<RecentFilesEvent>()

  fun getRecentFiles(): Flow<RecentFilesEvent> {
    return recentFiles
  }

  suspend fun emitRecentFiles(searchRequest: RecentFilesBackendRequest.NewSearchWithParameters) {
    recentFiles.emit(RecentFilesEvent.AllItemsRemoved())
    recentFiles.emitAll(fetchRecentFiles(searchRequest))
  }

  suspend fun hideAlreadyShownFile(hideFileRequest: RecentFilesBackendRequest.HideFile) {
    val project = hideFileRequest.projectId.findProjectOrNull() ?: return
    val virtualFile = hideFileRequest.fileToHide.virtualFileId.virtualFile() ?: return
    EditorHistoryManager.getInstance(project).removeFile(virtualFile)
    recentFiles.emit(RecentFilesEvent.ItemRemoved(hideFileRequest.fileToHide))
  }

  fun scheduleRehighlightUnopenedFiles(projectId: ProjectId) {
    val project = projectId.findProjectOrNull() ?: return
    HighlightingPassesCache.getInstance(project).schedule(getNotOpenedRecentFiles(project))
  }

  private fun getNotOpenedRecentFiles(project: Project): List<VirtualFile> {
    val recentFiles = EditorHistoryManager.getInstance(project).fileList
    val openFiles = FileEditorManager.getInstance(project).openFiles
    return recentFiles.subtract(openFiles.toSet()).toList()
  }

  private fun fetchRecentFiles(filter: RecentFilesBackendRequest.NewSearchWithParameters): Flow<RecentFilesEvent> {
    return flow {
      LOG.debug("Started fetching recent files")
      val project = filter.projectId.findProjectOrNull() ?: return@flow

      val collectedFiles = readAction {
        getFilesToShow(project, filter.onlyEdited, filter.pinned)
      }
      LOG.debug("Collected ${collectedFiles.size} recent files")

      emitAll(
        collectedFiles
          .asFlow()
          .map { RecentFilesEvent.ItemAdded(it) }
      )
    }
  }

  companion object {
    fun getInstance(project: Project): RecentFileEventsPerProjectHolder {
      return project.service<RecentFileEventsPerProjectHolder>()
    }
  }
}

private val LOG by lazy { fileLogger() }