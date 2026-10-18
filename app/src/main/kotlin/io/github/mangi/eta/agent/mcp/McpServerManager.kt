package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    /**
     * 连接服务器并读取工具清单。
     *
     * [enableAllDiscoveredTools] 只给 Agent 自助添加的路径用（用户已确认「加完就能用」）。
     * 设置页新建时保持 false：那里由用户在详情页逐项决定，重连时只保留已启用项（intersect）。
     */
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        enableAllDiscoveredTools: Boolean = false,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = if (enableAllDiscoveredTools) {
                availableNames
            } else {
                server.enabledToolNames.intersect(availableNames)
            },
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
