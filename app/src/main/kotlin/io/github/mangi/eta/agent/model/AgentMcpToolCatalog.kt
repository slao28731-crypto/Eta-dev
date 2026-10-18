package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器管理工具的 Schema 声明。
 *
 * 这些工具让模型自己完成「用户说一句 → 服务加好」的闭环，不需要用户进设置页手动操作。
 * 执行逻辑在 [io.github.mangi.eta.agent.tool.AgentLocalTools]。
 *
 * 设计取舍：
 * - 新增服务器默认启用全部工具（用户选择「加完就能用」）。否则加完还要手动逐项开启，功能等于半残。
 * - bearerToken 允许作为参数传入，但返回值一律打码回显。工具参数会完整进入对话历史，
 *   这是现有 transcript 机制的既有行为，改不了；能做的只是不在结果里二次暴露。
 * - serverId 由数据库生成（UUID），而 MCP 工具在模型侧的名字是
 *   `mcp_<serverId前8位>_<工具名>_<sha256前8位>`，所以 add 的返回值里必须带上这一轮算不出来的
 *   未来工具名清单，否则模型下一轮不知道该调什么。
 */
internal object AgentMcpToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(listTool())
            .put(addTool())
            .put(removeTool())
            .put(refreshTool())
            .put(setServerEnabledTool())
            .put(setToolEnabledTool())
    }

    private fun listTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_list_servers",
        description = "List configured MCP servers with id, name, url, enabled state, and their tool " +
            "names. Use this before adding a server to avoid registering the same endpoint twice, " +
            "and to obtain a serverId for mcp_remove_server / mcp_refresh_server.",
        parameters = schema(
            JSONObject()
                .put(
                    "includeTools",
                    JSONObject()
                        .put("type", "boolean")
                        .put("description", "Include each server's discovered tool names. Default true."),
                ),
        ),
    )

    private fun addTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_add_server",
        description = "Register a new MCP server (Streamable HTTP only) and discover its tools. " +
            "The server is enabled immediately and all discovered tools are enabled by default. " +
            "Tools become callable on the NEXT turn, not the current one. " +
            "Use mcp_list_servers first to check the endpoint is not already configured.",
        parameters = schema(
            JSONObject()
                .put(
                    "name",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200)
                        .put("description", "Display name shown in settings and in MCP tool descriptions."),
                )
                .put(
                    "url",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 2_000)
                        .put(
                            "description",
                            "Absolute http(s) URL of the MCP Streamable HTTP endpoint. " +
                                "stdio based MCP servers are not supported by this app.",
                        ),
                )
                .put(
                    "bearerToken",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 4_000)
                        .put(
                            "description",
                            "Optional Bearer token for authenticated servers. Omit for public " +
                                "servers. The token is stored encrypted; it is never echoed back " +
                                "in the tool result.",
                        ),
                ),
        ).put("required", JSONArray().put("name").put("url")),
    )

    private fun removeTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_remove_server",
        description = "Delete a configured MCP server and its stored credential. " +
            "Also removes its tools from the next turn's tool list. " +
            "Confirm the serverId with mcp_list_servers before calling.",
        parameters = schema(
            JSONObject()
                .put(
                    "serverId",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200)
                        .put("description", "Exact id from mcp_list_servers. Never infer it from the name."),
                ),
        ).put("required", JSONArray().put("serverId")),
    )

    private fun refreshTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_refresh_server",
        description = "Reconnect to an MCP server and re-read its tool list. Use after the remote " +
            "server added or renamed tools. Tools that disappeared are dropped; " +
            "newly discovered tools start DISABLED, matching the settings screen behaviour.",
        parameters = schema(
            JSONObject()
                .put(
                    "serverId",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200)
                        .put("description", "Exact id from mcp_list_servers."),
                ),
        ).put("required", JSONArray().put("serverId")),
    )

    private fun setServerEnabledTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_set_server_enabled",
        description = "Turn a whole MCP server on or off without deleting it. " +
            "A disabled server contributes no tools to the next turn.",
        parameters = schema(
            JSONObject()
                .put(
                    "serverId",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200)
                        .put("description", "Exact id from mcp_list_servers."),
                )
                .put(
                    "enabled",
                    JSONObject()
                        .put("type", "boolean")
                        .put("description", "true to enable the server, false to disable it."),
                ),
        ).put("required", JSONArray().put("serverId").put("enabled")),
    )

    private fun setToolEnabledTool(): JSONObject = AgentToolSchema.function(
        name = "mcp_set_tool_enabled",
        description = "Enable or disable one specific tool of an MCP server. " +
            "Use it to block a single dangerous tool while keeping the rest of the server usable.",
        parameters = schema(
            JSONObject()
                .put(
                    "serverId",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200)
                        .put("description", "Exact id from mcp_list_servers."),
                )
                .put(
                    "toolName",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", 300)
                        .put(
                            "description",
                            "The MCP tool's original name as returned by discovery " +
                                "(not the prefixed model-facing name).",
                        ),
                )
                .put(
                    "enabled",
                    JSONObject()
                        .put("type", "boolean")
                        .put("description", "true to enable the tool, false to disable it."),
                ),
        ).put("required", JSONArray().put("serverId").put("toolName").put("enabled")),
    )

    private fun schema(properties: JSONObject): JSONObject =
        JSONObject().put("type", "object").put("properties", properties)
}
