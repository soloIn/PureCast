package com.solo.purecast

import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.ui.components.formatSampleRate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💡 SignalPathPill 采样率格式化与音频链路展示单测
 */
class SignalPathPillTest {

    @Test
    fun `formatSampleRate formats standard integer and fractional sample rates correctly`() {
        assertEquals("44.1kHz", formatSampleRate(44100))
        assertEquals("48kHz", formatSampleRate(48000))
        assertEquals("88.2kHz", formatSampleRate(88200))
        assertEquals("96kHz", formatSampleRate(96000))
        assertEquals("176.4kHz", formatSampleRate(176400))
        assertEquals("192kHz", formatSampleRate(192000))
        assertEquals("352.8kHz", formatSampleRate(352800))
        assertEquals("384kHz", formatSampleRate(384000))
        assertEquals("0kHz", formatSampleRate(0))
    }

    @Test
    fun `AudioPathState default codec is PCM and can carry FLAC and WAV`() {
        val defaultState = AudioPathState()
        assertEquals("PCM", defaultState.codec)

        val flacState = defaultState.copy(
            protocol = StreamProtocol.UPNP_DLNA,
            codec = "FLAC",
            sampleRate = 96000,
            bitDepth = 24
        )
        assertEquals("FLAC", flacState.codec)
        assertEquals(96000, flacState.sampleRate)
        assertEquals(24, flacState.bitDepth)

        val wavState = defaultState.copy(
            protocol = StreamProtocol.UPNP_DLNA,
            codec = "WAV",
            sampleRate = 44100,
            bitDepth = 16
        )
        assertEquals("WAV", wavState.codec)
    }
}
