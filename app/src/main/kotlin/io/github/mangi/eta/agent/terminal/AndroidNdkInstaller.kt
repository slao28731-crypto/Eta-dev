package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/**
 * Android NDK 的用户自备安装器（不内置任何 NDK 资产）。
 *
 * ## 为什么和 LinuxPackageProfile 是两套东西
 *
 * Eta 现有的 Linux 工具链全部装在 Debian/Alpine 的 rootfs 里，跑在 chroot 中。
 * 但 Android NDK 的 clang 是 **bionic 二进制**（`INTERP: /system/bin/linker64`），
 * 它只能在 Android 宿主上运行：
 *
 * - chroot 里没有 `/system/bin/linker64` → 跑不起来
 * - PRoot 里看不到 `/system` 且 bionic 库不存在 → Permission denied
 * - `/storage/emulated/0` 是 noexec 挂载 → 只能读不能执行
 *
 * 所以它必须装在 `/data` 分区（App 私有目录），并且只能由 Root/Shizuku 的
 * shell 通道调用，**Agent 的 Linux 终端调不到它**。这一点在
 * `AgentPromptBuilder` 里有明确说明，避免 Agent 在错误的通道里试。
 *
 * ## 为什么支持多版本
 *
 * 用户会同时有多个 NDK：官方 r27、AIDE 魔改 r29、厂商定制版……
 * 不同项目对 NDK 版本敏感（`ANDROID_NDK_VERSION` 写死的工程切版本就编不过），
 * 所以这里按「目录名 = 版本槽」管理，并记录一个 active 指针。
 *
 * ## 为什么不内置
 *
 * 一个 NDK 包 700MB+，解压 2.5GB。内置进 APK 会让安装包膨胀十倍以上，
 * 而且绝大多数用户用不到。所以只提供「导入」入口，资产完全由用户提供。
 */
internal class AndroidNdkInstaller(
    private val context: Context,
) {
    private val root = File(context.filesDir, ROOT_DIR_NAME)

    /** 当前已安装的所有版本，按目录名排序（新的在前）。 */
    fun installedVersions(): List<InstalledNdk> = with(root) {
        if (!isDirectory) return emptyList()
        listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> readNdk(dir) }
            ?.sortedByDescending { it.revision.ifBlank { it.slotName } }
            ?: emptyList()
    }

    /** 当前激活的版本；active 指针不存在或指向已删除目录时返回 null。 */
    fun activeNdk(): InstalledNdk? {
        val pointer = File(root, ACTIVE_POINTER)
        if (!pointer.isFile) return null
        val slot = runCatching { pointer.readText().trim() }.getOrNull().orEmpty()
        if (slot.isBlank()) return null
        val dir = File(root, slot)
        if (!dir.isDirectory) return null
        return readNdk(dir)
    }

    /**
     * 把一个用户选择的归档导入为新的版本槽。
     *
     * @param openStream 由 UI 层从 SAF URI 打开；不把 Uri 传进来是为了让本类
     *   不依赖 Android ContentResolver，也方便单测直接喂文件流。
     * @param slotName 目录名，UI 默认从文件名推导；会做安全化处理。
     * @param onProgress 解压进度（已写字节 / 预计总量），可空。
     */
    suspend fun import(
        openStream: () -> java.io.InputStream,
        slotName: String,
        archiveSize: Long = 0L,
        onProgress: suspend (ExtractProgress) -> Unit = {},
    ): NdkImportResult = withContext(Dispatchers.IO) {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return@withContext NdkImportResult.UnsupportedAbi
        }
        val safeSlot = sanitizeSlot(slotName)
        if (safeSlot.isBlank()) return@withContext NdkImportResult.InvalidName

        val staging = File(root, "$safeSlot.importing")
        if (staging.exists() && !staging.deleteRecursively()) {
            return@withContext NdkImportResult.Failed("无法清理上次的临时目录，请重启应用后重试")
        }
        try {
            root.mkdirs()
            // 先把归档落到私有目录再解压。
            // 原因有两个：① SAF 的 InputStream 不支持 mark/reset，
            // 必须落盘后才能可靠地判 xz/gz 魔数；② 大文件反复读 URI 很慢。
            val archive = File(root, "$safeSlot.$ARCHIVE_TEMP_SUFFIX")
            archive.delete()
            val bytes = copyToFile(openStream, archive, archiveSize, onProgress)
            if (bytes <= 0) return@withContext NdkImportResult.Failed("归档为空或无法读取")

            extractArchive(archive, staging, archiveSize = bytes, onProgress = onProgress)

            // 归档可能多套一层目录（android-ndk-aide/、android-ndk-r29/…），
            // 也可能直接把 toolchains/ 放在根上。这两种都要能识别。
            val ndkRoot = locateNdkRoot(staging) ?: return@withContext NdkImportResult.NotAnNdk
            val normalized = normalizeLayout(staging, ndkRoot)

            val target = File(root, safeSlot)
            if (target.exists() && !target.deleteRecursively()) {
                return@withContext NdkImportResult.Failed("已存在同名版本且无法覆盖：$safeSlot")
            }
            if (!normalized.renameTo(target)) {
                return@withContext NdkImportResult.Failed("无法启用新版本目录，请检查存储空间")
            }

            val detected = readNdk(target) ?: return@withContext NdkImportResult.NotAnNdk
            writeWrapperScripts(target, detected)
            // 第一次导入自动激活；后续导入不抢当前激活的版本。
            if (activeNdk() == null) activate(safeSlot)

            AndroidAgentLogger.info(
                "Android NDK action=import slot=$safeSlot revision=${detected.revision} " +
                    "prebuilt=${detected.prebuiltDir?.name.orEmpty()} clang=${detected.clangPath != null}",
            )
            NdkImportResult.Imported(detected.copy(slotName = safeSlot))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            AndroidAgentLogger.warn("Android NDK action=import outcome=failed type=io")
            NdkImportResult.Failed(io.message ?: "解压失败")
        } catch (illegal: IllegalArgumentException) {
            AndroidAgentLogger.warn("Android NDK action=import outcome=failed type=illegal")
            NdkImportResult.Failed(illegal.message ?: "归档格式不被支持，需要 .tar.gz 或 .tar.xz")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            File(root, "$safeSlot.$ARCHIVE_TEMP_SUFFIX").delete()
        }
    }

    /**
     * 把归档复制到私有目录，同时回报进度。
     *
     * 分两段回报：复制阶段占总进度的 0~15%，解压占 15~100%。
     * 这样用户点下按钮就有反馈，不会在「读 728MB」这段完全静默。
     */
    private suspend fun copyToFile(
        openStream: () -> java.io.InputStream,
        destination: File,
        expectedSize: Long,
        onProgress: suspend (ExtractProgress) -> Unit,
    ): Long {
        var copied = 0L
        openStream().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    // 复制阶段的进度用一个虚拟总量，避免和后面解压的字节数混在一起。
                    onProgress(ExtractProgress(copied, expectedSize.coerceAtLeast(copied), copying = true))
                }
            }
        }
        return copied
    }

    /** 切换当前激活版本。 */
    fun activate(slotName: String): Boolean {
        val dir = File(root, slotName)
        if (!dir.isDirectory) return false
        root.mkdirs()
        return runCatching {
            File(root, ACTIVE_POINTER).writeText(slotName)
            true
        }.getOrDefault(false)
    }

    /** 删除某个版本；删的是当前激活版本时一并清掉指针。 */
    fun remove(slotName: String): Boolean {
        val dir = File(root, slotName)
        if (!dir.isDirectory) return false
        val wasActive = activeNdk()?.slotName == slotName
        if (!dir.deleteRecursively()) return false
        if (wasActive) File(root, ACTIVE_POINTER).delete()
        // 删掉激活版本后自动回落到剩下的最新一个，避免界面显示「有装但没激活」。
        if (wasActive) installedVersions().firstOrNull()?.let { activate(it.slotName) }
        return true
    }

    /** 单个版本占用的字节数，用于界面显示。 */
    fun sizeOf(slotName: String): Long = directorySize(File(root, slotName))

    // ---- 内部实现 ----

    private suspend fun extractArchive(
        archive: File,
        destination: File,
        archiveSize: Long,
        onProgress: suspend (ExtractProgress) -> Unit,
    ) {
        require(destination.mkdirs() || destination.isDirectory)
        val canonicalRoot = destination.canonicalFile
        val pendingLinks = mutableListOf<Triple<File, String, Boolean>>()
        var written = 0L
        var entries = 0

        // NDK 包实测膨胀约 3.2 倍（728MB → 约 2.3GB）。按 3.2 倍预留，低于它就提前报错，
        // 好过解压到一半把手机撑爆。
        val projected = (archiveSize * EXPANSION_RATIO).toLong()
        destination.parentFile?.let { parent ->
            val free = parent.usableSpace
            if (free in 1..<projected) {
                throw IOException(
                    "存储空间不足：解压约需 ${projected / 1024 / 1024}MB，可用 ${free / 1024 / 1024}MB",
                )
            }
        }

        archive.inputStream().use { raw ->
            val xz = looksLikeXz(raw)
            // 上面开的是独立流，读完就关；这里重新开一个给解压用，
            // 避免依赖 mark/reset（FileInputStream 支持，但包装后不保证）。
            archive.inputStream().buffered().use { stream ->
                val compressed = if (xz) XZInputStream(stream, 128 * 1024) else GZIPInputStream(stream)
                TarArchiveInputStream(compressed).use { tar ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = tar.nextEntry ?: break
                        require(++entries <= MAX_ENTRIES) { "归档条目过多（>$MAX_ENTRIES），可能不是 NDK" }
                        val path = normalizeArchivePath(entry.name) ?: continue
                        // safeResolve 返回 null 表示路径逃出了目标目录（含 `..` 或绝对路径），
                        // 直接跳过，而不是解压到别处。
                        val target = safeResolve(canonicalRoot, path) ?: continue
                        require(entry.size in 0..MAX_SINGLE_ENTRY) { "单个文件过大：${entry.name}" }

                        when {
                            entry.isDirectory -> require(target.mkdirs() || target.isDirectory)
                            entry.isSymbolicLink || entry.isLink ->
                                pendingLinks += Triple(target, entry.linkName, entry.isLink)
                            entry.isFile -> {
                                require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                                target.outputStream().use { out ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        coroutineContext.ensureActive()
                                        val read = tar.read(
                                            buffer,
                                            0,
                                            minOf(buffer.size.toLong(), remaining).toInt(),
                                        )
                                        if (read < 0) throw IOException("归档内容不完整：${entry.name}")
                                        out.write(buffer, 0, read)
                                        remaining -= read
                                        written += read
                                    }
                                }
                                // NDK 的 clang / ld 等必须可执行，按归档里的 mode 位恢复。
                                if (entry.mode and 0b001001001 != 0) {
                                    @Suppress("UsePropertyAccessSyntax")
                                    target.setExecutable(true, false)
                                }
                                onProgress(ExtractProgress(written, projected, copying = false))
                            }
                        }
                    }
                }
            }
        }

        // 链接放到最后建：目标文件此时一定已存在，避免悬空链接。
        // 只接受指向归档内部的路径，防止归档构造 /etc/passwd 之类的逃逸。
        pendingLinks.forEach { (link, name, hard) ->
            val linkTarget = safeResolve(canonicalRoot, name) ?: return@forEach
            link.parentFile?.mkdirs()
            link.delete()
            if (hard) {
                linkTarget.copyTo(link, overwrite = true)
            } else {
                runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), linkTarget.toPath()) }
            }
        }
    }

    /**
     * xz 的魔数：FD 37 7A 58 5A 00。
     *
     * 用独立的 FileInputStream 读前 6 字节即可，不需要 mark/reset——
     * 调用方会为解压重新开一个流。远端 NDK 基本都是 .tar.gz，
     * 但魔改版（如 TUSI 系列）可能压成 .tar.xz。
     */
    private fun looksLikeXz(input: java.io.InputStream): Boolean {
        val header = ByteArray(6)
        var read = 0
        while (read < 6) {
            val count = input.read(header, read, 6 - read)
            if (count < 0) break
            read += count
        }
        if (read < 6) return false
        return header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() &&
            header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() &&
            header[4] == 0x5A.toByte() && header[5] == 0x00.toByte()
    }

    /** 在解压结果里找 NDK 根：优先有 `source.properties` 的目录，其次有 `toolchains/llvm` 的目录。 */
    private fun locateNdkRoot(staging: File): File? {
        staging.walkTopDown()
            .maxDepth(3)
            .firstOrNull { it.isDirectory && File(it, NDK_MARKER).isFile }
            ?.let { return it }
        return staging.walkTopDown()
            .maxDepth(3)
            .firstOrNull { it.isDirectory && File(it, "toolchains/llvm").isDirectory }
    }

    /**
     * 把「多套一层目录」的结构摊平，让 `<slot>/source.properties` 直接可用。
     *
     * 为什么必须摊平：读版本、找 prebuilt、建 wrapper 全都以槽目录为基准，
     * 多一层就得每处都兼容两种层级，迟早漏。
     */
    private fun normalizeLayout(staging: File, ndkRoot: File): File {
        if (ndkRoot.canonicalPath == staging.canonicalPath) return staging
        // 把 ndkRoot 的内容移到 staging 根上。
        ndkRoot.listFiles()?.forEach { child ->
            val dest = File(staging, child.name)
            if (dest.exists()) dest.deleteRecursively()
            // 同分区，重命名不该失败；失败退化成复制。
            if (!child.renameTo(dest)) child.copyRecursively(dest, overwrite = true)
        }
        // 清掉已空的外壳目录（android-ndk-aide 等），避免它们被当成版本槽。
        staging.listFiles()
            ?.filter { it.isDirectory && it != staging }
            ?.forEach { leftover ->
                if (leftover.listFiles()?.isEmpty() == true) leftover.delete()
            }
        return staging
    }

    private fun writeWrapperScripts(slotDir: File, ndk: InstalledNdk) {
        val binDir = File(slotDir, "bin").apply { mkdirs() }
        listOf("clang", "clang++", "llvm-ar", "llvm-strip", "llvm-readelf", "llvm-nm").forEach { name ->
            val real = File(ndk.prebuiltBin, name)
            if (!real.isFile) return@forEach
            val wrapper = File(binDir, name)
            // 用绝对路径 exec：NDK 的 clang 是 bionic 二进制，依赖 /system/bin/linker64，
            // 只有 Android 宿主能加载它。wrapper 让调用方不必记住 120MB 二进制的长路径。
            wrapper.writeText(
                "#!/system/bin/sh\nexec " + real.absolutePath + " \"\$@\"\n",
            )
            @Suppress("UsePropertyAccessSyntax")
            wrapper.setExecutable(true, false)
        }
    }

    private fun readNdk(dir: File): InstalledNdk? {
        val marker = File(dir, NDK_MARKER)
        val revision = if (marker.isFile) {
            runCatching {
                marker.useLines { lines ->
                    lines.firstOrNull { it.trimStart().startsWith("Pkg.Revision") }
                        ?.substringAfter('=')
                        ?.trim()
                }
            }.getOrNull().orEmpty()
        } else {
            ""
        }
        val prebuilt = File(dir, "toolchains/llvm/prebuilt")
        val candidates = prebuilt.listFiles()?.filter { it.isDirectory }.orEmpty()
        // 优先选能跑在当前 ABI 上的 prebuilt；名字里带 arm 的都试。
        val chosen = candidates.firstOrNull { it.name.contains("linux-arm") }
            ?: candidates.firstOrNull { it.name.contains("arm") }
            ?: candidates.firstOrNull()
        if (chosen == null && marker.isFile.not()) return null
        val binDir = File(chosen ?: dir, "bin")
        val clang = File(binDir, "clang")
        // NDK 里 `clang` 是软链，指向 `clang-<LLVM 版本>`（如 clang-21），
        // 而 source.properties 里的 Pkg.Revision 是 NDK 版本（如 29.0.x），
        // 两者不是同一个数字。所以按「名字最长且带数字」挑真实二进制，
        // 不要用 NDK 版本号去拼文件名（那样永远对不上）。
        val clangReal = binDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("clang-") }
            ?.maxByOrNull { it.name.length }
        return InstalledNdk(
            slotName = dir.name,
            rootDir = dir,
            revision = revision,
            prebuiltDir = chosen,
            prebuiltBin = binDir,
            clangPath = when {
                clang.isFile -> clang
                clangReal != null -> clangReal
                else -> null
            },
        )
    }

    private fun sanitizeSlot(raw: String): String {
        val base = raw.substringBeforeLast('.').ifBlank { raw }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-', '.')
            .take(48)
    }

    private fun normalizeArchivePath(name: String): String? {
        val cleaned = name.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val relative = parts.joinToString("/")
        return relative.ifEmpty { null }
    }

    private fun safeResolve(root: File, relative: String): File? {
        val resolved = File(root, relative).canonicalFile
        val rootPath = root.canonicalPath + File.separator
        return if (resolved.path == root.canonicalPath || resolved.path.startsWith(rootPath)) resolved else null
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    internal data class InstalledNdk(
        val slotName: String,
        val rootDir: File,
        val revision: String,
        val prebuiltDir: File?,
        val prebuiltBin: File,
        val clangPath: File?,
    ) {
        /** 给 Agent 用的可执行路径（wrapper 优先，回退到真实 clang）。 */
        val clangCommand: String?
            get() {
                val wrapper = File(rootDir, "bin/clang")
                return when {
                    wrapper.isFile -> wrapper.absolutePath
                    clangPath != null -> clangPath.absolutePath
                    else -> null
                }
            }
    }

    /**
     * 导入进度。
     *
     * @param copying true = 正在把归档复制进 App 私有目录；false = 正在解压。
     *   两段的百分比不能直接相加，所以分开标记，UI 分别显示「复制」和「解压」。
     */
    internal data class ExtractProgress(
        val writtenBytes: Long,
        val totalBytes: Long,
        val copying: Boolean,
    ) {
        val fraction: Float?
            get() = if (totalBytes > 0L) {
                (writtenBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
    }

    internal sealed interface NdkImportResult {
        data class Imported(val ndk: InstalledNdk) : NdkImportResult
        data object NotAnNdk : NdkImportResult
        data object UnsupportedAbi : NdkImportResult
        data object InvalidName : NdkImportResult
        data class Failed(val reason: String) : NdkImportResult
    }

    companion object {
        private const val ROOT_DIR_NAME = "android-ndk"
        private const val ACTIVE_POINTER = ".active"
        private const val NDK_MARKER = "source.properties"
        private const val MAX_ENTRIES = 200_000
        private const val MAX_SINGLE_ENTRY = 512L * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val ARCHIVE_TEMP_SUFFIX = "archive.tmp"

        /**
         * 解压膨胀比的经验值。
         *
         * 实测用户提供的 `TUSI-Ollvm-ndk-r29-aide-Magic.tgz`：
         * 728MB 压缩包，clang 单个二进制解压后 120MB，整体约 2.3GB，比例约 3.2。
         * 用它做「空间够不够」的前置判断，宁可提前拒绝，也不要解压到一半写满磁盘。
         */
        private const val EXPANSION_RATIO = 3.2

        /** Agent 侧的调用约定：返回当前激活版本的 clang 路径。 */
        fun activeClangPath(context: Context): String? =
            AndroidNdkInstaller(context).activeNdk()?.clangCommand
    }
}
