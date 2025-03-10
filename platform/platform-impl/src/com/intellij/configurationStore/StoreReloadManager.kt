// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.configurationStore

import com.intellij.openapi.components.StateStorage
import com.intellij.openapi.components.impl.stores.IComponentStore
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.ApiStatus

interface StoreReloadManager {
  companion object {
    fun getInstance(project: Project) = project.service<StoreReloadManager>()
  }

  fun reloadProject()

  fun blockReloadingProjectOnExternalChanges()

  fun unblockReloadingProjectOnExternalChanges()

  @ApiStatus.Internal
  fun isReloadBlocked(): Boolean

  @ApiStatus.Internal
  fun scheduleProcessingChangedFiles()

  fun saveChangedProjectFile(file: VirtualFile)

  suspend fun reloadChangedStorageFiles()

  fun storageFilesChanged(store: IComponentStore, storages: Collection<StateStorage>)
}