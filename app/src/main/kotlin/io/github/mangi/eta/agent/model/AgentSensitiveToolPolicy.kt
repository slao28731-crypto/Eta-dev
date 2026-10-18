package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    /**
     * MCP 服务器管理工具虽然也以 `mcp_` 开头，但它们是**配置动作**，不是 MCP 远端工具调用：
     * 参数里没有业务数据，结果里只有服务器元信息和工具名单。
     *
     * 如果不排除，`mcp_add_server` 的结果会被 transcript 替换成
     * 「敏感工具……未写入持久会话」，连带把 `modelTools`（下一轮要调用的工具名）一起丢掉，
     * 模型换会话后就不知道自己刚加过什么、该调什么名字。这是踩过的坑。
     *
     * 真正的远端 MCP 工具名形如 `mcp_<serverId前8位>_<工具名>_<sha256前8位>`，
     * 不会命中下面这 6 个精确名字。
     */
    private val mcpManagementTools = setOf(
        "mcp_list_servers",
        "mcp_add_server",
        "mcp_remove_server",
        "mcp_refresh_server",
        "mcp_set_server_enabled",
        "mcp_set_tool_enabled",
    )

    fun isSensitive(toolName: String): Boolean {
        if (toolName in mcpManagementTools) return false
        return toolName.startsWith("mcp_") ||
            toolName in sensitiveTools ||
            toolName in io.github.mangi.eta.agent.context.PersonalSearchTools.names ||
            toolName in AgentPhoneToolCatalog.names
    }

    private val sensitiveTools = setOf(
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_system_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
