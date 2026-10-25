package com.kexu.app

import com.kexu.app.data.imports.TextDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextDecodingTest {

    @Test
    fun `纯 UTF-8 文本按 UTF-8 解码且无回退`() {
        val original = "课程名,星期\n高等数学,周一"
        val decoded = TextDecoding.decode(original.toByteArray(Charsets.UTF_8))
        assertEquals(original, decoded.text)
        assertEquals("UTF-8", decoded.charset)
        assertFalse(decoded.hadBom)
        assertFalse(decoded.hadInvalidBytes)
    }

    @Test
    fun `UTF-8 BOM 被剥离并标记`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "课程名,教师".toByteArray(Charsets.UTF_8)
        val decoded = TextDecoding.decode(bytes)
        assertEquals("课程名,教师", decoded.text)
        assertEquals("UTF-8", decoded.charset)
        assertTrue(decoded.hadBom)
        assertFalse(decoded.hadInvalidBytes)
    }

    @Test
    fun `GB18030 中文导出回退解码`() {
        val original = "高等数学,星期一,A101"
        val bytes = original.toByteArray(Charset.forName("GB18030"))
        val decoded = TextDecoding.decode(bytes)
        assertEquals(original, decoded.text)
        assertEquals("GB18030", decoded.charset)
        assertFalse(decoded.hadBom)
        assertTrue(decoded.hadInvalidBytes)
    }

    @Test
    fun `UTF-16LE BOM 解码`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "课表".toByteArray(Charsets.UTF_16LE)
        val decoded = TextDecoding.decode(bytes)
        assertEquals("课表", decoded.text)
        assertEquals("UTF-16LE", decoded.charset)
        assertTrue(decoded.hadBom)
        assertFalse(decoded.hadInvalidBytes)
    }

    @Test
    fun `UTF-16BE BOM 解码`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "课表".toByteArray(Charsets.UTF_16BE)
        val decoded = TextDecoding.decode(bytes)
        assertEquals("课表", decoded.text)
        assertEquals("UTF-16BE", decoded.charset)
        assertTrue(decoded.hadBom)
    }

    @Test
    fun `UTF-8 与 GB18030 都不合法时回退 ISO-8859-1`() {
        val bytes = byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0xFF.toByte())
        val decoded = TextDecoding.decode(bytes)
        assertEquals("ISO-8859-1", decoded.charset)
        assertEquals("Hi\u00FF", decoded.text)
        assertTrue(decoded.hadInvalidBytes)
    }

    @Test
    fun `空字节数组返回空文本`() {
        val decoded = TextDecoding.decode(ByteArray(0))
        assertEquals("", decoded.text)
        assertEquals("UTF-8", decoded.charset)
        assertFalse(decoded.hadBom)
        assertFalse(decoded.hadInvalidBytes)
    }

    @Test
    fun `只有 UTF-8 BOM 没有正文时返回空文本`() {
        val decoded = TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertEquals("", decoded.text)
        assertEquals("UTF-8", decoded.charset)
        assertTrue(decoded.hadBom)
    }
}
