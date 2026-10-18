package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 管理工具的目录契约。
 *
 * 这里锁死三件容易在后续改动里被无声破坏的事：
 * 1. 只有 mcpManagement=true 时才暴露（默认关闭，避免无谓的 token 占用）；
 * 2. 六个工具的参数必填项与上限，改 Schema 时测试会先炸；
 * 3. 管理工具不能被 [AgentSensitiveToolPolicy] 当成敏感工具 ——
 *    否则结果会被 transcript 脱敏，连带把 modelTools 一起丢掉。
 */
class McpManagementToolCatalogTest {
    @Test
    fun `management tools are only exposed when the flag is on`() {
        val base = AgentToolCatalog.build(false, false).toolNames()
        val enabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            mcpManagement = true,
        ).toolNames()

        MCP_TOOLS.forEach { name ->
            assertFalse("默认不应暴露 $name", name in base)
            assertTrue("开启后应暴露 $name", name in enabled)
        }
        // 开关必须只影响 MCP 管理工具，不能顺带放出别的工具。
        assertEquals(MCP_TOOLS, enabled - base)
    }

    @Test
    fun `add server schema requires name and url and bounds the token`() {
        val function = build().function("mcp_add_server")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")

        assertEquals(200, properties.getJSONObject("name").getInt("maxLength"))
        assertEquals(2_000, properties.getJSONObject("url").getInt("maxLength"))
        assertEquals(4_000, properties.getJSONObject("bearerToken").getInt("maxLength"))
        assertEquals(setOf("name", "url"), parameters.requiredNames())
        assertTrue(function.getString("description").contains("NEXT turn"))
    }

    @Test
    fun `remove refresh and enable tools all require an exact serverId`() {
        listOf(
            "mcp_remove_server" to setOf("serverId"),
            "mcp_refresh_server" to setOf("serverId"),
            "mcp_set_server_enabled" to setOf("serverId", "enabled"),
            "mcp_set_tool_enabled" to setOf("serverId", "toolName", "enabled"),
        ).forEach { (toolName, required) ->
            val parameters = build().function(toolName).getJSONObject("parameters")
            assertEquals("$toolName required", required, parameters.requiredNames())
            assertEquals(
                "$toolName serverId maxLength",
                200,
                parameters.getJSONObject("properties").getJSONObject("serverId").getInt("maxLength"),
            )
        }
    }

    @Test
    fun `management tools stay out of the sensitive policy`() {
        MCP_TOOLS.forEach { name ->
            assertFalse("$name 是配置动作，不应触发脱敏", AgentSensitiveToolPolicy.isSensitive(name))
        }
        // 远端 MCP 工具必须保持敏感，别把上面的豁免扩大到它们。
        assertTrue(
            "远端 MCP 工具仍需脱敏",
            AgentSensitiveToolPolicy.isSensitive("mcp_abc12345_search_9f8e7d6c"),
        )
    }

    private fun build(): JSONArray =
        AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            mcpManagement = true,
        )

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> {
        val array = optJSONArray("required") ?: return emptySet()
        return (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
    }

    private companion object {
        val MCP_TOOLS = setOf(
            "mcp_list_servers",
            "mcp_add_server",
            "mcp_remove_server",
            "mcp_refresh_server",
            "mcp_set_server_enabled",
            "mcp_set_tool_enabled",
        )
    }
}