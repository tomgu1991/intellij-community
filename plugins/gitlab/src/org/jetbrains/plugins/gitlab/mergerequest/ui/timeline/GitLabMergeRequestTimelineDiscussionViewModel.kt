// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.timeline

import com.intellij.collaboration.async.mapCaching
import com.intellij.collaboration.async.mapScoped
import com.intellij.collaboration.async.modelFlow
import com.intellij.collaboration.ui.codereview.timeline.CollapsibleTimelineItemViewModel
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.childScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO
import org.jetbrains.plugins.gitlab.mergerequest.data.*
import org.jetbrains.plugins.gitlab.ui.comment.*
import java.util.*

interface GitLabMergeRequestTimelineDiscussionViewModel :
  GitLabMergeRequestTimelineItemViewModel,
  CollapsibleTimelineItemViewModel {
  val author: Flow<GitLabUserDTO>

  val diffVm: Flow<GitLabDiscussionDiffViewModel?>

  val mainNote: Flow<GitLabNoteViewModel>
  val replies: Flow<List<GitLabNoteViewModel>>

  val repliesFolded: Flow<Boolean>

  val resolveVm: GitLabDiscussionResolveViewModel?
  val replyVm: GitLabDiscussionReplyViewModel?

  fun setRepliesFolded(folded: Boolean)

  suspend fun destroy()
}

private val LOG = logger<GitLabMergeRequestTimelineDiscussionViewModel>()

@OptIn(ExperimentalCoroutinesApi::class)
class GitLabMergeRequestTimelineDiscussionViewModelImpl(
  parentCs: CoroutineScope,
  currentUser: GitLabUserDTO,
  private val mr: GitLabMergeRequest,
  discussion: GitLabMergeRequestDiscussion
) : GitLabMergeRequestTimelineDiscussionViewModel {

  private val cs = parentCs.childScope(CoroutineExceptionHandler { _, e -> LOG.warn(e) })

  override val mainNote: Flow<GitLabNoteViewModel> = discussion.notes
    .map { it.first() }
    .distinctUntilChangedBy { it.id }
    .mapScoped { GitLabNoteViewModelImpl(this, it, flowOf(true)) }
    .modelFlow(cs, LOG)

  override val id: String = discussion.id
  override val author: Flow<GitLabUserDTO> = mainNote.map { it.author }

  private val _repliesFolded = MutableStateFlow(true)
  override val repliesFolded: Flow<Boolean> = _repliesFolded.asStateFlow()

  override val replies: Flow<List<GitLabNoteViewModel>> = discussion.notes
    .map { it.drop(1) }
    .mapCaching(
      GitLabNote::id,
      { cs, note -> GitLabNoteViewModelImpl(cs, note, flowOf(false)) },
      GitLabNoteViewModelImpl::destroy
    )
    .modelFlow(cs, LOG)

  override val resolveVm: GitLabDiscussionResolveViewModel? =
    if (discussion.resolvable) GitLabDiscussionResolveViewModelImpl(cs, discussion) else null

  override val collapsible: Flow<Boolean> = resolveVm?.resolved ?: flowOf(false)

  private val _collapsed: MutableStateFlow<Boolean> = MutableStateFlow(true)
  override val collapsed: Flow<Boolean> = combine(collapsible, _collapsed) { collapsible, collapsed -> collapsible && collapsed }

  override val replyVm: GitLabDiscussionReplyViewModel? =
    if (discussion.canAddNotes) GitLabDiscussionReplyViewModelImpl(cs, currentUser, discussion) else null

  override val diffVm: Flow<GitLabDiscussionDiffViewModel?> =
    discussion.notes
      .flatMapLatest { it.first().position }
      .mapScoped { pos -> pos?.let { GitLabDiscussionDiffViewModelImpl(this, mr, it) } }
      .modelFlow(cs, LOG)

  init {
    val resolvedFlow = resolveVm?.resolved
    if (resolvedFlow != null) {
      cs.launch(start = CoroutineStart.UNDISPATCHED) {
        resolvedFlow.collect {
          setCollapsed(it)
        }
      }
    }
  }

  override fun setCollapsed(collapsed: Boolean) {
    _collapsed.value = collapsed
    if (collapsed) {
      _repliesFolded.value = true
    }
  }

  override fun setRepliesFolded(folded: Boolean) {
    _repliesFolded.value = folded
    if (!folded) {
      _collapsed.value = false
    }
  }

  override suspend fun destroy() {
    try {
      cs.coroutineContext[Job]!!.cancelAndJoin()
    }
    catch (e: CancellationException) {
      // ignore, cuz we don't want to cancel the invoker
    }
  }
}

class GitLabMergeRequestTimelineDraftDiscussionViewModel(
  parentCs: CoroutineScope,
  currentUser: GitLabUserDTO,
  private val mr: GitLabMergeRequest,
  draftNote: GitLabMergeRequestNote
) : GitLabMergeRequestTimelineDiscussionViewModel {

  private val cs = parentCs.childScope(CoroutineExceptionHandler { _, e -> LOG.warn(e) })

  override val mainNote: Flow<GitLabNoteViewModel> =
    flowOf(GitLabNoteViewModelImpl(cs, draftNote, flowOf(true)))

  override val id: String = draftNote.id
  override val author: Flow<GitLabUserDTO> = flowOf(currentUser)

  private val _repliesFolded = MutableStateFlow(true)
  override val repliesFolded: Flow<Boolean> = _repliesFolded.asStateFlow()

  override val replies: Flow<List<GitLabNoteViewModel>> = flowOf(emptyList())

  override val resolveVm: GitLabDiscussionResolveViewModel? = null

  override val collapsible: Flow<Boolean> = flowOf(false)
  override val collapsed: Flow<Boolean> = flowOf(false)

  override val replyVm: GitLabDiscussionReplyViewModel? = null

  override val diffVm: Flow<GitLabDiscussionDiffViewModel?> =
    draftNote.position.map { pos -> pos?.let { GitLabDiscussionDiffViewModelImpl(cs, mr, it) } }
      .modelFlow(cs, LOG)

  override fun setCollapsed(collapsed: Boolean) = Unit

  override fun setRepliesFolded(folded: Boolean) = Unit

  override suspend fun destroy() {
    try {
      cs.coroutineContext[Job]!!.cancelAndJoin()
    }
    catch (e: CancellationException) {
      // ignore, cuz we don't want to cancel the invoker
    }
  }
}