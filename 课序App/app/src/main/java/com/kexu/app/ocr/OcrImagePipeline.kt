package com.kexu.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * 图片 / 扫描版 PDF → 本地 OCR 页序列。
 *
 * - 全部在 IO 线程执行，逐页处理：渲染一页 → OCR 一页 → 回收位图，避免把整本 PDF 的高分辨率页面同时放进内存；
 * - 图片解码优先 ImageDecoder（自动处理 EXIF 方向），API < 28 回退 BitmapFactory；
 * - 页数、长边都有上限；支持协程取消（用户取消导入时循环内会抛出 CancellationException）。
 */
object OcrImagePipeline {

    class ImageDecodeException(message: String) : Exception(message)
    class ScannedPdfException(message: String) : Exception(message)

    const val DEFAULT_MAX_LONG_SIDE = 2400
    const val DEFAULT_MAX_PAGES = 8

    /** 单张图片：解码 → OCR，返回一页结果。 */
    suspend fun recognizeImage(
        bytes: ByteArray,
        maxLongSide: Int = DEFAULT_MAX_LONG_SIDE
    ): OcrPage = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(bytes, maxLongSide)
        try {
            val lines = LocalOcrReader.recognize(bitmap)
            OcrPage(0, bitmap.width, bitmap.height, lines)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 扫描版 PDF：逐页渲染 + OCR。
     * @param onProgress (已完成页数, 总页数)，在 IO 线程调用，UI 侧自行切主线程。
     */
    suspend fun recognizeScannedPdf(
        context: Context,
        bytes: ByteArray,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxLongSide: Int = DEFAULT_MAX_LONG_SIDE,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<OcrPage> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw ScannedPdfException("PDF 文件为空")
        val temp = File.createTempFile("kexu_scan_", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            val pages = mutableListOf<OcrPage>()
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                try {
                    PdfRenderer(descriptor).use { renderer ->
                        val pageCount = minOf(renderer.pageCount, maxPages)
                        if (pageCount <= 0) throw ScannedPdfException("PDF 中没有可识别的页面")
                        for (index in 0 until pageCount) {
                            currentCoroutineContext().ensureActive()
                            val bitmap = renderPageBitmap(renderer, index, maxLongSide)
                            try {
                                val lines = try {
                                    LocalOcrReader.recognize(bitmap)
                                } catch (e: LocalOcrReader.BlankImageException) {
                                    emptyList()
                                }
                                pages += OcrPage(index, bitmap.width, bitmap.height, lines)
                            } finally {
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                            onProgress?.invoke(index + 1, pageCount)
                        }
                    }
                } catch (e: ScannedPdfException) {
                    throw e
                } catch (e: Exception) {
                    throw ScannedPdfException("扫描版 PDF 渲染失败：${e.message ?: "格式不支持"}")
                }
            }
            val nonEmpty = pages.filter { it.lines.isNotEmpty() }
            if (nonEmpty.isEmpty()) {
                throw LocalOcrReader.BlankImageException()
            }
            pages
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun renderPageBitmap(renderer: PdfRenderer, index: Int, maxLongSide: Int): Bitmap {
        return renderer.openPage(index).use { page ->
            val scale = minOf(
                maxLongSide.toFloat() / max(page.width, 1),
                maxLongSide.toFloat() / max(page.height, 1),
                2.5f
            ).coerceAtLeast(0.6f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    // ---------------------------------------------------------------- 图片解码

    private fun decodeBitmap(bytes: ByteArray, maxLongSide: Int): Bitmap {
        if (bytes.isEmpty()) throw ImageDecodeException("图片文件为空")
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(bytes, maxLongSide)
            } else {
                decodeWithBitmapFactory(bytes, maxLongSide)
            }
        } catch (e: Exception) {
            throw ImageDecodeException("无法解码图片：${e.message ?: "格式不支持"}")
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            throw ImageDecodeException("图片尺寸无效")
        }
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide > (maxLongSide * 1.15f).toInt()) {
            val scale = maxLongSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }
        return bitmap
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(bytes: ByteArray, maxLongSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val width = info.size.width
            val height = info.size.height
            val longSide = max(width, height)
            if (longSide > maxLongSide) {
                val sample = ceil(longSide.toFloat() / maxLongSide).toInt().coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
            }
        }
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageDecodeException("图片尺寸无效")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxLongSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ImageDecodeException("图片解码失败")
    }
}
