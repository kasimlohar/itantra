package com.itantra.data.audio

/**
 * High-performance deterministic Devanagari to Latin (Romanized/Hinglish) transliterator.
 * Used when the host device does not have an offline Hindi TTS voice installed, allowing
 * the device's offline English voice (e.g. en-IN or en-US) to audibly and accurately pronounce
 * Hindi messages without network connectivity.
 */
object DevanagariTransliterator {

    private val VOWELS = mapOf(
        '\u0905' to "a",   // अ
        '\u0906' to "aa",  // आ
        '\u0907' to "i",   // इ
        '\u0908' to "ee",  // ई
        '\u0909' to "u",   // उ
        '\u090A' to "oo",  // ऊ
        '\u090B' to "ri",  // ऋ
        '\u090E' to "e",   // ऎ
        '\u090F' to "e",   // ए
        '\u0910' to "ai",  // ऐ
        '\u0912' to "o",   // ऒ
        '\u0913' to "o",   // ओ
        '\u0914' to "au"   // औ
    )

    private val CONSONANTS = mapOf(
        '\u0915' to "k",   // क
        '\u0916' to "kh",  // ख
        '\u0917' to "g",   // ग
        '\u0918' to "gh",  // घ
        '\u0919' to "ng",  // ङ
        '\u091A' to "ch",  // च
        '\u091B' to "chh", // छ
        '\u091C' to "j",   // ज
        '\u091D' to "jh",  // झ
        '\u091E' to "ny",  // ञ
        '\u091F' to "t",   // ट
        '\u0920' to "th",  // ठ
        '\u0921' to "d",   // ड
        '\u0922' to "dh",  // ढ
        '\u0923' to "n",   // ण
        '\u0924' to "t",   // त
        '\u0925' to "th",  // थ
        '\u0926' to "d",   // द
        '\u0927' to "dh",  // ध
        '\u0928' to "n",   // न
        '\u0929' to "n",   // ऩ
        '\u092A' to "p",   // प
        '\u092B' to "ph",  // फ
        '\u092C' to "b",   // ब
        '\u092D' to "bh",  // भ
        '\u092E' to "m",   // म
        '\u092F' to "y",   // य
        '\u0930' to "r",   // र
        '\u0931' to "r",   // ऱ
        '\u0932' to "l",   // ल
        '\u0933' to "l",   // ळ
        '\u0934' to "zh",  // ऴ
        '\u0935' to "v",   // व
        '\u0936' to "sh",  // श
        '\u0937' to "sh",  // ष
        '\u0938' to "s",   // स
        '\u0939' to "h",   // ह
        // Precomposed nukta forms
        '\u0958' to "q",   // क़
        '\u0959' to "kh",  // ख़
        '\u095A' to "gh",  // ग़
        '\u095B' to "z",   // ज़
        '\u095C' to "r",   // ड़
        '\u095D' to "rh",  // ढ़
        '\u095E' to "f",   // फ़
        '\u095F' to "y"    // य़
    )

    private val MATRAS = mapOf(
        '\u093E' to "aa",  // ा
        '\u093F' to "i",   // ि
        '\u0940' to "ee",  // ी
        '\u0941' to "u",   // ु
        '\u0942' to "oo",  // ू
        '\u0943' to "ri",  // ृ
        '\u0944' to "ree", // ॄ
        '\u0946' to "e",   // ॆ
        '\u0947' to "e",   // े
        '\u0948' to "ai",  // ै
        '\u094A' to "o",   // ॊ
        '\u094B' to "o",   // ो
        '\u094C' to "au"   // ौ
    )

    private const val VIRAMA = '\u094D'    // ् (halant)
    private const val NUKTA = '\u093C'     // ़
    private const val ANUSVARA = '\u0902'  // ं
    private const val CANDRABINDU = '\u0901' // ँ
    private const val VISARGA = '\u0903'   // ः
    private const val DANDA = '\u0964'     // ।
    private const val DOUBLE_DANDA = '\u0965' // ॥

    /**
     * Returns true if the string contains any Devanagari characters.
     */
    fun containsDevanagari(text: String): Boolean {
        for (i in text.indices) {
            val c = text[i]
            if (c in '\u0900'..'\u097F') return true
        }
        return false
    }

    /**
     * Transliterates a Devanagari string into readable Romanized syllables.
     * Non-Devanagari text (Latin letters, numbers, punctuation) is preserved intact.
     */
    fun transliterate(text: String): String {
        if (text.isEmpty() || !containsDevanagari(text)) return text

        val sb = StringBuilder(text.length * 2)
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Independent Vowels
            val vowel = VOWELS[c]
            if (vowel != null) {
                sb.append(vowel)
                i++
                continue
            }

            // 2. Consonants
            var consonant = CONSONANTS[c]
            if (consonant != null) {
                var nextIndex = i + 1

                // Check for succeeding Nukta
                if (nextIndex < len && text[nextIndex] == NUKTA) {
                    consonant = when (c) {
                        '\u091C' -> "z"   // ज़
                        '\u092B' -> "f"   // फ़
                        '\u0915' -> "q"   // क़
                        '\u0916' -> "kh"  // ख़
                        '\u0917' -> "gh"  // ग़
                        '\u0921' -> "r"   // ड़
                        '\u0922' -> "rh"  // ढ़
                        else -> consonant
                    }
                    nextIndex++
                }

                sb.append(consonant)

                // Inspect what follows the consonant
                if (nextIndex < len) {
                    val next = text[nextIndex]
                    val matra = MATRAS[next]
                    if (matra != null) {
                        // Consonant has an explicit matra
                        sb.append(matra)
                        i = nextIndex + 1
                        continue
                    } else if (next == VIRAMA) {
                        // Consonant has a halant (suppress implicit 'a')
                        i = nextIndex + 1
                        continue
                    } else if (next == ANUSVARA || next == CANDRABINDU) {
                        // Anusvara on inherent vowel (e.g. क + ं = kan)
                        sb.append("an")
                        i = nextIndex + 1
                        continue
                    } else if (next == VISARGA) {
                        sb.append("ah")
                        i = nextIndex + 1
                        continue
                    }
                }

                // Schwa handling (inherent 'a')
                // If it's not the end of a word (followed by whitespace, punctuation, or end of string)
                if (nextIndex < len && isDevanagariConsonantOrVowel(text[nextIndex])) {
                    sb.append("a")
                } else if (len == 1) {
                    sb.append("a")
                }
                i = nextIndex
                continue
            }

            // 3. Isolated Matras (rare without consonant)
            val matra = MATRAS[c]
            if (matra != null) {
                sb.append(matra)
                i++
                continue
            }

            // 4. Modifiers
            when (c) {
                ANUSVARA, CANDRABINDU -> sb.append("n")
                VISARGA -> sb.append("h")
                DANDA, DOUBLE_DANDA -> sb.append(".")
                in '\u0966'..'\u096F' -> sb.append((c - '\u0966' + '0'.code).toChar())
                VIRAMA, NUKTA -> { /* Handled with consonant */ }
                else -> sb.append(c)
            }
            i++
        }

        return sb.toString()
    }

    private fun isDevanagariConsonantOrVowel(c: Char): Boolean {
        return (c in '\u0905'..'\u0914') || (c in '\u0915'..'\u0939') || (c in '\u0958'..'\u095F')
    }
}
