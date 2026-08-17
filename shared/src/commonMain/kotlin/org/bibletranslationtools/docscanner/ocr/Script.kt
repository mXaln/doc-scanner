package org.bibletranslationtools.docscanner.ocr

import org.bibletranslationtools.docscanner.data.models.Language

/**
 * Writing systems, as far as on-device recognition cares about them.
 *
 * Recognition models are trained per writing system, not per language, so this is the key
 * that selects a model. A language's script is derived from `langnames.json`, which already
 * carries each language's name written in its own script — no separate mapping table to
 * maintain, and it follows along when the language list is refreshed.
 */
enum class Script(
    /** Name to show a user, who should not have to read enum constants. */
    val label: String
) {
    LATIN("Latin"),
    CYRILLIC("Cyrillic"),
    ARABIC("Arabic"),
    DEVANAGARI("Devanagari"),
    CJK("Chinese or Japanese"),
    HANGUL("Korean"),
    GREEK("Greek"),
    HEBREW("Hebrew"),
    THAI("Thai"),
    /** A script with no on-device model; the feature stays hidden for these. */
    OTHER("an unrecognized script");

    /**
     * Whether [text] is actually written in this script.
     *
     * A recognizer fed the wrong script produces confident transliteration rather than noise,
     * so checking its output is what turns that into a reportable failure.
     */
    fun matches(text: String, minShare: Double = MIN_SCRIPT_SHARE): Boolean =
        shareOf(text) >= minShare

    /**
     * The fraction of [text]'s letters that belong to this script, or 0 when it has none.
     *
     * Reported to the user when a page is refused: a share says how far off the reading was,
     * where naming the dominant script cannot — a page can be refused while this script is
     * still the most common one in it.
     */
    fun shareOf(text: String): Double {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return 0.0
        val mine = text.count { it.isLetter() && scriptOf(it) == this }
        return mine.toDouble() / letters
    }

    private companion object {
        /** Below this share of same-script letters the result is treated as wrong-script. */
        const val MIN_SCRIPT_SHARE = 0.5
    }
}

/** The script of a single character, or `null` when it belongs to none we track. */
private fun scriptOf(char: Char): Script? {
    val code = char.code
    return when (code) {
        in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F, in 0x1E00..0x1EFF -> Script.LATIN
        in 0x0400..0x052F, in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> Script.CYRILLIC
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> Script.ARABIC
        in 0x0900..0x097F -> Script.DEVANAGARI
        in 0x0370..0x03FF, in 0x1F00..0x1FFF -> Script.GREEK
        in 0x0590..0x05FF -> Script.HEBREW
        in 0x0E00..0x0E7F -> Script.THAI
        in 0x1100..0x11FF, in 0x3130..0x318F, in 0xAC00..0xD7AF -> Script.HANGUL
        // Han plus kana: one recognizer covers them, so they are one bucket here.
        in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF -> Script.CJK
        else -> null
    }
}

/**
 * The dominant script of [text].
 *
 * A non-Latin script wins whenever it appears at all, because language names are often padded
 * with a Latin romanization (`"中文 - Zhōngwén"`) long enough to outnumber the characters that
 * identify the real script.
 */
fun scriptOf(text: String): Script {
    val counts = mutableMapOf<Script, Int>()
    for (char in text) {
        if (!char.isLetter()) continue
        val script = scriptOf(char) ?: continue
        counts[script] = (counts[script] ?: 0) + 1
    }
    if (counts.isEmpty()) return Script.OTHER
    val nonLatin = counts.filterKeys { it != Script.LATIN }
    val winner = (nonLatin.ifEmpty { counts }).maxByOrNull { it.value }
    return winner?.key ?: Script.OTHER
}

/**
 * The script this language is written in, taken from its endonym in `langnames.json`
 * (`ln`, exposed as [Language.name]).
 *
 * An untracked script stays [Script.OTHER] rather than falling back to Latin, since declining
 * to transcribe beats transliterating the page into the wrong alphabet.
 */
fun Language.script(): Script = scriptOf(name)
