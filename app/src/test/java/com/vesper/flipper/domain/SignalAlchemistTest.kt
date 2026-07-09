package com.vesper.flipper.domain

import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SignalAlchemistTest {

    @Test
    fun `export includes valid SubGHz RAW headers and data`() {
        val content = SignalAlchemist.exportToFlipperFormat(sampleProject())

        assertTrue(content.contains("Filetype: Flipper SubGhz RAW File"))
        assertTrue(content.contains("Frequency: 433920000"))
        assertTrue(content.contains("Preset: FuriHalSubGhzPresetOok650Async"))
        assertTrue(content.contains("Protocol: RAW"))
        assertTrue(content.contains("RAW_Data:"))
    }

    @Test
    fun `import raw subghz reads frequency and data`() {
        val content = """
            Filetype: Flipper SubGhz RAW File
            Version: 1
            Frequency: 315000000
            Preset: FuriHalSubGhzPresetOok270Async
            Protocol: RAW
            RAW_Data: 500 -500 1000 -500 500 -1000
        """.trimIndent()

        val project = SignalAlchemist.importSubGhzRaw(content, "Imported")

        assertNotNull(project)
        assertEquals("Imported", project!!.name)
        assertEquals(315_000_000, project.frequency)
        assertEquals(ModulationType.OOK_270, project.modulation)
        assertEquals(1, project.layers.size)
        assertEquals(listOf(true, false, true, false, true, false), project.layers.first().pattern.bits)
        assertEquals(
            listOf(500, -500, 1000, -500, 500, -1000),
            SignalAlchemist.synthesize(project)
        )
    }

    @Test
    fun `import rejects non raw or malformed content`() {
        assertNull(SignalAlchemist.importSubGhzRaw("Protocol: Princeton\nKey: 00 00"))
        assertNull(SignalAlchemist.importSubGhzRaw("Protocol: RAW\nRAW_Data: 500 -500"))
    }

    @Test
    fun `synthesis never emits zero timings when volume is zero`() {
        val project = sampleProject().copy(
            layers = sampleProject().layers.map { it.copy(volume = 0f) }
        )

        assertTrue(SignalAlchemist.synthesize(project).all { it != 0 })
    }

    private fun sampleProject() = AlchemyProject(
        name = "Test Signal",
        frequency = 433_920_000,
        modulation = ModulationType.OOK_650,
        preset = SignalPreset.CAR_KEY_433,
        layers = listOf(
            SignalLayer(
                name = "Data",
                type = LayerType.DATA,
                pattern = BitPattern(listOf(true, false, true, false), bitDuration = 400),
                timing = TimingConfig(),
                color = 0xFFFF6B00
            )
        )
    )
}
