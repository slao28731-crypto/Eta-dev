package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data object CompactContext : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    /** 把当前草稿排进待发送队列，不打断正在执行的 run。 */
    data class QueueMessage(val text: String) : AgentHomeAction
    /** 从待发送队列移除一条。 */
    data class RemoveQueuedMessage(val id: String) : AgentHomeAction
    /** 立刻发出某条排队消息：先取消当前 run，再作为新一轮提交。 */
    data class SendQueuedMessageNow(val id: String) : AgentHomeAction
    /** 展开 / 收起待发送面板。 */
    data class SetQueueExpanded(val expanded: Boolean) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
}
