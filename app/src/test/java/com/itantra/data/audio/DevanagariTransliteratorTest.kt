package com.itantra.data.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevanagariTransliteratorTest {

    @Test
    fun containsDevanagari_detectsScriptCorrectly() {
        assertThat(DevanagariTransliterator.containsDevanagari("नमस्ते")).isTrue()
        assertThat(DevanagariTransliterator.containsDevanagari("hello नमस्ते")).isTrue()
        assertThat(DevanagariTransliterator.containsDevanagari("SOS Emergency")).isFalse()
        assertThat(DevanagariTransliterator.containsDevanagari("12345!@#$")).isFalse()
    }

    @Test
    fun transliterate_preservesLatinText() {
        val input = "SOS Emergency at Sector 4"
        assertThat(DevanagariTransliterator.transliterate(input)).isEqualTo(input)
    }

    @Test
    fun transliterate_convertsHelloAndNamaste() {
        val hello = DevanagariTransliterator.transliterate("हैलो")
        assertThat(hello).contains("hailo")

        val namaste = DevanagariTransliterator.transliterate("नमस्ते")
        assertThat(namaste).contains("namaste")
    }

    @Test
    fun transliterate_convertsFullMeshPhrases() {
        val res1 = DevanagariTransliterator.transliterate("हैलो आवाज आ रही है")
        assertThat(res1.lowercase()).contains("hailo")
        assertThat(res1.lowercase()).contains("aa")
        assertThat(res1.lowercase()).contains("hai")

        val res2 = DevanagariTransliterator.transliterate("सब ठीक है ना")
        assertThat(res2.lowercase()).contains("theek")
        assertThat(res2.lowercase()).contains("hai")
    }

    @Test
    fun transliterate_convertsDevanagariNumerals() {
        val res = DevanagariTransliterator.transliterate("वार्ड नंबर ४२")
        assertThat(res).contains("42")
    }
}
