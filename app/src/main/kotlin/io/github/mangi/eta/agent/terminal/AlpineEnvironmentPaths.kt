package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** Eta 管理的 Linux 工具环境路径；内部历史包名不参与对外展示。 */
internal object AlpineEnvironmentPaths {
    const val READY_MARKER = LinuxEnvironmentPaths.READY_MARKER
    const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
    const val APK_ANALYSIS_MARKER = ".eta-apk-analysis-ready"
    const val PYTHON_TOOLS_MARKER = ".eta-python-tools-ready"
    const val NODE_TOOLS_MARKER = ".eta-node-tools-ready"
    const val SSH_TOOLS_MARKER = ".eta-ssh-tools-ready"
    const val KIMI_TOOLS_MARKER = ".eta-kimi-tools-ready"
    /** 逆向分析工具组（z3/unicorn/apkid/objection/mitmproxy/radare2）。 */
    const val RE_LAB_MARKER = ".eta-re-lab-ready"
    /** angr 符号执行单独一组：体积大（含 pyvex/pypcode/lief/cle），默认关。 */
    const val ANGR_MARKER = ".eta-angr-ready"
    /** Android SDK + Gradle：ARM64 工具包 + android.jar + Gradle，可在手机上编 APK。 */
    const val ANDROID_SDK_MARKER = ".eta-android-sdk-ready"
    /** 渗透测试 7 组：各自独立 marker，装一组只写一组的标记。 */
    const val PENTEST_RECON_MARKER = ".eta-pentest-recon-ready"
    const val PENTEST_WEB_MARKER = ".eta-pentest-web-ready"
    const val PENTEST_NET_MARKER = ".eta-pentest-net-ready"
    const val PENTEST_CRED_MARKER = ".eta-pentest-cred-ready"
    const val PENTEST_CLOUD_MARKER = ".eta-pentest-cloud-ready"
    const val PENTEST_AD_MARKER = ".eta-pentest-ad-ready"
    const val PENTEST_WIFI_MARKER = ".eta-pentest-wifi-ready"
    const val TOOLSET_REVISION = 1
    // revision 2：补齐 ARM64 aapt2，解掉「只解码不回编译」的限制。
    const val APK_ANALYSIS_REVISION = 2
    const val PYTHON_TOOLS_REVISION = 1
    // revision 2：Debian 规格补装 libatomic1，已就绪环境需重走安装补齐依赖。
    const val NODE_TOOLS_REVISION = 2
    const val SSH_TOOLS_REVISION = 1
    const val KIMI_TOOLS_REVISION = 1
    const val RE_LAB_REVISION = 1
    const val ANGR_REVISION = 1
    const val ANDROID_SDK_REVISION = 1
    const val PENTEST_RECON_REVISION = 1
    const val PENTEST_WEB_REVISION = 1
    const val PENTEST_NET_REVISION = 1
    const val PENTEST_CRED_REVISION = 1
    const val PENTEST_CLOUD_REVISION = 1
    const val PENTEST_AD_REVISION = 1
    const val PENTEST_WIFI_REVISION = 1
    // 两个新 profile 的 marker 说明见 LinuxPackageProfiles.RE_LAB / ANGR 的注释。

    fun environmentDir(context: Context): File =
        LinuxEnvironmentPaths.environmentDir(context, LinuxDistribution.ALPINE)

    fun rootfsDir(context: Context): File =
        LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.ALPINE)

    fun artifactDir(context: Context): File =
        File(context.cacheDir, "linux-installer/artifacts")

    fun profileStagingDir(context: Context, profile: String): File =
        File(context.cacheDir, "linux-installer/profiles/$profile.installing")

    fun rootfsReady(rootfsPath: String?): Boolean {
        return LinuxEnvironmentPaths.rootfsReady(rootfsPath)
    }

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        val marker = File(rootfsPath, COMMON_TOOLS_MARKER)
        if (!marker.isFile) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "toolset=$TOOLSET_REVISION" }
            }
        }.getOrDefault(false)
    }

}
