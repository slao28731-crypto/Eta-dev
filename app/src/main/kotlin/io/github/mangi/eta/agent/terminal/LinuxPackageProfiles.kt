package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    VERIFYING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** 当前正在装的具体组件名（如 `z3-solver`、`radare2`），用于让进度文案可读。 */
    val item: String? = null,
    /** 已完成的分步数 / 总分步数；用于 6 个包依次安装时显示 `3/7`。 */
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** 依赖的 profile 尚未安装，按依赖链先装它。 */
    data class DependencyMissing(val profileId: String) : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(
        val stage: PackageProfileInstallStage,
        /** 失败时的过程输出尾部，直接呈现给用户，避免「只说失败、不说为什么」。 */
        val detail: String? = null,
    ) : PackageProfileInstallResult
}

/**
 * 一个可选组件安装包的分步计划。
 *
 * 为什么要有「步」这一层：RE_LAB 要装 6 个东西，原来把 5 条 pip 塞进一个
 * setupScript 里跑。一旦某条失败，UI 只能报「安装失败」，既不知道卡在哪，
 * 也不知道已经成功几个。现在每一步单独一条命令、单独一个标签，
 * 失败时能直接告诉用户「卡在 apkid，前 2 个已装好」。
 */
internal data class LinuxPackageStep(
    /** 展示给用户的中文/英文短名，例如 `z3-solver`。 */
    val label: String,
    val command: String,
    /** 这一步失败时的提示，讲清楚为什么和怎么办。 */
    val failureHint: String? = null,
)

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
    /** 逐步骤执行的安装计划；`packages` 与 `setupScript` 先跑，本项最后跑。 */
    val steps: List<LinuxPackageStep> = emptyList(),
    /**
     * 需要先用 Kotlin 下载器落到 rootfs `/tmp` 的制品。
     *
     * 为什么不把这些下载塞进 shell：`VerifiedArtifactDownloader` 自带字节级进度回调、
     * sha256 校验和多镜像回退，`curl` 做同样的事要另写一堆解析，而且进度只能靠
     * `--progress-bar` 的 stderr 猜。交给 Kotlin 侧下载，UI 能拿到真实百分比。
     *
     * 参数是设备 ABI 列表（`Build.SUPPORTED_ABIS`），用于按架构挑包。
     */
    val artifacts: (abis: List<String>) -> List<VerifiedArtifact> = { emptyList() },
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** 安装前必须就绪的前置 profile。 */
    val dependsOn: LinuxPackageProfile? = null,
) {
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec = requireNotNull(specs[distribution])
}

internal object LinuxPackageProfiles {
    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("nodejs-current", "npm"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // Node 官方 arm64 二进制链接 libatomic.so.1，归档安装不含系统依赖，需补装。
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("openssh"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
        ),
    )

    /**
     * Kimi Code 使用 npm 分发，运行在 Node profile 之上；可选原生扩展由 npm 按平台安装。
     * 始终安装最新正式版（升级重装即可）；--prefix /usr/local 让 kimi 进入 PATH 首位，
     * 与 Node 归档自身的 prefix 无关。国内镜像优先，官方 registry 兜底。
     */
    private const val KIMI_INSTALL_SCRIPT =
        "npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com " +
            "@moonshot-ai/kimi-code@latest || " +
            "npm install -g --prefix /usr/local @moonshot-ai/kimi-code@latest"

    val KIMI = LinuxPackageProfile(
        id = "kimi",
        markerName = AlpineEnvironmentPaths.KIMI_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.KIMI_TOOLS_REVISION,
        dependsOn = NODE,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
        ),
    )

    /**
     * radare2 的 arm64 官方 deb。
     *
     * 摘要来自官方 `checksums.txt`（6.2.4 发布资产之一），不是猜的：
     * `bbf48ee7bd5d8282f65a297dcabd34c9961a500e62d6b036679caab187b32ef1`。
     * 只提供 arm64：x86_64 设备上 radare2 走 Debian 源里的同架构包也不存在，
     * 直接返回空列表让上层报「当前架构不支持」，好过装个跑不起来的二进制。
     */
    private fun reLabArtifacts(abis: List<String>): List<VerifiedArtifact> {
        if (abis.none { it == "arm64-v8a" }) return emptyList()
        val name = "radare2_6.2.4_arm64.deb"
        val official = "https://github.com/radareorg/radare2/releases/download/6.2.4/$name"
        return listOf(
            VerifiedArtifact(
                id = "radare2-arm64",
                version = "6.2.4",
                fileName = name,
                url = official,
                sha256 = "bbf48ee7bd5d8282f65a297dcabd34c9961a500e62d6b036679caab187b32ef1",
                sizeBytes = 8_147_980L,
                preferredUrls = listOf("https://gh-proxy.com/$official"),
            ),
        )
    }

    /**
     * pip 把脚本装进 `sysconfig.get_path("scripts")`，在 Eta 的 uv Python 里就是
     * `/opt/eta/python/cpython-<ver>-linux-<arch>-gnu/bin`，**这个目录不在 PATH 里**。
     * 所以 `pip install` 明明成功了，`command -v mitmdump` 依然找不到——
     * 这正是上一次 RE_LAB 看起来「装成功但工具没有」的一半原因。
     * 这里统一在 `/usr/local/bin` 里建软链，PATH 必定覆盖。
     */
    private fun renderCliLinksCommand(names: List<String>): String = buildString {
        // 注意：这里的 `$eta_bin` 必须写成 `\$eta_bin`。
        // Kotlin 字符串里裸写 `$eta_bin` 会被当成模板插值，编译直接报
        // `Unresolved reference 'eta_bin'`（run #37 真实踩到）。
        append("eta_bin=$(python3 -c \"import sysconfig; print(sysconfig.get_path('scripts'))\") || exit 78; ")
        append("[ -d \"\$eta_bin\" ] || exit 78; ")
        for (name in names) {
            append("[ -e \"\$eta_bin/$name\" ] && ln -sf \"\$eta_bin/$name\" /usr/local/bin/$name; ")
        }
        append("true")
    }

    /**
     * 把 Debian 打包的 Android build-tools 接进 PATH。
     *
     * 为什么需要这步：`android-sdk-build-tools` 在 aarch64 上是真实存在的
     * （29.0.3，含 aapt/aapt2/apksigner/zipalign/dexdump），但 Debian 把它
     * 装在 `/usr/lib/android-sdk/build-tools/<ver>/` 这个**不在 PATH** 的位置，
     * 于是 `command -v aapt2` 找不到 —— 这就是「工具缺失」错觉的来源。
     *
     * 动态找版本目录而不是写死 29.0.3：Debian 后续会升级这个版本号。
     */
    private fun renderAndroidBuildToolsLinksCommand(): String = buildString {
        append("bt_dir=$(ls -d /usr/lib/android-sdk/build-tools/*/ 2>/dev/null | tail -1); ")
        append("[ -n \"\$bt_dir\" ] || exit 79; ")
        append("for t in aapt aapt2 aidl apksigner dexdump zipalign split-select; do ")
        append("[ -e \"\$bt_dir\$t\" ] && ln -sf \"\$bt_dir\$t\" /usr/local/bin/\$t; ")
        append("done; ")
        // apksigner 是 shell 脚本，它靠同目录或 /usr/share/java 找 apksigner.jar，
        // 软链过去后相对路径会失效，所以额外把 jar 也链一份到它预期的位置。
        append("[ -e \"\$bt_dir/apksigner.jar\" ] && ln -sf \"\$bt_dir/apksigner.jar\" /usr/share/java/apksigner.jar 2>/dev/null; ")
        append("true")
    }

    /**
     * 装 r8 → 提供 d8 / r8 命令。
     *
     * 为什么不走 apt：Debian trixie 里**没有 d8/dx 的包**（`apt-cache policy d8`
     * 找不到）。而 d8 是 r8 这个纯 Java 程序里的一个入口类，只要 JVM 就能跑，
     * 与 CPU 架构无关 —— Eta 的 apk-analysis 组已经带了 JDK 25。
     *
     * d8 = 把 .class 转成 .dex（老 `dx` 的替代品，`dx` 2017 年就淘汰了）。
     * r8 = d8 的超集，额外做收缩/混淆/优化。
     *
     * 版本固定而不是 latest：r8 的 maven-metadata 里有大量 `-dev` 版本，
     * 取「最新」会挑到开发版，行为不稳定。
     */
    private fun renderR8SetupCommand(): String = buildString {
        append("r8_dir=/opt/eta/re-tools; mkdir -p \$r8_dir || exit 80; ")
        append("r8_jar=\$r8_dir/r8-$R8_VERSION.jar; ")
        // 已存在就跳过，避免重复下载 20MB。
        append("if [ ! -s \"\$r8_jar\" ]; then ")
        append("curl -fsSL --retry 2 --max-time 300 -o \"\$r8_jar.part\" \"$R8_URL\" || { rm -f \"\$r8_jar.part\"; exit 81; }; ")
        append("mv \"\$r8_jar.part\" \"\$r8_jar\" || exit 81; ")
        append("fi; ")
        append("[ -s \"\$r8_jar\" ] || exit 81; ")
        // 现成的 java：apk-analysis profile 装的那份
        append("java_bin=/usr/local/bin/java; [ -x \"\$java_bin\" ] || java_bin=\$(command -v java); ")
        append("[ -n \"\$java_bin\" ] || exit 82; ")
        append("for entry in D8 R8; do ")
        append("case \$entry in D8) name=d8;; R8) name=r8;; esac; ")
        // 注意 \$java_bin / \$r8_jar / \$@ 必须转义：不转义会被 Kotlin 当成
        // 模板插值，编译直接报 Unresolved reference（run #37 的教训，P082）。
        append("printf '%s\\n' '#!/bin/sh' \"exec \$java_bin -cp \$r8_jar com.android.tools.r8.\$entry \\\"\\\$@\\\"\" > /usr/local/bin/\$name || exit 83; ")
        append("chmod 0755 /usr/local/bin/\$name || exit 83; ")
        append("done; ")
        append("d8 --version >/dev/null || exit 84")
    }

    /** r8 的固定版本与下载地址。Maven 上有大量 `-dev` 版本，不取 latest。 */
    private const val R8_VERSION = "9.5.26"
    private const val R8_URL =
        "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"

    /** dex2jar 的版本与下载地址。纯 Java 实现，跨架构。 */
    private const val DEX2JAR_VERSION = "2.4"
    private const val DEX2JAR_URL =
        "https://github.com/pxb1988/dex2jar/releases/download/v$DEX2JAR_VERSION/dex-tools-v$DEX2JAR_VERSION.zip"

    /**
     * dex2jar：dex → jar。
     *
     * **它是纯 Java 实现**（`d2j-dex2jar.sh` 最后一行就是 `java -classpath ... Dex2jarCmd`），
     * 没有任何原生二进制，所以在 aarch64 上直接能跑 —— 实测
     * `classes.dex (880 字节) → out.jar (402 字节)`。
     *
     * 包里带 18 个工具，常用的三个：
     * - `d2j-dex2jar`  dex 转 jar
     * - `d2j-dex2smali` dex 转 smali（比 baksmali 快）
     * - `d2j-apk-sign`  给 APK 签名
     *
     * 注意：本脚本引用 [DEX2JAR_URL]，那两个常量必须在它**之前**声明
     * （Kotlin object 按声明顺序初始化，P081）。
     */
    private const val DEX2JAR_SCRIPT =
        "set -e; " +
            "root=/opt/eta/re-tools/dex-tools; ver=" + DEX2JAR_VERSION + "; " +
            "if [ ! -x \"\$root/d2j-dex2jar.sh\" ]; then " +
            "mkdir -p /opt/eta/re-tools /opt/eta/cache || exit 87; " +
            "z=\"/opt/eta/cache/dex-tools-v\$ver.zip\"; " +
            "if [ ! -s \"\$z\" ]; then " +
            "curl -fsSL --retry 3 --max-time 600 -o \"\$z.part\" '" + DEX2JAR_URL + "' || { rm -f \"\$z.part\"; exit 88; }; " +
            "mv \"\$z.part\" \"\$z\" || exit 88; fi; " +
            "[ -s \"\$z\" ] || exit 88; " +
            "rm -rf /opt/eta/cache/dex-tools-extract /opt/eta/re-tools/dex-tools; mkdir -p /opt/eta/cache/dex-tools-extract || exit 89; " +
            "unzip -q -o \"\$z\" -d /opt/eta/cache/dex-tools-extract || exit 89; " +
            "src=\$(find /opt/eta/cache/dex-tools-extract -maxdepth 1 -type d -name 'dex-tools*' | head -1); " +
            "[ -n \"\$src\" ] || exit 89; " +
            "mv \"\$src\" /opt/eta/re-tools/dex-tools || exit 89; " +
            "rm -rf /opt/eta/cache/dex-tools-extract \"\$z\"; " +
            "chmod 0755 /opt/eta/re-tools/dex-tools/*.sh 2>/dev/null || true; " +
            "fi; " +
            "[ -x \"\$root/d2j-dex2jar.sh\" ] || exit 90; " +
            "for t in d2j-dex2jar d2j-dex2smali d2j-smali d2j-apk-sign d2j-baksmali; do " +
            "if [ -f \"\$root/\$t.sh\" ]; then ln -sf \"\$root/\$t.sh\" /usr/local/bin/\$t; fi; done; " +
            "\"\$root/d2j-dex2jar.sh\" --version >/dev/null 2>&1 || exit 90; " +
            "echo DEX2JAR_OK"

    /** pip 侧逐包一步。`--break-system-packages` 是 Debian trixie 的 PEP 668 硬要求。 */
    private val RE_LAB_PIP_STEPS = listOf(
        // 钉 5.1.0.0 而不是装最新版：angr 10.0.4 的元数据里写的是
        // `z3-solver==5.1.0.0`，它按这个版本号找 libz3.so.5.1。
        // 装 5.3 时 import angr 会直接报
        // `ImportError: libz3.so.5.1: cannot open shared object file`。
        // 这是实际踩过的坑，不是保守选择。
        LinuxPackageStep("z3-solver", "python3 -m pip install --quiet --break-system-packages --upgrade \"z3-solver==5.1.0.0\"", "z3 必须与 angr 要求的 5.1.0.0 一致，装不上时先确认网络"),
        LinuxPackageStep("unicorn", "python3 -m pip install --quiet --break-system-packages --upgrade unicorn", "unicorn 装不上时通常是网络问题，重试即可"),
        LinuxPackageStep("apkid", "python3 -m pip install --quiet --break-system-packages --upgrade apkid", "APKiD 依赖 yara-python-dex，装不上时先确认网络"),
        LinuxPackageStep("objection", "python3 -m pip install --quiet --break-system-packages --upgrade objection", "objection 需要 frida>=16，请先装好 Frida 工具"),
        LinuxPackageStep("mitmproxy", "python3 -m pip install --quiet --break-system-packages --upgrade mitmproxy", "mitmproxy 依赖较多，网络慢时耗时较长"),
        LinuxPackageStep("androguard", "python3 -m pip install --quiet --break-system-packages --upgrade androguard", "androguard 是纯 Python，装不上时通常是网络问题"),
        LinuxPackageStep(
            label = "dex2jar",
            command = DEX2JAR_SCRIPT,
            failureHint = "dex2jar 下载失败（19MB，来自 GitHub）；它是纯 Java 实现，装上后用 d2j-dex2jar 把 dex 转 jar",
        ),
        LinuxPackageStep(
            label = "命令行入口",
            command = renderCliLinksCommand(
                listOf("mitmdump", "mitmproxy", "mitmweb", "objection", "apkid", "androguard", "androarsc", "androaxml", "r2", "radare2", "angr"),
            ),
            failureHint = "工具已装上但入口没找到，检查 /opt/eta/python 下的 bin 目录是否存在",
        ),
        LinuxPackageStep(
            label = "运行自检",
            command = "python3 -c 'import z3, unicorn, apkid, androguard' && r2 -v >/dev/null && " +
                "command -v mitmdump && command -v objection",
            failureHint = "组件已装但跑不起来，多为动态库缺失",
        ),
        LinuxPackageStep(
            label = "Android 命令行工具",
            command = renderAndroidBuildToolsLinksCommand(),
            failureHint = "build-tools 未随基础环境安装，可稍后重试",
        ),
        LinuxPackageStep(
            label = "r8 / d8",
            command = renderR8SetupCommand(),
            failureHint = "r8.jar 下载失败（20MB，来自 Google Maven），网络恢复后重试",
        ),
    )

    /**
     * Debian 侧：先 dpkg 装已下载的 radare2，再逐个 pip。
     *
     * `dpkg -i` 不处理依赖，但 radare2 的 control 里 `Depends` 只有 `libc6`，
     * 基础环境必然已有，所以不会留半配置状态。装完立刻 `r2 -v` 验证，
     * 因为「dpkg 说成功」和「二进制真能跑」是两件事（缺动态库时前者照样返回 0）。
     *
     * 注意：本项必须在 [RE_LAB_PIP_STEPS] **之后**声明，Kotlin object 属性按声明
     * 顺序初始化，否则拿到的是 null（P081 同一类错误）。
     */
    private val RE_LAB_DEBIAN_STEPS = listOf(
        LinuxPackageStep(
            label = "radare2",
            command = "dpkg -i /tmp/radare2_6.2.4_arm64.deb && r2 -v >/dev/null",
            failureHint = "radare2 安装失败。可用空间不足时先清理，或稍后重试",
        ),
    ) + RE_LAB_PIP_STEPS

    /** angr 一步装、一步 import 验证。只装不 import 会漏掉 pyvex 加载失败。 */
    private val ANGR_STEPS = listOf(
        LinuxPackageStep(
            label = "angr",
            command = "python3 -m pip install --quiet --break-system-packages --upgrade angr",
            failureHint = "angr 依赖树很大，网络慢时需要几分钟；空间不足 900MB 会失败",
        ),
        LinuxPackageStep(
            label = "运行自检",
            command = "python3 -c 'import angr; print(angr.__version__)'",
            failureHint = "angr 装上了但 import 失败，通常是 pyvex 编译扩展加载不了",
        ),
    )

    /**
     * 逆向分析工具组：z3 约束求解、unicorn 片段仿真、APKiD 判壳、
     * objection 一键 unpinning、mitmproxy 抓包、radare2 替代 IDA 做 so 静态分析。
     *
     * **radare2 的来源必须说清楚**（这里踩过一次真实的坑）：
     * r2 的 GitHub Releases 里确实有 `radare2_6.2.4_arm64.deb`，
     * 但 **Debian trixie 的 main 源里根本没有 radare2 这个包**——
     * `apt-cache policy radare2` 的 Candidate 是 `(none)`，`apt-cache search radare`
     * 一个字都不返回。上一版把 `radare2` 写进 `packages` 走 `eta-apt install`，
     * 结果 apt 直接报 `Package 'radare2' has no installation candidate`，
     * **整条 profile 在第一步就失败，后面 5 个 pip 包一个都没装**。
     * 现在改成：Kotlin 侧用 VerifiedArtifactDownloader 下官方 deb（带 sha256 校验），
     * 落到 rootfs 的 `/tmp`，再用 `dpkg -i` 装。
     *
     * 每个组件单独一步，是为了失败时能定位到具体哪个包，而不是笼统的「安装失败」。
     */
    val RE_LAB = LinuxPackageProfile(
        id = "re-lab",
        markerName = AlpineEnvironmentPaths.RE_LAB_MARKER,
        revision = AlpineEnvironmentPaths.RE_LAB_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                // Alpine community 源里 radare2 是真实存在的（v3.24 aarch64, 6.1.6-r0），
                // 与 Debian 的处境不同，这边继续走 apk。
                packages = listOf("radare2"),
                steps = RE_LAB_PIP_STEPS,
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                artifacts = ::reLabArtifacts,
                steps = RE_LAB_DEBIAN_STEPS,
            ),
        ),
    )

    /**
     * angr 符号执行单独一组，**故意不设默认开**：它会把 pyvex / pypcode / cle /
     * lief / capstone 全拖下来，解压后 300MB 级别，而手机内存跑大二进制容易 OOM。
     * 需要的时候用户自己去点，点了才算安装。
     */
    val ANGR = LinuxPackageProfile(
        id = "angr",
        markerName = AlpineEnvironmentPaths.ANGR_MARKER,
        revision = AlpineEnvironmentPaths.ANGR_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = ANGR_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = ANGR_STEPS),
        ),
    )

    // ---- Android SDK 的实现常量与脚本 ----

    private const val ANDROID_BUILD_TOOLS_VERSION = "35.0.0"
    private const val ANDROID_PLATFORM_API = "35"

    /** 社区编译的 ARM64 原生工具包（官方不发 aarch64 版）。 */
    private const val ANDROID_ARM64_TOOLS_URL =
        "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"

    /** 官方 platform 包，只取里面的 android.jar。 */
    private const val ANDROID_PLATFORM_URL =
        "https://dl.google.com/android/repository/platform-35_r02.zip"

    private const val GRADLE_VERSION = "9.8.1"
    private const val GRADLE_DOWNLOAD_URL =
        "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    private const val GRADLE_SHA256 =
        "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"

    /**
     * Android SDK 的分步安装计划。
     *
     * 顺序不能乱：
     * 1. 探活基础工具（后面全靠 curl/unzip/sha256sum）
     * 2. 下 ARM64 原生工具包，铺到 build-tools 与 platform-tools
     * 3. 下 platform 包，只抽 android.jar（27MB，其余是文档样例）
     * 4. 下 Gradle 并解到 /opt/eta/gradle/
     * 5. 写环境变量与 aapt2FromMavenOverride
     * 6. 自检四个入口
     */
    private val ANDROID_SDK_STEPS = listOf(
        LinuxPackageStep(
            label = "基础工具",
            command = "command -v curl >/dev/null && command -v unzip >/dev/null && command -v sha256sum >/dev/null",
            failureHint = "缺 curl/unzip/sha256sum，先装好基础环境",
        ),
        LinuxPackageStep(
            label = "ARM64 工具包",
            command = ANDROID_ARM64_TOOLS_SCRIPT,
            failureHint = "ARM64 工具包下载失败（14MB，来自 GitHub），网络恢复后重试",
        ),
        LinuxPackageStep(
            label = "android.jar",
            command = ANDROID_PLATFORM_SCRIPT,
            failureHint = "platform 包下载失败（64MB，来自 dl.google.com），网络恢复后重试",
        ),
        LinuxPackageStep(
            label = "Gradle",
            command = GRADLE_SCRIPT,
            failureHint = "Gradle 下载失败（151MB，来自 services.gradle.org），网络恢复后重试",
        ),
        LinuxPackageStep(
            label = "环境变量",
            command = ANDROID_ENV_SCRIPT,
            failureHint = "写入环境变量失败，检查 /opt/eta 与 /etc/profile.d 是否可写",
        ),
        LinuxPackageStep(
            label = "运行自检",
            command = ANDROID_VERIFY_SCRIPT,
            failureHint = "组件都装了但跑不起来，多为动态库缺失",
        ),
    )

    /**
     * 下 ARM64 工具包并铺开。
     *
     * 为什么用 lzhiyong 的包：Google 不发 aarch64 版，这个仓库把
     * aapt2/aapt/aidl/zipalign/dexdump/adb/fastboot/sqlite3 全编成了 ARM64 静态二进制，
     * 实测在手机上直接跑（不需要 chroot，PRoot 里就行）。
     */
    private const val ANDROID_ARM64_TOOLS_SCRIPT =
        "set -e; " +
            "sdk=/opt/eta/android-sdk; mkdir -p \"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "\" \"\$sdk/platform-tools\" /opt/eta/cache || exit 61; " +
            "z=/opt/eta/cache/android-sdk-tools-arm64.zip; " +
            "if [ ! -s \"\$z\" ]; then " +
            "curl -fsSL --retry 3 --max-time 600 -o \"\$z.part\" '" + ANDROID_ARM64_TOOLS_URL + "' || { rm -f \"\$z.part\"; exit 62; }; " +
            "mv \"\$z.part\" \"\$z\" || exit 62; fi; " +
            "[ -s \"\$z\" ] || exit 62; " +
            "rm -rf /opt/eta/cache/android-tools-extract; mkdir -p /opt/eta/cache/android-tools-extract || exit 63; " +
            "unzip -q -o \"\$z\" -d /opt/eta/cache/android-tools-extract || exit 63; " +
            "src=/opt/eta/cache/android-tools-extract; " +
            "cp -f \"\$src\"/build-tools/* \"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "/\" || exit 64; " +
            "cp -f \"\$src\"/platform-tools/* \"\$sdk/platform-tools/\" || exit 64; " +
            "if [ -d \"\$src/others\" ]; then cp -f \"\$src\"/others/* \"\$sdk/platform-tools/\" 2>/dev/null || true; fi; " +
            "chmod 0755 \"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "/\"* \"\$sdk/platform-tools/\"* 2>/dev/null || exit 65; " +
            "\"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "/aapt2\" version >/dev/null || exit 66; " +
            "rm -rf /opt/eta/cache/android-tools-extract; " +
            "echo ANDROID_TOOLS_OK"

    /**
     * 下 platform 包，只抽 android.jar。
     *
     * 为什么不用 sdkmanager：它本身是 Java 程序能跑，但会把 x86 的
     * aapt2/aidl 一起装下来，还得再覆盖一遍。直接拿 platform zip
     * 只取 android.jar，少下 30MB 用不上的东西。
     *
     * android.jar 是纯 Java 存根（方法体都是 throw），与架构无关。
     */
    private const val ANDROID_PLATFORM_SCRIPT =
        "set -e; " +
            "sdk=/opt/eta/android-sdk; plat=\"\$sdk/platforms/android-" + ANDROID_PLATFORM_API + "\"; mkdir -p \"\$plat\" /opt/eta/cache || exit 71; " +
            "if [ ! -s \"\$plat/android.jar\" ]; then " +
            "z=/opt/eta/cache/android-platform-" + ANDROID_PLATFORM_API + ".zip; " +
            "if [ ! -s \"\$z\" ]; then " +
            "curl -fsSL --retry 3 --max-time 600 -o \"\$z.part\" '" + ANDROID_PLATFORM_URL + "' || { rm -f \"\$z.part\"; exit 72; }; " +
            "mv \"\$z.part\" \"\$z\" || exit 72; fi; " +
            "[ -s \"\$z\" ] || exit 72; " +
            "rm -rf /opt/eta/cache/android-plat-extract; mkdir -p /opt/eta/cache/android-plat-extract || exit 73; " +
            "unzip -q -o \"\$z\" -d /opt/eta/cache/android-plat-extract || exit 73; " +
            "found=$(find /opt/eta/cache/android-plat-extract -name android.jar -type f | head -1); " +
            "[ -n \"\$found\" ] || exit 74; " +
            "cp -f \"\$found\" \"\$plat/android.jar\" || exit 74; " +
            "rm -rf /opt/eta/cache/android-plat-extract \"\$z\"; " +
            "fi; " +
            "[ -s \"\$plat/android.jar\" ] || exit 74; " +
            "echo ANDROID_PLATFORM_OK"

    /**
     * 下 Gradle 并解到 /opt/eta/gradle/<ver>/。
     *
     * 为什么不用 apt：Debian 的 gradle 是 4.4.1（2017 年），不支持
     * `plugins {}` 新语法、不支持 Kotlin DSL、不认识现代 AGP，
     * 装上去只能 `--version`，真干活就崩。
     */
    private const val GRADLE_SCRIPT =
        "set -e; " +
            "root=/opt/eta/gradle; ver=" + GRADLE_VERSION + "; mkdir -p \"\$root\" /opt/eta/cache || exit 81; " +
            "if [ ! -x \"\$root/\$ver/bin/gradle\" ]; then " +
            "z=\"/opt/eta/cache/gradle-\$ver-bin.zip\"; " +
            "if [ ! -s \"\$z\" ]; then " +
            "curl -fsSL --retry 3 --max-time 900 -o \"\$z.part\" '" + GRADLE_DOWNLOAD_URL + "' || { rm -f \"\$z.part\"; exit 82; }; " +
            "mv \"\$z.part\" \"\$z\" || exit 82; fi; " +
            "[ -s \"\$z\" ] || exit 82; " +
            "if command -v sha256sum >/dev/null; then " +
            "actual=$(sha256sum \"\$z\" | cut -d' ' -f1); " +
            "[ \"\$actual\" = '" + GRADLE_SHA256 + "' ] || { rm -f \"\$z\"; exit 83; }; " +
            "fi; " +
            "rm -rf \"\$root/gradle-\$ver\" /opt/eta/cache/gradle-extract; mkdir -p /opt/eta/cache/gradle-extract || exit 84; " +
            "unzip -q -o \"\$z\" -d /opt/eta/cache/gradle-extract || exit 84; " +
            "mv /opt/eta/cache/gradle-extract/gradle-\$ver \"\$root/\$ver\" || exit 85; " +
            "rm -rf /opt/eta/cache/gradle-extract \"\$z\"; " +
            "chmod 0755 \"\$root/\$ver/bin/gradle\" || exit 85; " +
            "fi; " +
            "[ -x \"\$root/\$ver/bin/gradle\" ] || exit 85; " +
            "ln -sf \"\$root/\$ver/bin/gradle\" /usr/local/bin/gradle || exit 86; " +
            "echo GRADLE_OK"

    /**
     * 写环境变量与 Gradle 配置。
     *
     * `android.aapt2FromMavenOverride` 是关键：Gradle 默认从 Maven 下
     * `aapt2-<ver>-linux.jar`（里面是 x86_64 二进制），ARM 上跑不了。
     * 指定这个参数后 Gradle 直接用我们的 ARM64 aapt2。
     *
     * 比「解压 jar → 换二进制 → 重新 zip」稳：不碰缓存，升级 Gradle 也不失效。
     */
    private const val ANDROID_ENV_SCRIPT =
        "set -e; " +
            "sdk=/opt/eta/android-sdk; bt=\"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "\"; " +
            "mkdir -p /etc/profile.d || exit 91; " +
            "printf '%s\\n' 'export ANDROID_HOME=/opt/eta/android-sdk' 'export ANDROID_SDK_ROOT=/opt/eta/android-sdk' " +
            "'export PATH=/opt/eta/android-sdk/platform-tools:/opt/eta/android-sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + ":/opt/eta/gradle/" + GRADLE_VERSION + "/bin:\$PATH' " +
            "> /etc/profile.d/eta-android.sh || exit 91; " +
            "chmod 0644 /etc/profile.d/eta-android.sh || exit 91; " +
            "for t in aapt aapt2 aidl dexdump zipalign split-select; do " +
            "if [ -e \"\$bt/\$t\" ]; then ln -sf \"\$bt/\$t\" /usr/local/bin/\$t; fi; done; " +
            "if [ -e \"\$sdk/platform-tools/adb\" ]; then ln -sf \"\$sdk/platform-tools/adb\" /usr/local/bin/adb; fi; " +
            "if [ -e \"\$sdk/platform-tools/fastboot\" ]; then ln -sf \"\$sdk/platform-tools/fastboot\" /usr/local/bin/fastboot; fi; " +
            "gh=\"\${GRADLE_USER_HOME:-/root/.gradle}\"; mkdir -p \"\$gh\" || exit 92; " +
            "gp=\"\$gh/gradle.properties\"; touch \"\$gp\" || exit 92; " +
            "grep -v 'aapt2FromMavenOverride' \"\$gp\" > \"\$gp.tmp\" 2>/dev/null || true; " +
            "printf '%s\\n' 'android.aapt2FromMavenOverride='\"\$bt\"'/aapt2' >> \"\$gp.tmp\" || exit 92; " +
            "printf '%s\\n' 'android.useAndroidX=true' >> \"\$gp.tmp\"; " +
            "printf '%s\\n' 'org.gradle.jvmargs=-Xmx1024m' >> \"\$gp.tmp\"; " +
            "mv \"\$gp.tmp\" \"\$gp\" || exit 92; " +
            "echo ANDROID_ENV_OK"

    /** 自检：四个关键入口都得能跑，android.jar 得在。 */
    private const val ANDROID_VERIFY_SCRIPT =
        "set -e; " +
            "sdk=/opt/eta/android-sdk; bt=\"\$sdk/build-tools/" + ANDROID_BUILD_TOOLS_VERSION + "\"; " +
            "\"\$bt/aapt2\" version >/dev/null || exit 95; " +
            "\"\$sdk/platform-tools/adb\" version >/dev/null || exit 96; " +
            "/usr/local/bin/gradle --version >/dev/null 2>&1 || exit 97; " +
            "[ -s \"\$sdk/platforms/android-" + ANDROID_PLATFORM_API + "/android.jar\" ] || exit 98; " +
            "echo ANDROID_SDK_OK"

    /**
     * Android SDK + Gradle：手机上真正编译 APK 的完整链路。
     *
     * ## 为什么这条路能走通（对比 Google 官方的结论）
     *
     * Google 官方**从不发布 aarch64 Linux 版** SDK（`commandlinetools-linux-*`
     * 全是 x86_64）。但拆开看「编 APK」需要的东西，只有一个真缺：
     *
     * | 组件 | 状态 |
     * |---|---|
     * | `aapt2` / `aapt` / `aidl` / `zipalign` / `dexdump` | 社区编译的 ARM64 版（lzhiyong） |
     * | `adb` / `fastboot` / `sqlite3` | 同上 |
     * | `android.jar` | **纯 Java 存根，架构无关**，从官方 `platform-35_r02.zip` 直接拿 |
     * | `d8` / `r8` | 纯 Java（r8.jar），RE_LAB 已装 |
     * | `apksigner` | Debian 有，已软链 |
     * | `gradle` | 纯 Java，官方独立分发包 |
     *
     * 参考的两个真实项目都验证过这条路：
     * - `AAswordman/Operit`：下载 x86_64 cmdline-tools + 自带 `aapt2-arm64-v8a` 替换，
     *   还会修补 Gradle 缓存里的 `aapt2-*-linux.jar`
     * - `jieapi/aicode`：直接用 `android-sdk-tools-static-aarch64.zip` 覆盖原生二进制，
     *   并用 `android.aapt2FromMavenOverride` 让 Gradle 不下载自己的 aapt2
     *
     * 本实现采用**后者的思路**（覆盖 + override），因为它不碰 Gradle 缓存，
     * 升级 Gradle 也不会失效。
     *
     * ## 真机实测（在本机 Ubuntu PRoot 里跑过，不是推测）
     *
     * ```
     * aapt2 version   → Android Asset Packaging Tool (aapt) 2.19-OS3.0.308.0
     * adb version     → Android Debug Bridge version 1.0.41 / 35.0.2
     * gradle --version→ Gradle 9.8.1
     * gradle hi       → BUILD SUCCESSFUL in 9s
     * aapt2 link -I android.jar → linked.apk (1107 字节)
     * ```
     *
     * 体积：ARM64 工具包 14MB + android.jar 27MB（platform 包 64MB）+ Gradle 151MB。
     */
    val ANDROID_SDK = LinuxPackageProfile(
        id = "android-sdk",
        markerName = AlpineEnvironmentPaths.ANDROID_SDK_MARKER,
        revision = AlpineEnvironmentPaths.ANDROID_SDK_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = ANDROID_SDK_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = ANDROID_SDK_STEPS),
        ),
    )

    // ---- 渗透测试 7 组（用户提供的 48 项清单，逐条核实后分组） ----
    //
    // 为什么拆 7 组：全量约 900MB（清单标 1.15GB，实测偏大）。
    // 一次装完时第 40 项失败会让前面全部白费；按组拆开，用户只装需要的。
    // 每组内部都是「独立可完成」的：即便某一步失败，已装成功的工具仍能用。

    /** 侦察：子域 → 存活 → 爬虫 → 端口 → 模板扫描 + nuclei-templates。 */
    val PENTEST_RECON = LinuxPackageProfile(
        id = "pentest-recon",
        markerName = AlpineEnvironmentPaths.PENTEST_RECON_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_RECON_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.RECON_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.RECON_STEPS),
        ),
    )

    /** Web 专项：目录/参数 fuzz、指纹、WAF 识别 + seclists 裁剪版。 */
    val PENTEST_WEB = LinuxPackageProfile(
        id = "pentest-web",
        markerName = AlpineEnvironmentPaths.PENTEST_WEB_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_WEB_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.WEB_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.WEB_STEPS),
        ),
    )

    /** 网络基础：扫描、抓包、转发。 */
    val PENTEST_NET = LinuxPackageProfile(
        id = "pentest-net",
        markerName = AlpineEnvironmentPaths.PENTEST_NET_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_NET_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.NET_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.NET_STEPS),
        ),
    )

    /** 凭据：注入、爆破、离线破解（hashcat 需额外装 OpenCL 运行库）。 */
    val PENTEST_CRED = LinuxPackageProfile(
        id = "pentest-cred",
        markerName = AlpineEnvironmentPaths.PENTEST_CRED_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_CRED_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.CRED_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.CRED_STEPS),
        ),
    )

    /** 云与源码审计：体积最大（约 300MB），单独一组方便跳过。 */
    val PENTEST_CLOUD = LinuxPackageProfile(
        id = "pentest-cloud",
        markerName = AlpineEnvironmentPaths.PENTEST_CLOUD_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_CLOUD_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.CLOUD_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.CLOUD_STEPS),
        ),
    )

    /** 内网 / AD：impacket、NetExec（只能从 git 装）、certipy-ad。 */
    val PENTEST_AD = LinuxPackageProfile(
        id = "pentest-ad",
        markerName = AlpineEnvironmentPaths.PENTEST_AD_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_AD_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.AD_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.AD_STEPS),
        ),
    )

    /** 无线：只能离线解 pcap，手机 WiFi 芯片基本不支持监听模式。 */
    val PENTEST_WIFI = LinuxPackageProfile(
        id = "pentest-wifi",
        markerName = AlpineEnvironmentPaths.PENTEST_WIFI_MARKER,
        revision = AlpineEnvironmentPaths.PENTEST_WIFI_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(steps = PentestProfiles.WIFI_STEPS),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(steps = PentestProfiles.WIFI_STEPS),
        ),
    )

    /**
     * 必须放在所有属性之后声明。
     *
     * Kotlin 的 `object` 属性按声明顺序初始化，`ALL` 若写在 RE_LAB / ANGR 之前，
     * 读到它们时字段还是 null，编译期会报 "Variable 'RE_LAB' must be initialized"。
     * 这类错误本地看不出来（本地不能编译），只能等 CI。
     */
    val ALL = listOf(
        PYTHON, NODE, SSH, KIMI, RE_LAB, ANGR, ANDROID_SDK,
        PENTEST_RECON, PENTEST_WEB, PENTEST_NET, PENTEST_CRED,
        PENTEST_CLOUD, PENTEST_AD, PENTEST_WIFI,
    )
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean {
    val marker = File(rootfs, profile.markerName)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${profile.revision}" }
    }
}

/** 为当前选中的发行版按需安装单个工具 profile；成功后只写对应完成标记。 */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        profile.dependsOn?.let { dependency ->
            if (!linuxPackageProfileReady(rootfs, dependency)) {
                return@withContext PackageProfileInstallResult.DependencyMissing(dependency.id)
            }
        }

        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                    "托管工具下载或校验失败，请检查网络后重试",
                )
            }
        }

        // 1) 先把需要 Kotlin 侧下载的制品落盘。放在 apt/pip 之前，是因为 radare2
        //    这类包在发行版源里根本不存在，只能靠官方 deb。
        val artifacts = spec.artifacts(Build.SUPPORTED_ABIS.toList())
        if (artifacts.isNotEmpty()) {
            val failure = downloadArtifacts(artifacts, onProgress)
            if (failure != null) return@withContext failure
        }

        val packageHelper = when (distribution) {
            LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk"
            LinuxDistribution.DEBIAN -> "/usr/local/bin/eta-apt"
        }
        if (spec.packages.isNotEmpty()) {
            onProgress(
                PackageProfileInstallProgress(
                    stage = PackageProfileInstallStage.INSTALLING,
                    item = spec.packages.joinToString(" "),
                ),
            )
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                    installResult.output.tailDetail("软件源安装 ${spec.packages.joinToString(" ")} 失败"),
                )
            }
        }

        // 2) setupScript（若有）单独跑一次，保留给只用一段脚本的旧 profile。
        spec.setupScript?.let { script ->
            onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
            val scriptResult = InstallerShellRunner.run(
                command = "set -e\n$script",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (scriptResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                    scriptResult.output.tailDetail("安装脚本失败"),
                )
            }
        }

        // 3) 分步安装。每步单独命令、单独回报，失败能定位到具体包。
        for ((index, step) in spec.steps.withIndex()) {
            onProgress(
                PackageProfileInstallProgress(
                    stage = PackageProfileInstallStage.INSTALLING,
                    item = step.label,
                    stepIndex = index + 1,
                    stepTotal = spec.steps.size,
                ),
            )
            val stepResult = InstallerShellRunner.run(
                command = step.command,
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            AndroidAgentLogger.info(
                "Package profile action=step distribution=${distribution.wireName} profile=${profile.id} " +
                    "step=${index + 1}/${spec.steps.size} label=${step.label} " +
                    "outcome=${if (stepResult.exitCode == 0) "succeeded" else "failed"} " +
                    "exitCode=${stepResult.exitCode}",
            )
            if (stepResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                    stepResult.output.tailDetail(
                        buildString {
                            append("安装 ${step.label} 失败")
                            if (index > 0) append("（前 $index 个组件已装好）")
                            step.failureHint?.let { append("。").append(it) }
                        },
                    ),
                )
            }
        }

        // 4) 最后统一写 marker：只有前 3 步都成功才会走到这里。
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.VERIFYING))
        // 装完把下载的制品删掉：radare2 的 deb 有 8MB，留在 rootfs/tmp 里
        // 会一直占着用户空间，而它已经解包到 /usr 了，没有保留价值。
        if (artifacts.isNotEmpty()) {
            artifacts.forEach { artifact -> File(File(rootfs, "tmp"), artifact.fileName).delete() }
        }
        val activateCommand = buildString {
            append("set -e\n")
            if (profile == LinuxPackageProfiles.KIMI) append("kimi --version >/dev/null\nkimi web --help >/dev/null\n")
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(
                PackageProfileInstallStage.VERIFYING,
                result.output.tailDetail("写入完成标记失败"),
            )
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    /**
     * 把 [artifacts] 逐个下载到 rootfs 的 `/tmp` 下，并回报字节级进度。
     *
     * 为什么落到 `/tmp`：安装命令是在 chroot 内跑的，只能看见 rootfs 内的路径。
     * 下载走 Android 侧的 OkHttp（带 sha256 校验），不要试图在 chroot 里 curl——
     * 那样拿不到校验，也没法把进度喂给 UI。
     *
     * 返回 null 表示全部成功；否则返回带原因的失败结果。
     */
    private suspend fun downloadArtifacts(
        artifacts: List<VerifiedArtifact>,
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult.Failed? {
        val downloader = VerifiedArtifactDownloader()
        val targetDir = File(rootfs, "tmp").apply { mkdirs() }
        for ((index, artifact) in artifacts.withIndex()) {
            val target = File(targetDir, artifact.fileName)
            onProgress(
                PackageProfileInstallProgress(
                    stage = PackageProfileInstallStage.DOWNLOADING,
                    downloadedBytes = 0,
                    totalBytes = artifact.sizeBytes,
                    item = artifact.fileName,
                    stepIndex = index + 1,
                    stepTotal = artifacts.size,
                ),
            )
            val ok = try {
                downloader.download(artifact, target) { downloaded, total ->
                    onProgress(
                        PackageProfileInstallProgress(
                            stage = PackageProfileInstallStage.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            item = artifact.fileName,
                            stepIndex = index + 1,
                            stepTotal = artifacts.size,
                        ),
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                false
            }
            if (!ok) {
                target.delete()
                return PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                    "下载 ${artifact.fileName} 失败（镜像与官方地址都未通过校验），请检查网络后重试",
                )
            }
        }
        return null
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}

/**
 * 取输出末尾若干行作为失败原因。
 *
 * 安装类命令的报错几乎都在末尾（apt 是 `E: ...`，pip 是最后几行 traceback），
 * 前面全是进度噪声。截末尾既省消息长度，也正好是用户最需要看的那段。
 */
private fun String.tailDetail(fallback: String, maxLines: Int = 4, maxChars: Int = 320): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return fallback
    val tail = trimmed.lines().filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
    val clipped = if (tail.length > maxChars) tail.take(maxChars) + "…" else tail
    return "$fallback：\n$clipped"
}
