package com.kexu.app

import com.kexu.app.data.UpdateChecker
import com.kexu.app.data.UpdateInfo
import com.kexu.app.data.UpdateResult
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 「检查更新」纯逻辑测试：版本号比较、GitHub Releases JSON 解析、中文错误文案。
 *
 * org.json 在 Android 单元测试里由 Robolectric 提供（与 JsonTimetableImporterTest 一致）；
 * 这里的用例都不发真实网络请求，checkForUpdate 的网络分支只在真机上触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateCheckerTest {

    // ---------------------------------------------------------------- 版本号解析与比较

    @Test
    fun `版本号逐段按数字比较而不是按字符串比较`() {
        assertTrue(UpdateChecker.isNewerVersion("1.2.10", "1.2.9"))
        assertEquals(1, UpdateChecker.compareVersions("1.2.10", "1.2.9"))
        assertEquals(-1, UpdateChecker.compareVersions("1.2.9", "1.2.10"))
        assertEquals(0, UpdateChecker.compareVersions("1.2.10", "1.2.10"))
    }

    @Test
    fun `v 前缀与纯数字视为同一版本`() {
        assertEquals(listOf(1, 2, 6), UpdateChecker.parseVersion("v1.2.6"))
        assertEquals(listOf(1, 2, 6), UpdateChecker.parseVersion("1.2.6"))
        assertEquals(0, UpdateChecker.compareVersions("v1.2.6", "1.2.6"))
        assertFalse(UpdateChecker.isNewerVersion("V1.2.6", "1.2.6"))
    }

    @Test
    fun `相同版本不算更新`() {
        assertFalse(UpdateChecker.isNewerVersion("1.2.6", "1.2.6"))
        assertFalse(UpdateChecker.isNewerVersion("v1.2.6", "1.2.6"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.5", "1.2.6"))
    }

    @Test
    fun `段数不同时缺失段按 0 处理`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.3", "1.2.6"))
        assertFalse(UpdateChecker.isNewerVersion("1.2", "1.2.6"))
        assertTrue(UpdateChecker.isNewerVersion("2", "1.9.9"))
    }

    @Test
    fun `预发布标签不提示更新`() {
        assertTrue(UpdateChecker.isPreRelease("v1.3.0-beta"))
        assertTrue(UpdateChecker.isPreRelease("1.3.0-rc.1"))
        assertFalse(UpdateChecker.isPreRelease("v1.3.0"))
        // 远端是预发布：即使版本号更大也不提示
        assertFalse(UpdateChecker.isNewerVersion("v1.3.0-beta", "1.2.6"))
        // 同一版本号发布正式版时正常提示
        assertTrue(UpdateChecker.isNewerVersion("v1.3.0", "1.2.6"))
    }

    @Test
    fun `非法版本号返回 null 或 false 而不是抛异常`() {
        listOf("", "   ", "abc", "1.x", "v", "1.2.6.", "-beta").forEach { raw ->
            assertNull("应无法解析：$raw", UpdateChecker.parseVersion(raw))
            assertFalse("非法版本不应提示更新：$raw", UpdateChecker.isNewerVersion(raw, "1.2.6"))
        }
        assertFalse(UpdateChecker.isNewerVersion("1.2.7", "不是版本号"))
    }

    // ---------------------------------------------------------------- Releases JSON 解析

    private val releaseJson = """
        {
          "tag_name": "v1.2.7",
          "name": "课序 V1.2.7",
          "body": "## 本次更新\n- 新增「检查更新」\n- 修复若干问题",
          "html_url": "https://github.com/juhe291/kexu-app/releases/tag/v1.2.7",
          "published_at": "2026-10-20T08:30:00Z",
          "assets": [
            { "name": "课序-v1.2.7-debug.apk", "browser_download_url": "https://github.com/juhe291/kexu-app/releases/download/v1.2.7/kexu-debug.apk", "size": 11000000 },
            { "name": "课序-v1.2.7-release.apk", "browser_download_url": "https://github.com/juhe291/kexu-app/releases/download/v1.2.7/kexu-release.apk", "size": 12345678 },
            { "name": "checksums.txt", "browser_download_url": "https://github.com/juhe291/kexu-app/releases/download/v1.2.7/checksums.txt", "size": 128 }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析正常 release 响应`() {
        val info = UpdateChecker.parseReleaseJson(releaseJson)
        assertEquals("1.2.7", info.versionName)
        assertTrue(info.notes.contains("检查更新"))
        assertEquals("https://github.com/juhe291/kexu-app/releases/tag/v1.2.7", info.releaseUrl)
        assertEquals("2026-10-20T08:30:00Z", info.publishedAt)
        assertEquals(12345678L, info.apkSize)
        val apkUrl = requireNotNull(info.apkUrl) { "正常 release 应解析出 apkUrl" }
        assertTrue("应取 release 包的下载地址：$apkUrl", apkUrl.endsWith("kexu-release.apk"))
        assertFalse("同一个 release 有 debug 包时不应选 debug：$apkUrl", apkUrl.contains("debug"))
    }

    @Test
    fun `没有 apk 资产时 apkUrl 为空且大小为 0`() {
        val json = """
            { "tag_name": "v1.3.0", "assets": [
                { "name": "source.zip", "browser_download_url": "https://example.com/source.zip", "size": 10 }
            ] }
        """.trimIndent()
        val info = UpdateChecker.parseReleaseJson(json)
        assertEquals("1.3.0", info.versionName)
        assertNull(info.apkUrl)
        assertEquals(0L, info.apkSize)
    }

    @Test
    fun `字段缺失时使用默认值且不崩溃`() {
        val info = UpdateChecker.parseReleaseJson("""{ "tag_name": "v1.2.9" }""")
        assertEquals("1.2.9", info.versionName)
        assertEquals("", info.notes)
        assertEquals(UpdateChecker.RELEASES_PAGE, info.releaseUrl)
        assertNull(info.apkUrl)
        assertEquals(0L, info.apkSize)
        assertEquals("", info.publishedAt)
    }

    @Test
    fun `tag_name 缺失时从 name 兜底提取版本号`() {
        val info = UpdateChecker.parseReleaseJson("""{ "name": "课序 V1.2.8" }""")
        assertEquals("1.2.8", info.versionName)
    }

    @Test
    fun `JSON 不合法时抛 JSONException 交给调用方处理`() {
        assertThrows(JSONException::class.java) {
            UpdateChecker.parseReleaseJson("{ 这不是合法 JSON")
        }
    }

    // ---------------------------------------------------------------- evaluateRelease

    private fun info(version: String) = UpdateInfo(
        versionName = version,
        notes = "更新说明",
        releaseUrl = UpdateChecker.RELEASES_PAGE,
        apkUrl = null,
        apkSize = 0L,
        publishedAt = ""
    )

    @Test
    fun `远端版本更高时返回 Available`() {
        val remote = UpdateChecker.parseReleaseJson(releaseJson)
        val result = UpdateChecker.evaluateRelease(remote, "1.2.6")
        assertTrue("应为有新版：$result", result is UpdateResult.Available)
        assertEquals("1.2.7", (result as UpdateResult.Available).info.versionName)
    }

    @Test
    fun `远端版本不高于本地时返回 UpToDate`() {
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluateRelease(info("1.2.6"), "1.2.6"))
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluateRelease(info("1.2.5"), "1.2.6"))
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluateRelease(info("1.3.0-beta"), "1.2.6"))
    }

    @Test
    fun `版本号无法解析时返回中文 Failed 而不是抛异常`() {
        val remote = UpdateChecker.evaluateRelease(info("不是版本号"), "1.2.6")
        assertTrue(remote is UpdateResult.Failed)
        val remoteMessage = (remote as UpdateResult.Failed).message
        assertTrue(remoteMessage, remoteMessage.contains("版本号"))

        val local = UpdateChecker.evaluateRelease(info("1.2.7"), "未知")
        assertTrue(local is UpdateResult.Failed)
        assertTrue((local as UpdateResult.Failed).message.isNotBlank())
    }

    // ---------------------------------------------------------------- 错误文案

    @Test
    fun `错误文案是可读中文且不暴露英文异常原文`() {
        val messages = listOf(
            UpdateChecker.readableMessage(UnknownHostException("api.github.com")),
            UpdateChecker.readableMessage(SocketTimeoutException("timeout")),
            UpdateChecker.readableMessage(JSONException("bad token")),
            UpdateChecker.readableMessage(IOException("broken pipe")),
            UpdateChecker.readableMessage(IllegalStateException("boom"))
        )
        messages.forEach { message ->
            assertTrue("应为非空中文提示：$message", message.isNotBlank())
            assertTrue("应包含中文字符：$message", message.any { it in '\u4e00'..'\u9fff' })
            assertFalse("不应把英文异常原文带给用户：$message", message.contains("boom") || message.contains("broken pipe"))
        }
        assertTrue(UpdateChecker.readableMessage(UnknownHostException()).contains("网络"))
        assertTrue(UpdateChecker.readableMessage(SocketTimeoutException()).contains("超时"))
        assertTrue(UpdateChecker.readableMessage(JSONException("bad")).contains("格式"))
    }
}
