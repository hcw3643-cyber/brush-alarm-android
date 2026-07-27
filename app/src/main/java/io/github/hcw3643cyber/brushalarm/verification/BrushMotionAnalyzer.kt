package io.github.hcw3643cyber.brushalarm.verification

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.floor

/**
 * Timestamp-samples the camera on the analyzer thread and runs one ONNX
 * inference at a time on a separate worker. Frames are never saved or uploaded.
 */
class BrushMotionAnalyzer(
    context: Context,
    private val onProgress: (Float, String) -> Unit,
    private val onVerified: () -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val frames = ArrayDeque<SampledFrame>(FRAME_COUNT)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executorLock = Any()
    private val decisionFilter = BrushDecisionFilter()
    private val inferenceLog = InferenceLogWriter(context)
    private var nextSampleTimestampNs = Long.MIN_VALUE
    private var lastSampleTimestampNs = Long.MIN_VALUE
    private var lastInferenceTimestampNs = Long.MIN_VALUE
    private var score = 0f

    @Volatile
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
            if (verified || closed.get() || !shouldSample(proxy.imageInfo.timestamp)) return
            if (frames.size == FRAME_COUNT) frames.removeFirst()
            frames.addLast(SampledFrame(proxy.imageInfo.timestamp, toModelFrame(proxy)))
            if (frames.size < FRAME_COUNT) {
                onProgress(0f, "正在收集约 2 秒动作画面…")
                return
            }

            val timestampNs = proxy.imageInfo.timestamp
            val inferenceDue = lastInferenceTimestampNs == Long.MIN_VALUE ||
                timestampNs - lastInferenceTimestampNs >= INFERENCE_INTERVAL_NS
            if (!inferenceDue || !inferenceRunning.compareAndSet(false, true)) return

            lastInferenceTimestampNs = timestampNs
            val snapshot = frames.toList()
            synchronized(executorLock) {
                if (closed.get()) {
                    inferenceRunning.set(false)
                    return
                }
                inferenceExecutor.execute {
                    try {
                        if (!verified && !closed.get()) processInference(snapshot)
                    } catch (_: Exception) {
                        if (!closed.get()) {
                            onProgress(score, "模型暂时无法识别，请调整距离和光线")
                        }
                    } finally {
                        inferenceRunning.set(false)
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) {
                onProgress(score, "相机画面处理失败，请调整距离和光线")
            }
        } finally {
            proxy.close()
        }
    }

    private fun shouldSample(timestampNs: Long): Boolean {
        if (lastSampleTimestampNs != Long.MIN_VALUE &&
            (timestampNs <= lastSampleTimestampNs ||
                timestampNs - lastSampleTimestampNs > MAX_SAMPLE_GAP_NS)
        ) {
            // Never mix frames from before/after a camera pause into one clip.
            frames.clear()
            nextSampleTimestampNs = timestampNs
        }
        if (nextSampleTimestampNs == Long.MIN_VALUE) {
            nextSampleTimestampNs = timestampNs
        }
        if (timestampNs < nextSampleTimestampNs) return false
        do {
            nextSampleTimestampNs += SAMPLE_INTERVAL_NS
        } while (nextSampleTimestampNs <= timestampNs)
        lastSampleTimestampNs = timestampNs
        return true
    }

    private fun processInference(snapshot: List<SampledFrame>) {
        val startedAt = System.nanoTime()
        val result = infer(snapshot)
        val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val probability = result.confidence
        val decision = decisionFilter.update(probability, SystemClock.elapsedRealtime())
        score = decision.progress
        val message = when {
            decision.accumulating -> "识别到刷牙，请继续保持动作"
            decision.confidence > BrushDecisionFilter.LOW_THRESHOLD ->
                "动作不够明确，进度暂时保持"
            else -> "暂未识别到刷牙动作"
        }
        val spanNs = snapshot.last().timestampNs - snapshot.first().timestampNs
        val spanMs = spanNs / 1_000_000.0
        val sampleFps = if (spanNs > 0) {
            (snapshot.size - 1) * 1_000_000_000.0 / spanNs
        } else {
            0.0
        }
        inferenceLog.record(
            windowSpanMs = spanMs,
            sampleFps = sampleFps,
            inferenceMs = inferenceMs,
            logit = result.logit,
            confidence = result.confidence,
            decision = when {
                decision.accumulating -> "positive"
                decision.confidence > BrushDecisionFilter.LOW_THRESHOLD -> "held"
                else -> "negative"
            },
            progress = decision.progress
        )
        if (!closed.get()) onProgress(score, message)
        if (decision.passed && !verified && !closed.get()) {
            verified = true
            onVerified()
        }
    }

    private fun infer(snapshot: List<SampledFrame>): InferenceOutput {
        val input = FloatArray(FRAME_COUNT * CHANNELS * SIZE * SIZE)
        snapshot.forEachIndexed { index, frame ->
            frame.data.copyInto(input, index * frame.data.size)
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
                val confidence = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
                return InferenceOutput(logit, confidence)
            }
        }
    }

    /** Center-crops the upright image and writes bilinear-resized CHW RGB. */
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
                val rotatedX = offsetX + (x + .5f) * crop / SIZE - .5f
                val rotatedY = offsetY + (y + .5f) * crop / SIZE - .5f
                val (sourceX, sourceY) = rotatedToSource(
                    rotatedX, rotatedY, rotation, sourceWidth, sourceHeight
                )
                val pixel = y * SIZE + x
                writeBilinearRgb(proxy, sourceX, sourceY, output, pixel)
            }
        }
        return output
    }

    private fun rotatedToSource(
        x: Float,
        y: Float,
        rotation: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Pair<Float, Float> = when (rotation) {
        90 -> y to (sourceHeight - 1f - x)
        180 -> (sourceWidth - 1f - x) to (sourceHeight - 1f - y)
        270 -> (sourceWidth - 1f - y) to x
        else -> x to y
    }

    private fun writeBilinearRgb(
        proxy: ImageProxy,
        rawX: Float,
        rawY: Float,
        output: FloatArray,
        pixel: Int
    ) {
        val floorX = floor(rawX)
        val floorY = floor(rawY)
        val x0 = floorX.toInt().coerceIn(0, proxy.width - 1)
        val y0 = floorY.toInt().coerceIn(0, proxy.height - 1)
        val x1 = (x0 + 1).coerceAtMost(proxy.width - 1)
        val y1 = (y0 + 1).coerceAtMost(proxy.height - 1)
        val fx = (rawX - floorX).coerceIn(0f, 1f)
        val fy = (rawY - floorY).coerceIn(0f, 1f)
        val topLeft = yuvToRgb(proxy, x0, y0)
        val topRight = yuvToRgb(proxy, x1, y0)
        val bottomLeft = yuvToRgb(proxy, x0, y1)
        val bottomRight = yuvToRgb(proxy, x1, y1)
        for (channel in 0 until CHANNELS) {
            val shift = (2 - channel) * 8
            val top = ((topLeft shr shift) and 0xff) * (1f - fx) +
                ((topRight shr shift) and 0xff) * fx
            val bottom = ((bottomLeft shr shift) and 0xff) * (1f - fx) +
                ((bottomRight shr shift) and 0xff) * fx
            val value = (top * (1f - fy) + bottom * fy) / 255f
            output[channel * SIZE * SIZE + pixel] = (value - MEAN[channel]) / STD[channel]
        }
    }

    private fun yuvToRgb(proxy: ImageProxy, x: Int, y: Int): Int {
        fun sample(planeIndex: Int, px: Int, py: Int): Int {
            val plane = proxy.planes[planeIndex]
            val index = plane.buffer.position() +
                py * plane.rowStride + px * plane.pixelStride
            return plane.buffer.get(index.coerceAtMost(plane.buffer.limit() - 1))
                .toInt() and 0xff
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
        if (!closed.compareAndSet(false, true)) return
        synchronized(executorLock) {
            // Queue cleanup after any active native inference instead of
            // closing the ONNX session from the UI thread while it is in use.
            inferenceExecutor.execute {
                inferenceLog.close()
                session.close()
            }
            inferenceExecutor.shutdown()
        }
    }

    fun markGroundTruth(brushing: Boolean) {
        inferenceLog.markGroundTruth(brushing)
    }

    companion object {
        private const val MODEL_ASSET = "brush_classifier.onnx"
        private const val FRAME_COUNT = 16
        private const val CHANNELS = 3
        private const val SIZE = 192
        private const val SAMPLE_INTERVAL_NS = 125_000_000L
        private const val INFERENCE_INTERVAL_NS = 500_000_000L
        private const val MAX_SAMPLE_GAP_NS = 500_000_000L
        private val MEAN = floatArrayOf(.43216f, .394666f, .37645f)
        private val STD = floatArrayOf(.22803f, .22145f, .216989f)
    }

    private data class SampledFrame(val timestampNs: Long, val data: FloatArray)
    private data class InferenceOutput(val logit: Float, val confidence: Float)
}
