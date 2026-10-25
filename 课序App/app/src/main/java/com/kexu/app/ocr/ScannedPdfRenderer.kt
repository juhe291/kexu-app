package com.kexu.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描版 PDF：逐页渲染成 Bitmap 交给本地 OCR。
 * 不一次性把全部页面以高分辨率加载到内存：逐页渲染、限制长边与页数。
 */
object ScannedPdfRenderer {

    data class RenderedPage(val pageIndex: Int, val bitmap: Bitmap)

    suspend fun renderPages(context: Context, uri: Uri, maxPages: Int = 8, maxLongSide: Int = 2200): List<RenderedPage> =
        withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("无法打开 PDF 文件")
            val pfd: ParcelFileDescriptor = descriptor
            try {
                PdfRenderer(pfd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    (0 until count).mapNotNull { index ->
                        renderer.openPage(index).use { page ->
                            val scale = minOf(
                                maxLongSide.toFloat() / page.width,
                                maxLongSide.toFloat() / page.height,
                                2.5f
                            ).coerceAtLeast(1f)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            RenderedPage(index, bitmap)
                        }
                    }
                }
            } finally {
                try { pfd.close() } catch (_: Exception) { }
            }
        }
}
