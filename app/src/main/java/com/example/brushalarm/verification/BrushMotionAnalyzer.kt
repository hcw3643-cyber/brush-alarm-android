package com.example.brushalarm.verification

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Runs the trained temporal brush classifier completely on-device.
 * No camera frame is saved or uploaded.
 */
class BrushMotionAnalyzer(
    context: Context,
    private val onProgress: (Float, String) -> Unit,
    private val onVerified: () -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val frames = ArrayDeque<FloatArray>(FRAME_COUNT)
    private var frameNumber = 0
    private var framesSinceInference = 0
    private val decisionFilter = BrushDecisionFilter()
    private var score = 0f
    private var verified = false

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(model, options)
        inputName = session.inputNames.first()
    }

    override fun analyze(proxy: ImageProxy) {
        try {
            if (verified || frameNumber++ % CAPTURE_EVERY_N_FRAMES != 0) return
            if (frames.size == FRAME_COUNT) frames.removeFirst()
            frames.addLast(toModelFrame(proxy))
            framesSinceInference++
            if (frames.size < FRAME_COUNT) {
                onProgress(0f, "请将上半身和牙刷保持在画面中")
                return
            }
            if (framesSinceInference < INFER_EVERY_N_CAPTURED) return
            framesSinceInference = 0
            val probability = infer()
            val decision = decisionFilter.update(probability, SystemClock.elapsedRealtime())
            score = decision.progress
            val message = when {
                decision.accumulating -> "识别到刷牙，请继续保持动作"
                decision.brushing -> "动作暂时中断，恢复刷牙后继续累计"
                decision.filteredConfidence >= 0.30f ->
                    "正在确认动作，请继续保持牙刷和手臂可见"
                else -> "暂未识别到刷牙动作"
            }
            onProgress(score, message)
            if (decision.passed && !verified) {
                verified = true
                onVerified()
            }
        } catch (error: Exception) {
            onProgress(score, "模型暂时无法识别，请调整距离和光线")
        } finally {
            proxy.close()
        }
    }

    private fun infer(): Float {
        val input = FloatArray(FRAME_COUNT * CHANNELS * SIZE * SIZE)
        frames.forEachIndexed { index, frame ->
            frame.copyInto(input, index * frame.size)
        }
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, FRAME_COUNT.toLong(), CHANNELS.toLong(), SIZE.toLong(), SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { output ->
                val value = output[0].value
                val logit = when (value) {
                    is FloatArray -> value[0]
                    is Array<*> -> (value[0] as FloatArray)[0]
                    else -> error("Unexpected model output: ${value.javaClass}")
                }
                return (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
            }
        }
    }

    /** Center-crops the upright image and writes Kinetics/S3D-normalized CHW RGB. */
    private fun toModelFrame(proxy: ImageProxy): FloatArray {
        val rotation = proxy.imageInfo.rotationDegrees
        val sourceWidth = proxy.width
        val sourceHeight = proxy.height
        val rotatedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val rotatedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val crop = minOf(rotatedWidth, rotatedHeight).toFloat()
        val offsetX = (rotatedWidth - crop) / 2f
        val offsetY = (rotatedHeight - crop) / 2f
        val output = FloatArray(CHANNELS * SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val rotatedX = offsetX + (x + .5f) * crop / SIZE
                val rotatedY = offsetY + (y + .5f) * crop / SIZE
                val (sourceX, sourceY) = when (rotation) {
                    90 -> rotatedY to (sourceHeight - 1f - rotatedX)
                    180 -> (sourceWidth - 1f - rotatedX) to (sourceHeight - 1f - rotatedY)
                    270 -> (sourceWidth - 1f - rotatedY) to rotatedX
                    else -> rotatedX to rotatedY
                }
                val rgb = yuvToRgb(proxy, sourceX.toInt(), sourceY.toInt())
                val pixel = y * SIZE + x
                output[pixel] = ((rgb shr 16 and 0xff) / 255f - MEAN[0]) / STD[0]
                output[SIZE * SIZE + pixel] = ((rgb shr 8 and 0xff) / 255f - MEAN[1]) / STD[1]
                output[2 * SIZE * SIZE + pixel] = ((rgb and 0xff) / 255f - MEAN[2]) / STD[2]
            }
        }
        return output
    }

    private fun yuvToRgb(proxy: ImageProxy, rawX: Int, rawY: Int): Int {
        val x = rawX.coerceIn(0, proxy.width - 1)
        val y = rawY.coerceIn(0, proxy.height - 1)
        fun sample(planeIndex: Int, px: Int, py: Int): Int {
            val plane = proxy.planes[planeIndex]
            // Plane buffers may start at a non-zero position (especially the
            // interleaved U/V planes). Absolute get(0) is not always pixel 0.
            val index = plane.buffer.position() +
                py * plane.rowStride + px * plane.pixelStride
            return plane.buffer.get(index.coerceAtMost(plane.buffer.limit() - 1)).toInt() and 0xff
        }
        val yy = sample(0, x, y)
        val u = sample(1, x / 2, y / 2) - 128
        val v = sample(2, x / 2, y / 2) - 128
        val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (yy - .344136f * u - .714136f * v).toInt().coerceIn(0, 255)
        val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val MODEL_ASSET = "brush_classifier.onnx"
        private const val FRAME_COUNT = 16
        private const val CHANNELS = 3
        private const val SIZE = 160
        private const val CAPTURE_EVERY_N_FRAMES = 4
        // Nominally 30 / 4 / 3 = 2.5 decisions per second before inference cost.
        private const val INFER_EVERY_N_CAPTURED = 3
        private val MEAN = floatArrayOf(.43216f, .394666f, .37645f)
        private val STD = floatArrayOf(.22803f, .22145f, .216989f)
    }
}
