package com.kexu.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地图片 OCR：ML Kit 中文识别（模型随 APK 打包，完全离线，不联网）。
 * 只负责“图片/位图 → 带像素坐标的文字行”；表格结构恢复与课程语义解析由
 * [OcrTableRecovery] / [OcrCourseRecordParser] 完成。
 */
object LocalOcrReader {

    class BlankImageException : Exception("图片中没有识别到文字，请换一张更清晰、方向正确的课表截图")
    class OcrFailedException(message: String) : Exception(message)

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 从文件 Uri 读取图片并识别（兼容相册/文件选择器的 content://）。 */
    suspend fun recognize(context: Context, uri: Uri): List<OcrLine> {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            throw OcrFailedException("无法读取图片：${e.message ?: "格式不支持"}")
        }
        return recognizeImage(image)
    }

    /** 直接识别已经解码/缩放好的位图（扫描 PDF 逐页渲染后使用，控制内存）。 */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (bitmap.isRecycled) throw OcrFailedException("图片已释放")
        return recognizeImage(InputImage.fromBitmap(bitmap, 0))
    }

    private suspend fun recognizeImage(image: InputImage): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { vision ->
                    val lines = mutableListOf<OcrLine>()
                    vision.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val box: Rect? = line.boundingBox
                            if (box != null && line.text.isNotBlank()) {
                                lines += OcrLine(line.text.trim(), box.left, box.top, box.right, box.bottom)
                            }
                        }
                    }
                    if (lines.isEmpty()) {
                        cont.resumeWithException(BlankImageException())
                    } else {
                        cont.resume(lines.sortedWith(compareBy({ it.top }, { it.left })))
                    }
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(OcrFailedException(e.message ?: "OCR 识别失败"))
                }
        }
}
