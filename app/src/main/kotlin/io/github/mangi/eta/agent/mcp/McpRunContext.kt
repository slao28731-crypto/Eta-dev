package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

/** 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
) {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    // 只截目录里的展示文本；数据库仍保留完整描述供设置页查看。
                    append("。 ").append(it.take(MAX_CATALOG_DESCRIPTION_CHARS))
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(): McpRunSnapshot {
            val projected = mutableListOf<McpRunTool>()
            val now = System.currentTimeMillis()
            for (configured in McpServerRepository.enabledServers()) {
                if (projected.size >= MAX_RUN_TOOLS) break
                val bearerToken = McpServerRepository.bearerToken(configured.id)
                val needsRefresh = configured.lastProtocolVersion == null ||
                    configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                    (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
                val server = if (needsRefresh) {
                    runCatching {
                        McpServerManager.discover(configured, bearerToken).also {
                            McpServerRepository.update(it)
                        }
                    }.getOrNull()
                        ?: continue
                } else {
                    configured
                }
                // 额度是所有服务共享的：前面的服务占满后，后面的一个工具也进不来。
                // 必须在这里显式 take(remaining)：早期写法用 return@forEach 判断上限，
                // 它只跳过当前元素、外层继续空转，超额的工具被静默丢弃且不留任何痕迹，
                // 模型因此拿着「已全部启用」的承诺去调用根本不存在的名字（TOOL_NOT_AVAILABLE）。
                val remaining = MAX_RUN_TOOLS - projected.size
                if (remaining <= 0) break
                server.activeTools.take(remaining).forEach { tool ->
                    projected += McpRunTool(
                        modelName = mcpModelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
            }
            return McpRunSnapshot(projected)
        }

        /**
         * 按 [load] 完全相同的额度规则，预估每个已启用服务在下次 run 里**实际**能进目录的工具数。
         *
         * 存在理由：`mcp_add_server` 曾经无条件回复「已启用全部工具」，而实际进目录的
         * 受 [MAX_RUN_TOOLS] 全局额度限制，100 个工具里只有 64 个能被模型看见。
         * 模型照着「全部启用」的承诺去调用被裁掉的名字，必然拿到 TOOL_NOT_AVAILABLE。
         * 这里把「承诺」和「实际」对齐，让模型一开始就知道哪些名字真的能喊。
         *
         * 用已入库的 [McpServerSetting.activeTools] 估算，不发网络请求：调用方（新增服务、
         * 管理工具）刚做过 discover，库里的数据就是最新的，再连一次只会拖慢工具响应。
         */
        suspend fun projectedToolCounts(): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()
            var used = 0
            for (server in McpServerRepository.enabledServers()) {
                val remaining = MAX_RUN_TOOLS - used
                if (remaining <= 0) break
                val admitted = server.activeTools.size.coerceAtMost(remaining)
                counts[server.id] = admitted
                used += admitted
            }
            return counts
        }

        /** 全部已启用服务共享的额度总数。 */
        suspend fun totalEnabledToolCount(): Int =
            McpServerRepository.enabledServers().sumOf { it.activeTools.size }

        /** 单个工具描述的目录展示上限，供调用方保持文案一致。 */
        val catalogDescriptionChars: Int get() = MAX_CATALOG_DESCRIPTION_CHARS

        /**
         * 单次 run 最多装入的 MCP 工具总数（所有启用的服务共享这一个额度）。
         *
         * 这不是「一轮能调几次」的限制，而是「模型这一轮能看见多少个 MCP 工具」。
         * 上游原值 64 在双服务（51 + 49 = 100）场景下会把后一个服务的工具几乎全裁掉，
         * 模型只收到「已启用全部工具」的承诺，调用被裁掉的名字必然失败。
         *
         * 与 [McpHttpClient.MAX_DISCOVERED_TOOLS] 的关系：发现上限必须不低于这里，
         * 否则工具连数据库都进不来，调大这个值也是白调。
         */
        const val MAX_RUN_TOOLS = 256

        /**
         * 拼进模型目录时单个工具描述的字符上限。
         *
         * 入库保留完整的 2000 字（设置页要看得全），但目录里只需要「这工具干什么」。
         * 100 个工具按 2000 字算约 20 万字符，光描述就能吃掉几万 token；
         * 截到 400 字可以省下大半，且不影响模型判断该不该调用。
         */
        private const val MAX_CATALOG_DESCRIPTION_CHARS = 400
    }
}

/**
 * MCP 工具在模型侧的最终名字。
 *
 * 唯一实现，供两处共用：
 * - [McpRunSnapshot.load] 组装本轮工具目录时；
 * - [io.github.mangi.eta.agent.tool.AgentLocalTools] 的 `mcp_add_server` / `mcp_list_servers`，
 *   用来把「下一轮才会出现的名字」提前告诉模型。
 *
 * 一旦两处各写一份，任何一处改格式都会让模型照着错误的名字调用，而报错信息只会是
 * UNKNOWN_TOOL，极难定位。所以这里必须保持单一定义。
 */
internal fun mcpModelToolName(serverId: String, toolName: String): String {
    val serverPart = serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }
    val normalized = toolName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .trim('_')
        .take(30)
        .ifBlank { "tool" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$serverId\u0000$toolName".toByteArray())
        .take(4)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "mcp_${serverPart}_${normalized}_$digest"
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpHttpClient>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        return runCatching {
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                clients[tool.server.id] ?: McpHttpClient(
                    server = tool.server,
                    bearerToken = tool.bearerToken,
                ).also { clients[tool.server.id] = it }
            }
            adaptResult(tool, client.callTool(tool.definition, arguments))
        }.getOrElse {
            failure("MCP_CALL_FAILED", "MCP 工具调用失败")
        }
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "当前版本暂不支持 MCP 工具在执行中请求补充输入",
            )
        }
        if (tool.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) {
            payload.put("code", "MCP_TOOL_ERROR")
            // 远端把失败原因放在 content 里，没有 message 字段。补一个 message：
            // UI 的失败摘要只读 message，缺了它用户只能看到「失败 · code=MCP_TOOL_ERROR」，
            // 展开也拿不到任何细节（折叠行与详情同文，被判为无详情）。
            val reason = (0 until textItems.length())
                .mapNotNull { index -> textItems.optString(index).takeIf(String::isNotBlank) }
                .firstOrNull()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(MAX_ERROR_REASON_CHARS)
            if (!reason.isNullOrBlank()) payload.put("message", reason)
        }
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        /** 摘要层面的失败原因上限，比摘要行更宽松，够看清服务端说了什么。 */
        const val MAX_ERROR_REASON_CHARS = 160
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val mcp: McpToolExecutor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        if (mcp.contains(toolCall.name)) mcp.execute(toolCall) else local.execute(toolCall)

    override fun close() {
        runCatching { mcp.close() }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
