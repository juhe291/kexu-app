package com.kexu.app.data.imports

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 导入文件文本解码：先看 BOM，再严格 UTF-8，失败回退 GB18030（中文 Excel 常见），最后 ISO-8859-1。
 *
 * 只依赖 java.nio.charset，可在 JVM 单测直接运行；任何输入都返回文本而不抛异常，
 * 解码过程中的不确定性通过 [DecodedText.hadBom] / [DecodedText.hadInvalidBytes] 暴露给调用方。
 */
object TextDecoding {

    /**
     * 解码结果。
     * [charset] 是实际使用的编码名；[hadBom] 表示输入是否带 BOM（BOM 已从文本中剥离）；
     * [hadInvalidBytes] 表示解码过程中是否发生过回退或替换字符（true 说明文本可能不是 UTF-8 或有字节无法映射）。
     */
    data class DecodedText(
        val text: String,
        val charset: String,
        val hadBom: Boolean,
        val hadInvalidBytes: Boolean
    )

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private const val REPLACEMENT_CHAR = '\uFFFD'
    private const val GB18030 = "GB18030"
    private const val ISO_8859_1 = "ISO-8859-1"

    private val gb18030Charset: Charset? by lazy {
        runCatching { Charset.forName(GB18030) }.getOrNull()
    }

    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.isEmpty()) {
            return DecodedText(text = "", charset = "UTF-8", hadBom = false, hadInvalidBytes = false)
        }
        return when {
            bytes.startsWith(UTF8_BOM) ->
                decodeUtf8WithBom(bytes)

            bytes.startsWith(UTF16LE_BOM) ->
                decodeUtf16WithBom(bytes, UTF16LE_BOM.size, "UTF-16LE")

            bytes.startsWith(UTF16BE_BOM) ->
                decodeUtf16WithBom(bytes, UTF16BE_BOM.size, "UTF-16BE")

            else -> decodeWithoutBom(bytes)
        }
    }

    /** UTF-8 BOM 是强信号，但仍可能遇到损坏文件：严格解不开时按中文编码继续兜底而非报错。 */
    private fun decodeUtf8WithBom(bytes: ByteArray): DecodedText {
        val text = strictDecode(bytes, UTF8_BOM.size, StandardCharsets.UTF_8)
        if (text != null) {
            return DecodedText(
                text = text,
                charset = "UTF-8",
                hadBom = true,
                hadInvalidBytes = text.contains(REPLACEMENT_CHAR)
            )
        }
        return fallbackDecode(bytes, UTF8_BOM.size).copy(hadBom = true, hadInvalidBytes = true)
    }

    /** UTF-16 BOM 已明确字节序，按其解码；偶发坏字节用替换字符兜底并标记。 */
    private fun decodeUtf16WithBom(bytes: ByteArray, bomSize: Int, label: String): DecodedText {
        val charset = runCatching { Charset.forName(label) }.getOrNull()
            ?: return DecodedText(String(bytes, bomSize, bytes.size - bomSize, StandardCharsets.ISO_8859_1), label, hadBom = true, hadInvalidBytes = true)
        val strict = strictDecode(bytes, bomSize, charset)
        if (strict != null) {
            return DecodedText(strict, label, hadBom = true, hadInvalidBytes = strict.contains(REPLACEMENT_CHAR))
        }
        val lenient = lenientDecode(bytes, bomSize, charset)
        return DecodedText(lenient, label, hadBom = true, hadInvalidBytes = true)
    }

    private fun decodeWithoutBom(bytes: ByteArray): DecodedText {
        val utf8 = strictDecode(bytes, 0, StandardCharsets.UTF_8)
        if (utf8 != null) {
            return DecodedText(utf8, "UTF-8", hadBom = false, hadInvalidBytes = false)
        }
        return fallbackDecode(bytes, 0)
    }

    /** 严格 UTF-8 失败后的兜底链：GB18030 严格 → GB18030 替换 → ISO-8859-1。 */
    private fun fallbackDecode(bytes: ByteArray, offset: Int): DecodedText {
        val gb = gb18030Charset
        if (gb != null) {
            val strict = strictDecode(bytes, offset, gb)
            if (strict != null) {
                return DecodedText(strict, GB18030, hadBom = false, hadInvalidBytes = true)
            }
            val lenient = lenientDecode(bytes, offset, gb)
            if (!lenient.contains(REPLACEMENT_CHAR)) {
                return DecodedText(lenient, GB18030, hadBom = false, hadInvalidBytes = true)
            }
        }
        val latin = String(bytes, offset, bytes.size - offset, StandardCharsets.ISO_8859_1)
        return DecodedText(latin, ISO_8859_1, hadBom = false, hadInvalidBytes = true)
    }

    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }.getOrNull()

    private fun lenientDecode(bytes: ByteArray, offset: Int, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
