package com.castpro.tv.render

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.AnnexBCodecConfig
import com.castpro.core.streaming.decoder.VideoDecodeGate
import com.castpro.core.streaming.decoder.VideoDecoderController
import com.castpro.tv.viewmodel.TvDisplayMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RendererSurfaceLifecycleTest {
    private val config = StreamConfig(width = 1920, height = 1080, codecMime = "video/avc")
    private val csd = byteArrayOf(
        0, 0, 0, 1, 0x67, 0x42, 0, 0x1E,
        0, 0, 1, 0x68, 0xCE.toByte(), 0x06
    )
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x01)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11)

    @Test fun textureSurfaceDestroyRecreateResubmitsCachedCsdAndRequiresFreshIdr() {
        val context = RuntimeEnvironment.getApplication() as Context
        val view = TextureView(context)
        val created = mutableListOf<FakeRendererDecoder>()
        val renderer = TvTextureRenderer(
            textureView = view,
            initialConfig = config,
            initialDisplayMode = TvDisplayMode.STRETCH,
            onMetricsUpdated = {},
            decoderFactory = { _, _, initialCsd ->
                FakeRendererDecoder(initialCsd).also { created += it }
            }
        )

        val firstTexture = SurfaceTexture(0)
        renderer.onSurfaceTextureAvailable(firstTexture, 1920, 1080)
        renderer.onFrame(csd, 0, csd.size, 1L, true, true, false)
        assertTrue(created.single().gate.hasCodecConfig())

        assertTrue(renderer.onSurfaceTextureDestroyed(firstTexture))
        firstTexture.release()

        val secondTexture = SurfaceTexture(0)
        renderer.onSurfaceTextureAvailable(secondTexture, 1920, 1080)
        val recreated = created.last()
        assertEquals(2, created.size)
        assertNotNull(recreated.initialCsd)
        assertFalse(recreated.acceptedPFrames > 0)
        renderer.onFrame(pFrame, 0, pFrame.size, 2L, true, false, false)
        assertEquals(0, recreated.acceptedPFrames)
        renderer.onFrame(idr, 0, idr.size, 3L, true, false, true)
        renderer.onFrame(pFrame, 0, pFrame.size, 4L, true, false, false)
        assertEquals(1, recreated.acceptedPFrames)

        renderer.release()
        secondTexture.release()
    }

    @Test fun surfaceViewDestroyRecreateResubmitsCachedCsdAndRequiresFreshIdr() {
        val context = RuntimeEnvironment.getApplication() as Context
        val view = SurfaceView(context)
        val created = mutableListOf<FakeRendererDecoder>()
        val renderer = TvSurfaceRenderer(
            surfaceView = view,
            initialConfig = config,
            initialDisplayMode = TvDisplayMode.STRETCH,
            onMetricsUpdated = {},
            decoderFactory = { _, _, initialCsd ->
                FakeRendererDecoder(initialCsd).also { created += it }
            }
        )

        val firstTexture = SurfaceTexture(0)
        val firstSurface = Surface(firstTexture)
        val firstHolder = holderProxy(firstSurface)
        renderer.surfaceCreated(firstHolder)
        renderer.onFrame(csd, 0, csd.size, 1L, true, true, false)
        renderer.surfaceDestroyed(firstHolder)

        val secondTexture = SurfaceTexture(0)
        val secondSurface = Surface(secondTexture)
        val secondHolder = holderProxy(secondSurface)
        renderer.surfaceCreated(secondHolder)
        val recreated = created.last()
        assertEquals(2, created.size)
        assertNotNull(recreated.initialCsd)
        renderer.onFrame(pFrame, 0, pFrame.size, 2L, true, false, false)
        assertEquals(0, recreated.acceptedPFrames)
        renderer.onFrame(idr, 0, idr.size, 3L, true, false, true)
        renderer.onFrame(pFrame, 0, pFrame.size, 4L, true, false, false)
        assertEquals(1, recreated.acceptedPFrames)

        renderer.surfaceDestroyed(secondHolder)
        renderer.release()
        firstSurface.release()
        secondSurface.release()
        firstTexture.release()
        secondTexture.release()
    }

    private fun holderProxy(surface: Surface): SurfaceHolder =
        Proxy.newProxyInstance(
            SurfaceHolder::class.java.classLoader,
            arrayOf(SurfaceHolder::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getSurface" -> surface
                "getSurfaceFrame" -> Rect(0, 0, 1920, 1080)
                "toString" -> "TestSurfaceHolder"
                "hashCode" -> System.identityHashCode(surface)
                "equals" -> false
                else -> null
            }
        } as SurfaceHolder

    private class FakeRendererDecoder(csd: ByteArray?) : VideoDecoderController {
        val gate = VideoDecodeGate()
        val initialCsd: ByteArray? = csd?.copyOf()
        var acceptedPFrames = 0
        init {
            if (csd != null && AnnexBCodecConfig.extractParameterSets("video/avc", csd) != null) {
                gate.markCodecConfigAvailable()
            }
        }
        override fun start() = Unit
        override fun stop() = Unit
        override fun reset() = gate.resetForFlush()
        override fun resetSession() = gate.resetForSession()
        override fun reconfigure(newConfig: StreamConfig) = gate.resetForReconfigure()
        override fun setVideoScalingMode(mode: Int) = Unit
        override fun decodeFrame(
            data: ByteArray, offset: Int, length: Int, timestampNano: Long,
            isGamingMode: Boolean, isCodecConfig: Boolean, isKeyframe: Boolean
        ) {
            if (isCodecConfig) {
                if (gate.extractInlineParameterSets("video/avc", data, offset, length) != null) {
                    gate.markCodecConfigAvailable()
                }
                return
            }
            if (gate.canQueueFrame(isKeyframe)) {
                if (isKeyframe) gate.markKeyframeQueued() else acceptedPFrames++
            }
        }
    }
}
