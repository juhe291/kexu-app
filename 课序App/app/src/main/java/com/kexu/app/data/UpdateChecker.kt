package com.kexu.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** GitHub Releases 里的最新版本信息。 */
data class UpdateInfo(
    /** 规范化后的版本号：tag_name 为 v1.2.7 时这里是 1.2.7，预发布后缀（如 -beta）会保留。 */
    val versionName: String,
    /** 更新说明（release body 原文，可能为空）。 */
    val notes: String,
    /** Release 页面地址（html_url，缺失时回退到 Releases 列表页）。 */
    val releaseUrl: String,
    /** 以 .apk 结尾的资产的下载地址；发布里没有 APK 资产时为 null。 */
    val apkUrl: String?,
    /** APK 字节数；没有 APK 资产时为 0。 */
    val apkSize: Long,
    /** 发布时间原文（ISO 8601 字符串），可能为空。 */
    val publishedAt: String
)

/** 检查更新的结果：已是最新 / 有可用更新 / 失败（带可读中文原因）。 */
sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: UpdateInfo) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * 检查更新：请求 GitHub Releases 的 latest 接口，比较版本号并取出 APK 下载地址。
 *
 * 只有用户主动点「检查更新」时才会发起这一次网络请求；App 其余功能仍然完全离线。
 * [checkForUpdate] 不会向调用方抛异常：网络失败、限流、JSON 异常都会转成
 * [UpdateResult.Failed] 里的中文说明。协程取消会原样抛出，避免吞掉取消信号。
 */
object UpdateChecker {

    /** Releases 列表页：没有 APK 资产或 html_url 缺失时的兜底跳转地址。 */
    const val RELEASES_PAGE = "https://github.com/juhe291/kexu-app/releases"

    /** GitHub 最新 Release API（latest 只返回正式发布，不含 draft / prerelease）。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/juhe291/kexu-app/releases/latest"

    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT = "kexu-app-update"
    private const val ACCEPT = "application/vnd.github+json"

    /**
     * 检查是否有比 [currentVersion] 更新的版本。
     * @param currentVersion 当前安装版本，通常传 BuildConfig.VERSION_NAME，如 "1.2.6"。
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            evaluateRelease(parseReleaseJson(fetchLatestReleaseJson()), currentVersion)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            UpdateResult.Failed(readableMessage(error))
        }
    }

    // ------------------------------------------------------------------ 网络

    private fun fetchLatestReleaseJson(): String {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", ACCEPT)
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpStatusException(code)
            if (body.isBlank()) throw IOException("empty release response")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    // ------------------------------------------------------------------ JSON 解析

    /**
     * 解析 GitHub Releases 的 latest 响应。字段缺失时给出空值/兜底值，不抛 [JSONException]
     * 以外的异常；JSON 本身不合法时由 [JSONException] 向上传递，由 [checkForUpdate] 转成中文提示。
     */
    fun parseReleaseJson(json: String): UpdateInfo {
        val root = JSONObject(json)
        val tag = root.optText("tag_name")
        val title = root.optText("name")
        // 正常情况下 tag_name 一定存在；万一只剩 name（如「课序 V1.2.7」），从中兜底提取版本号。
        val version = normalizeVersion(tag).ifBlank { versionFromTitle(title) }

        var apkUrl: String? = null
        var apkSize = 0L
        var apkIsReleaseBuild = false
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val fileName = asset.optText("name")
                val downloadUrl = asset.optText("browser_download_url")
                if (!fileName.endsWith(".apk", ignoreCase = true) || downloadUrl.isBlank()) continue
                // 同一个 release 可能同时挂 debug 与 release 包：优先 release 包，否则取第一个 APK。
                val isRelease = fileName.contains("release", ignoreCase = true)
                if (apkUrl == null || (isRelease && !apkIsReleaseBuild)) {
                    apkUrl = downloadUrl
                    apkSize = asset.optLong("size", 0L)
                    apkIsReleaseBuild = isRelease
                }
            }
        }

        return UpdateInfo(
            versionName = version,
            notes = root.optText("body"),
            releaseUrl = root.optText("html_url").ifBlank { RELEASES_PAGE },
            apkUrl = apkUrl,
            apkSize = if (apkSize > 0L) apkSize else 0L,
            publishedAt = root.optText("published_at")
        )
    }

    /** GitHub 显式给 null 时 Android 的 optString 会返回字符串 "null"，这里统一收敛成空串。 */
    private fun JSONObject.optText(name: String): String {
        val raw = optString(name, "")
        return if (raw.isBlank() || raw.equals("null", ignoreCase = true)) "" else raw
    }

    /** tag_name 去掉前缀 v/V（仅当后面紧跟数字时），保留 -beta 之类的后缀。 */
    fun normalizeVersion(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length > 1 && (trimmed[0] == 'v' || trimmed[0] == 'V') && trimmed[1].isDigit()) {
            trimmed.substring(1)
        } else {
            trimmed
        }
    }

    private val VERSION_IN_TITLE = Regex("""[vV]?(\d+(?:\.\d+)+)""")

    private fun versionFromTitle(title: String): String =
        VERSION_IN_TITLE.find(title)?.groupValues?.getOrNull(1).orEmpty()

    // ------------------------------------------------------------------ 版本比较

    /**
     * 把 "1.2.6" / "v1.2.6" / "1.2.6-beta.1" 解析成数字列表（忽略 - / + 之后的非数字后缀）。
     * 无法解析（空串、"abc"、"1.x" 等）时返回 null。
     */
    fun parseVersion(raw: String): List<Int>? {
        val trimmed = raw.trim()
        val withoutPrefix = if (trimmed.isNotEmpty() && (trimmed[0] == 'v' || trimmed[0] == 'V')) {
            trimmed.substring(1)
        } else {
            trimmed
        }
        val core = withoutPrefix.substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return null
        val parts = core.split('.')
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            numbers.add(digits.toIntOrNull() ?: return null)
        }
        return if (numbers.isEmpty()) null else numbers
    }

    /** 是否为预发布标签（tag 在 - 之前以外还带后缀，如 v1.3.0-beta）：预发布不提示更新。 */
    fun isPreRelease(raw: String): Boolean {
        val trimmed = raw.trim()
        val withoutPrefix = if (trimmed.isNotEmpty() && (trimmed[0] == 'v' || trimmed[0] == 'V')) {
            trimmed.substring(1)
        } else {
            trimmed
        }
        return withoutPrefix.substringBefore('+').contains('-')
    }

    /** 逐段比较解析后的版本号：缺的段按 0 处理，如 1.2 == 1.2.0。 */
    fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /**
     * 版本号比较：[left] 比 [right] 新返回正数，相等返回 0，更旧返回负数；任一侧无法解析时返回 null。
     */
    fun compareVersions(left: String, right: String): Int? {
        val leftParts = parseVersion(left) ?: return null
        val rightParts = parseVersion(right) ?: return null
        return compareVersionParts(leftParts, rightParts)
    }

    /** [remoteVersion] 是否比 [currentVersion] 更新；预发布标签或非法版本号一律视为没有更新。 */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        if (isPreRelease(remoteVersion)) return false
        return (compareVersions(remoteVersion, currentVersion) ?: return false) > 0
    }

    /**
     * 纯逻辑：拿解析好的 [info] 和本地版本做比较，产出最终结果。
     * 抽出来便于单测覆盖「有新版 / 已最新 / 预发布 / 版本号异常」，测试不依赖网络。
     */
    fun evaluateRelease(info: UpdateInfo, currentVersion: String): UpdateResult {
        val remote = parseVersion(info.versionName)
            ?: return UpdateResult.Failed("无法识别发布版本号，请稍后再试")
        if (isPreRelease(info.versionName)) return UpdateResult.UpToDate
        val current = parseVersion(currentVersion)
            ?: return UpdateResult.Failed("无法识别当前版本号，请稍后再试")
        return if (compareVersionParts(remote, current) > 0) {
            UpdateResult.Available(info)
        } else {
            UpdateResult.UpToDate
        }
    }

    // ------------------------------------------------------------------ 错误文案

    /** 把网络/解析异常翻译成可以直接展示给用户的中文说明（不暴露英文异常信息）。 */
    fun readableMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "网络似乎未连接，请检查网络后重试"
        is SocketTimeoutException -> "连接 GitHub 超时了，请稍后重试"
        is HttpStatusException -> when (error.code) {
            401, 403, 429 -> "GitHub 暂时限制了访问，请稍后再试"
            404 -> "GitHub 上还没有发布版本，请稍后再试"
            else -> "更新服务器返回错误（HTTP ${error.code}），请稍后再试"
        }
        is JSONException -> "更新信息格式异常，请稍后重试"
        is IOException -> "网络请求失败，请检查网络后重试"
        else -> "检查更新失败，请稍后重试"
    }
}
