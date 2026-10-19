package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

internal enum class FridaInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING_CLI,
    STARTING,
    VERIFYING,
    COMPLETE,
}

internal data class FridaInstallProgress(
    val stage: FridaInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface FridaInstallResult {
    data object AlreadyReady : FridaInstallResult
    data object EnvironmentNotReady : FridaInstallResult
    data object RootRequired : FridaInstallResult
    data object UnsupportedAbi : FridaInstallResult

    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : FridaInstallResult
    data object Installed : FridaInstallResult
    data class Failed(val stage: FridaInstallStage, val code: String? = null) : FridaInstallResult
}

/**
 * Frida 的 server 与 Python 绑定版本必须严格一致（都用 17.24.2），
 * 否则 attach 时会报 "unable to find process" 或握手失败。
 *
 * 注意：**frida-tools 不跟随 frida 的版本号**。它是独立编号的项目，
 * 当前最新是 14.11.0，其依赖声明为 `frida>=17.10.0,<18.0.0`。
 * 把 frida-tools 也钉到 17.24.2 会让 pip 直接报 No matching distribution，
 * 安装卡死在 INSTALLING_CLI。
 */
internal object FridaPaths {
    const val VERSION = "17.24.2"

    /** frida-tools 独立版本线，与 [VERSION] 无关。 */
    const val TOOLS_VERSION = "14.11.0"

    /** 装到私有目录而不是 /data/local/tmp：卸载 App 时能一起清掉，也不污染共享临时目录。 */
    fun serverDir(context: Context): File = File(context.filesDir, "frida")
    fun serverFile(context: Context): File = File(serverDir(context), "etad")
    fun logFile(context: Context): File = File(serverDir(context), "server.log")
    fun pidFile(context: Context): File = File(serverDir(context), "server.pid")

    /** 默认端口改成 8899，避开反调试最爱扫的 27042/27043。 */
    const val DEFAULT_PORT = 8899
}

internal fun fridaServerReady(context: Context): Boolean =
    FridaPaths.serverFile(context).let { it.isFile && it.length() > 0L }

/**
 * 同步判活，供 UI 快速呈现（`remember { mutableStateOf(installer.isRunning()) }`）。
 *
 * **为什么不能只看 `/proc`**：Android 10+ 的 `/proc` 挂了 `hidepid`，App 身份读不到
 * 别的 UID（这里是 root）的 `/proc/<pid>/stat`，实测会 FileNotFound。所以主判据改成
 * **TCP 探测 127.0.0.1:8899**：server 活着就一定有进程在 listen，端口连得上，
 * 这是唯一不依赖权限、也不会被 SELinux 挡住的信号。
 * 端口连不上时再退回 pid 文件 + `/proc` 检查（同 UID 场景仍有效）。
 */
internal fun fridaServerRunning(context: Context): Boolean {
    if (fridaPortOpen()) return true
    val pidFile = FridaPaths.pidFile(context)
    if (!pidFile.isFile) return false
    val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return false
    if (pid <= 0) return false
    val stat = File("/proc/$pid/stat")
    if (!stat.isFile) return false
    return runCatching {
        stat.readText().substringAfterLast(')').trim().firstOrNull() != 'Z'
    }.getOrDefault(true)
}

/** 本地 TCP 探测：server 活着就必然在 listen。回环连接是毫秒级，超时给 200ms 足够且不卡 UI。 */
private fun fridaPortOpen(): Boolean = runCatching {
    java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress("127.0.0.1", FridaPaths.DEFAULT_PORT), 200)
        true
    }
}.getOrDefault(false)

/**
 * Frida server 的生命周期管理。server 是 Android 原生二进制（不是 Debian 内的），
 * 必须用 root 身份起在宿主侧，Debian 里的 frida CLI 通过 TCP 连过来。
 */
internal class FridaInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val downloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    fun isReady(): Boolean = fridaServerReady(context)

    fun isRunning(): Boolean = fridaServerRunning(context)

    suspend fun install(
        onProgress: suspend (FridaInstallProgress) -> Unit = {},
    ): FridaInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    /**
     * 只做「让 server 真正跑起来」这一段：补 CLI → 起 server → 连通验证。
     *
     * UI 的 Start 按钮走这条。为什么要单独抽出来：server 二进制已经在、
     * 但 rootfs 里的 frida-ps 被清掉（换发行版、手动清 python 包）时，
     * 只调 startServer 会「起得来但用不了」，用户看到的是连不上却无从下手。
     */
    suspend fun ensureRunning(
        onProgress: suspend (FridaInstallProgress) -> Unit = {},
    ): FridaInstallResult {
        installMutex.lock()
        return try {
            withContext(Dispatchers.IO) {
                onProgress(FridaInstallProgress(FridaInstallStage.CHECKING))
                if (!fridaServerReady(context)) {
                    return@withContext FridaInstallResult.EnvironmentNotReady
                }
                if (!RootAccess.isGranted) {
                    return@withContext FridaInstallResult.RootRequired
                }
                val wasRunning = fridaServerRunning(context)

                coroutineContext.ensureActive()
                onProgress(FridaInstallProgress(FridaInstallStage.INSTALLING_CLI))
                if (!installCli()) {
                    return@withContext FridaInstallResult.Failed(FridaInstallStage.INSTALLING_CLI)
                }

                coroutineContext.ensureActive()
                onProgress(FridaInstallProgress(FridaInstallStage.STARTING))
                if (!wasRunning && !startServer()) {
                    return@withContext FridaInstallResult.Failed(FridaInstallStage.STARTING)
                }

                coroutineContext.ensureActive()
                onProgress(FridaInstallProgress(FridaInstallStage.VERIFYING))
                if (!verifyConnection()) {
                    return@withContext FridaInstallResult.Failed(FridaInstallStage.VERIFYING, "CONNECT_FAILED")
                }

                onProgress(FridaInstallProgress(FridaInstallStage.COMPLETE))
                if (wasRunning) FridaInstallResult.AlreadyReady else FridaInstallResult.Installed
            }
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (FridaInstallProgress) -> Unit,
    ): FridaInstallResult = withContext(Dispatchers.IO) {
        onProgress(FridaInstallProgress(FridaInstallStage.CHECKING))

        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return@withContext FridaInstallResult.EnvironmentNotReady
        }

        // server 必须以 root 起在宿主侧；没有 Root 时提前失败，别等到 STARTING 才报一个含糊的错。
        if (!RootAccess.isGranted) {
            return@withContext FridaInstallResult.RootRequired
        }

        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext FridaInstallResult.UnsupportedAbi

        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < MIN_AVAILABLE_BYTES) {
            return@withContext FridaInstallResult.InsufficientSpace(
                requiredBytes = MIN_AVAILABLE_BYTES,
                availableBytes = availableBytes,
            )
        }

        // 1. 下载并校验 server（xz 压缩包）。
        //    server 二进制已在就跳过下载，但**不能连 CLI 一起跳过**：用户可能只清理过
        //    rootfs 里的 python 包，或换了发行版，那时 server 在、frida-ps 不在，
        //    直接 return 会让按钮永远停在「已安装但连不上」。
        val server = FridaPaths.serverFile(context)
        if (!fridaServerReady(context)) {
            val archive = File(context.cacheDir, "frida/${artifact.fileName}")
            onProgress(
                FridaInstallProgress(
                    stage = FridaInstallStage.DOWNLOADING,
                    totalBytes = artifact.sizeBytes,
                ),
            )
            val downloaded = downloader.download(artifact, archive) { done, total ->
                onProgress(
                    FridaInstallProgress(
                        stage = FridaInstallStage.DOWNLOADING,
                        downloadedBytes = done,
                        totalBytes = total,
                    ),
                )
            }
            if (!downloaded) {
                return@withContext FridaInstallResult.Failed(FridaInstallStage.DOWNLOADING)
            }

            // 2. 解压 xz → server 二进制
            coroutineContext.ensureActive()
            onProgress(FridaInstallProgress(FridaInstallStage.EXTRACTING))
            if (!extractXz(archive, server)) {
                archive.delete()
                return@withContext FridaInstallResult.Failed(FridaInstallStage.EXTRACTING)
            }
            server.setExecutable(true, false)
            archive.delete()
        }

        // 3. Debian 侧装 frida-tools。注意版本用 TOOLS_VERSION（14.x 线），不是 VERSION
        coroutineContext.ensureActive()
        onProgress(FridaInstallProgress(FridaInstallStage.INSTALLING_CLI))
        if (!installCli()) {
            return@withContext FridaInstallResult.Failed(FridaInstallStage.INSTALLING_CLI)
        }

        // 4. 起 server
        coroutineContext.ensureActive()
        onProgress(FridaInstallProgress(FridaInstallStage.STARTING))
        if (!startServer()) {
            return@withContext FridaInstallResult.Failed(FridaInstallStage.STARTING)
        }

        // 5. 验证 CLI 能连上
        coroutineContext.ensureActive()
        onProgress(FridaInstallProgress(FridaInstallStage.VERIFYING))
        if (!verifyConnection()) {
            return@withContext FridaInstallResult.Failed(FridaInstallStage.VERIFYING, "CONNECT_FAILED")
        }

        onProgress(FridaInstallProgress(FridaInstallStage.COMPLETE))
        FridaInstallResult.Installed
    }

    /**
     * 解 xz。
     *
     * **不能用外部 `xz` 命令**：
     * - Android 原生环境没有 xz（`/system/bin`、`/vendor/bin`、toybox 全都没有）；
     * - Debian/chroot 侧虽然有 xz，但 chroot 根是 rootfs，看不到宿主侧的
     *   `filesDir` / `cacheDir`，跨边界读写要做额外搬运。
     *
     * 项目已依赖 `org.tukaani:xz`（见 `libs.versions.toml` 的 `xz = "1.12"`），
     * 直接用 [XZInputStream] 在 Kotlin 里解，不依赖任何外部命令、不跨 chroot 边界。
     * frida-server 的 `.xz` 是单流压缩（非 tar.xz），所以只解流不解包。
     */
    private suspend fun extractXz(archive: File, target: File): Boolean = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        runCatching {
            archive.inputStream().buffered().use { raw ->
                XZInputStream(raw, 128 * 1024).use { xz ->
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = xz.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            AndroidAgentLogger.warn(
                "Frida action=extract outcome=failed type=${failure.safeLogType()}",
            )
            target.delete()
        }.isSuccess && target.isFile && target.length() > 0L
    }

    /**
     * 装 frida 的 Python 绑定与 CLI。
     *
     * **不能写死 `python3 -m pip`**：Eta 的 Debian rootfs 里 `/usr/bin/python3` 可能不存在，
     * `python3` 实际是 `/usr/local/bin/python3` 指向 uv 托管的
     * `/opt/eta/python/cpython-<ver>-linux-<arch>-gnu/bin/python3.14`。
     * 用户的发行版/架构不同，这个路径会变，所以这里运行时逐个探测，
     * 装完再从几个候选目录里找 `frida-ps`，找不到就把软链接补进 /usr/local/bin。
     * 探测失败一律带明确 exit code，方便 UI 报出具体卡在哪一步。
     */
    private suspend fun installCli(): Boolean {
        val result = InstallerShellRunner.run(
            command = installCliCommand(),
            timeoutSeconds = 900,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Frida action=install_cli outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode}",
        )
        // pip 在 Debian 的 PEP 668 下必须带 --break-system-packages，已带。
        return result.exitCode == 0
    }

    /**
     * 一条自包含的 POSIX sh 脚本。整段拼出来容易漏标点，所以按已校验的单行版本逐段抄写：
     * 这条字符串已用 `bash -n` / `dash -n` / `sh -n` 三种 shell 校验（rc=0），
     * 并在真实 Debian rootfs 里用 `chroot ... /bin/sh` 实跑过（输出 FRIDA_CLI_OK）。
     * 改动时请重新做这两项校验，不要凭感觉调。
     *
     * 退出码约定：66 没 python / 67 pip 失败 / 68 找不到 frida-ps / 69 软链不可执行。
     */
    internal fun installCliCommand(): String =
        "PY=; for c in /usr/local/bin/python3 /usr/bin/python3 /opt/eta/python/*/bin/python3 python3; do " +
            "if [ -x \"\$c\" ] || command -v \"\$c\" >/dev/null 2>&1; then PY=\"\$c\"; break; fi; done; " +
            "if [ -z \"\$PY\" ]; then echo FRIDA_NO_PYTHON; exit 66; fi; echo FRIDA_PYTHON=\$PY; " +
            // 版本已对上就跳过 pip：重装一次要下 30MB，按钮不该每次点都卡半天。
            "if \"\$PY\" -c 'import frida,sys; sys.exit(0 if frida.__version__==\"${FridaPaths.VERSION}\" else 1)' " +
            ">/dev/null 2>&1; then echo FRIDA_CLI_ALREADY=" +
            "\$(\"\$PY\" -c 'import frida;print(frida.__version__)'); " +
            "else \"\$PY\" -m pip install --quiet --break-system-packages --ignore-installed " +
            "frida==${FridaPaths.VERSION} frida-tools==${FridaPaths.TOOLS_VERSION} " +
            "|| { echo FRIDA_PIP_FAILED; exit 67; }; fi; " +
            // uv 把脚本装在解释器同级 bin，pip 在 /usr/bin；两边都找一遍。
            "CLI=; for d in \"\${PY%/*}\" /usr/local/bin /usr/bin /opt/eta/python/*/bin; do " +
            "if [ -x \"\$d/frida-ps\" ]; then CLI=\"\$d/frida-ps\"; break; fi; done; " +
            "if [ -z \"\$CLI\" ]; then echo FRIDA_CLI_MISSING; exit 68; fi; echo FRIDA_CLI=\$CLI; " +
            // 统一软链到 /usr/local/bin：chroot 的 PATH 是调用方注入的，不保证含解释器 bin。
            "mkdir -p /usr/local/bin; " +
            "for t in frida frida-ps frida-trace frida-ls-devices frida-discover frida-kill " +
            "frida-ls frida-rm frida-run frida-compile frida-create frida-join frida-pull " +
            "frida-push frida-apk frida-itrace frida-strace frida-pm; do " +
            "if [ -x \"\${CLI%/*}/\$t\" ]; then ln -sf \"\${CLI%/*}/\$t\" \"/usr/local/bin/\$t\"; fi; done; " +
            // 复核用绝对路径，不用 command -v：命令行的 PATH 未必含 /usr/local/bin（实测会误报）。
            "if [ ! -x /usr/local/bin/frida-ps ]; then echo FRIDA_CLI_NOT_LINKED; exit 69; fi; " +
            "echo FRIDA_CLI_OK"

    /**
     * 用 root shell 扫 `/proc`，按 server 的绝对路径精确定位 pid。
     *
     * 为什么不用 `$!`：Android 的 `setsid` 会先 fork 再 exec，`$!` 记下的是短命的
     * 父进程，写进 pid 文件就是脏数据 —— 现象是「明明在跑却显示已停止」且 stop 杀不掉。
     * 为什么不在 App 侧读 `/proc`：frida-server 是 root 起的，App 身份读
     * `/proc/<root-pid>/cmdline` 常被 SELinux 拒（实测 `ps -A` 能看到但读不到内容），
     * 所以这一步必须在 root shell 里做。
     *
     * 输出格式：`PID=<pid>`，找不到则输出 `PID=`。
     */
    private suspend fun detectServerPid(): Int? {
        val serverPath = FridaPaths.serverFile(context).absolutePath
        val quoted = shellQuote(serverPath)
        // cmdline 是以 NUL 分隔的，直接读前缀会带上 \0 匹配不上；先用 tr 转空格再 case 匹配。
        // tr 不保证在 root shell 的 PATH 里，先探一遍再退回绝对路径。
        val result = InstallerShellRunner.run(
            command = "T=; for c in tr /system/bin/tr /vendor/bin/tr; do " +
                "if command -v \"\$c\" >/dev/null 2>&1 || [ -x \"\$c\" ]; then T=\"\$c\"; break; fi; " +
                "done; if [ -z \"\$T\" ]; then echo PID=; exit 0; fi; " +
                "P=; for d in /proc/[0-9]*; do " +
                "c=\$d/cmdline; [ -r \"\$c\" ] || continue; " +
                "n=\$(\"\$T\" '\\000' ' ' < \"\$c\" 2>/dev/null); " +
                "case \"\$n\" in $quoted*) P=\${d#/proc/}; break;; esac; " +
                "done; echo PID=\$P",
            timeoutSeconds = 20,
            environment = TerminalEnvironment.ANDROID,
            identity = "root",
        )
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .firstOrNull { it.startsWith("PID=") }
            ?.removePrefix("PID=")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    /**
     * 起 server。用 root 身份、加 setsid 脱离当前会话，
     * 这样即使启动它的 shell 退出，server 也不会被一起收掉。
     */
    suspend fun startServer(): Boolean {
        val server = FridaPaths.serverFile(context)
        if (!server.isFile) return false
        if (detectServerPid() != null) return true

        val log = FridaPaths.logFile(context)
        val pidFile = FridaPaths.pidFile(context)
        val result = InstallerShellRunner.run(
            command = buildString {
                append("mkdir -p ${shellQuote(server.parentFile!!.absolutePath)} && ")
                append("rm -f ${shellQuote(pidFile.absolutePath)} ${shellQuote(log.absolutePath)} && ")
                append("setsid ${shellQuote(server.absolutePath)} ")
                append("-l 127.0.0.1:${FridaPaths.DEFAULT_PORT} ")
                append(">${shellQuote(log.absolutePath)} 2>&1 < /dev/null & ")
                append("echo \$! > ${shellQuote(pidFile.absolutePath)}")
            },
            timeoutSeconds = 30,
            environment = TerminalEnvironment.ANDROID,
            identity = "root",
        )
        AndroidAgentLogger.info(
            "Frida action=start_server outcome=${if (result.exitCode == 0) "dispatched" else "failed"} " +
                "exitCode=${result.exitCode}",
        )
        if (result.exitCode != 0) return false

        // 等 server 把端口挂起来；拿到真实 pid 后回写 pid 文件，覆盖 setsid 那个可能已失效的值。
        repeat(10) {
            val pid = detectServerPid()
            if (pid != null) {
                runCatching { pidFile.writeText(pid.toString()) }
                return true
            }
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    /**
     * 停 server。pid 用 root shell 现场扫（pid 文件可能已失效），
     * 先 SIGTERM 等 1 秒，还在就 SIGKILL。pid 文件一并清掉，避免下次误判。
     */
    suspend fun stopServer(): Boolean {
        val pidFile = FridaPaths.pidFile(context)
        val pid = detectServerPid()
        if (pid == null) {
            runCatching { pidFile.delete() }
            return true
        }
        val result = InstallerShellRunner.run(
            command = "kill $pid 2>/dev/null; sleep 1; kill -9 $pid 2>/dev/null; " +
                "rm -f ${shellQuote(pidFile.absolutePath)}; true",
            timeoutSeconds = 20,
            environment = TerminalEnvironment.ANDROID,
            identity = "root",
        )
        val stillRunning = detectServerPid() != null
        AndroidAgentLogger.info(
            "Frida action=stop_server outcome=${if (stillRunning) "still_running" else "stopped"} " +
                "exitCode=${result.exitCode}",
        )
        return !stillRunning
    }

    /**
     * 验证 CLI 能连上 server。
     *
     * **不要写成 `frida-ps ... | head -5`**：管道的退出码是 `head` 的，永远是 0，
     * `timeout` 打到 124 也看不出来；而 frida-ps 失败时常常只往 stderr 吐一行，
     * 一度让这里「看起来在跑但拿不到输出」。改成直接跑、保留真实退出码，
     * 再把输出（含 stderr）落进日志，失败时能一眼看出是连不上还是 CLI 崩了。
     */
    private suspend fun verifyConnection(): Boolean {
        val result = InstallerShellRunner.run(
            command = "timeout 45 /usr/local/bin/frida-ps -H 127.0.0.1:${FridaPaths.DEFAULT_PORT}",
            timeoutSeconds = 75,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        val ok = result.exitCode == 0 && result.output.isNotBlank() &&
            !result.output.contains("Failed to connect", ignoreCase = true) &&
            !result.output.contains("unable to connect", ignoreCase = true)
        AndroidAgentLogger.info(
            "Frida action=verify outcome=${if (ok) "succeeded" else "failed"} exitCode=${result.exitCode} " +
                "output=${result.output.take(240).replace('\n', ' ')}",
        )
        return ok
    }

    companion object {
        internal const val MIN_AVAILABLE_BYTES = 400L * 1024L * 1024L

        private val installMutex = Mutex()

        private val GITHUB_PROXY_PREFIXES = listOf("https://gh-proxy.com/")

        internal fun artifactForAbis(abis: List<String>): VerifiedArtifact? {
            val abi = when {
                "arm64-v8a" in abis -> "arm64"
                "armeabi-v7a" in abis -> "arm"
                "x86_64" in abis -> "x86_64"
                "x86" in abis -> "x86"
                else -> return null
            }
            return when (abi) {
                "arm64" -> fridaArtifact(
                    "arm64",
                    "frida-server-${FridaPaths.VERSION}-android-arm64.xz",
                    "e746ffa89d328c65dc9cf05ae98f08dd0e0571df7e51889033e8bed07f2cc3ea",
                    24_627_616L,
                )
                "arm" -> fridaArtifact(
                    "arm",
                    "frida-server-${FridaPaths.VERSION}-android-arm.xz",
                    "b573ee434734599dd91733202a8405a9ea5196146f7af0853d9796c21f06d3bc",
                    9_984_912L,
                )
                "x86_64" -> fridaArtifact(
                    "x86_64",
                    "frida-server-${FridaPaths.VERSION}-android-x86_64.xz",
                    "2447b5985d210f4add5019aff22b02a3740de18ef985f504b5b70b0b29cf7b0f",
                    44_819_148L,
                )
                "x86" -> fridaArtifact(
                    "x86",
                    "frida-server-${FridaPaths.VERSION}-android-x86.xz",
                    "6e485a594f86e8251a30e078127cac81432b7fe814b88104d18e4805d938b92c",
                    23_202_268L,
                )
                else -> null
            }
        }

        private fun fridaArtifact(
            abi: String,
            fileName: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val official = "https://github.com/frida/frida/releases/download/${FridaPaths.VERSION}/$fileName"
            return VerifiedArtifact(
                id = "frida-server-$abi",
                version = FridaPaths.VERSION,
                fileName = fileName,
                url = official,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                preferredUrls = GITHUB_PROXY_PREFIXES.map { it + official },
            )
        }

        internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
