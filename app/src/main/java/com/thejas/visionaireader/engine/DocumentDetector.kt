package com.thejas.visionaireader.engine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class DocStatus {
    NO_DOC, MOVE_AWAY, MOVE_CLOSER,
    SHOW_TOP_LEFT, SHOW_TOP_RIGHT, SHOW_BOTTOM_LEFT, SHOW_BOTTOM_RIGHT,
    PERFECT
}

class DocumentDetector(context: Context) {

    private val interpreter: Interpreter

    init {
        val afd = context.assets.openFd("document_detection_0.1.tflite")
        val channel = FileInputStream(afd.fileDescriptor).channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        interpreter = Interpreter(buffer)
        channel.close()
    }

    fun analyze(bitmap: Bitmap): DocStatus {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val input = preprocessBitmap(resized)
        val output = ByteBuffer.allocateDirect(1 * 224 * 224 * 4).order(ByteOrder.nativeOrder())

        synchronized(interpreter) { interpreter.run(input, output) }

        output.rewind()
        val prediction = Array(224) { FloatArray(224) }
        for (i in 0 until 224) for (j in 0 until 224) {
            prediction[i][j] = 1f - output.float
        }

        return evaluate(prediction)
    }

    private fun evaluate(pred: Array<FloatArray>): DocStatus {
        var mean = 0f
        for (row in pred) for (v in row) mean += v
        mean /= (224 * 224)

        return when {
            mean >= 0.95f -> DocStatus.NO_DOC
            mean <= 0.15f -> DocStatus.MOVE_AWAY
            mean > 0.85f  -> DocStatus.MOVE_CLOSER
            else -> {
                val sz = 56
                val tl = regionMean(pred, 0,       0,       sz, sz)
                val tr = regionMean(pred, 0,       224-sz,  sz, sz)
                val bl = regionMean(pred, 224-sz,  0,       sz, sz)
                val br = regionMean(pred, 224-sz,  224-sz,  sz, sz)
                val thresh = 0.85f
                when {
                    tl < thresh -> DocStatus.SHOW_TOP_LEFT
                    tr < thresh -> DocStatus.SHOW_TOP_RIGHT
                    bl < thresh -> DocStatus.SHOW_BOTTOM_LEFT
                    br < thresh -> DocStatus.SHOW_BOTTOM_RIGHT
                    else        -> DocStatus.PERFECT
                }
            }
        }
    }

    private fun regionMean(pred: Array<FloatArray>, startY: Int, startX: Int, h: Int, w: Int): Float {
        var sum = 0f
        for (y in startY until startY + h) for (x in startX until startX + w) sum += pred[y][x]
        return sum / (h * w)
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(224 * 224)
        bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255f)
            buf.putFloat(((px shr 8)  and 0xFF) / 255f)
            buf.putFloat((px          and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    fun close() = interpreter.close()
}
