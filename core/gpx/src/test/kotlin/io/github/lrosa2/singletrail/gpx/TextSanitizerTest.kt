package io.github.lrosa2.singletrail.gpx

import io.github.lrosa2.singletrail.gpx.TextSanitizer.plainText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextSanitizerTest {

    @Test
    fun `null and blank give null`() {
        assertNull(plainText(null))
        assertNull(plainText("  \n\t "))
    }

    @Test
    fun `paragraphs become lines and inline tags disappear`() {
        assertEquals("Flowy singletrack\nWatch out", plainText("<p>Flowy <b>singletrack</b></p><p>Watch out</p>"))
    }

    @Test
    fun `br tags become line breaks`() {
        assertEquals("one\ntwo", plainText("one<br>two"))
    }

    @Test
    fun `script and style blocks are removed with their content`() {
        assertEquals("Nice ride", plainText("Nice <script>alert(1)</script>ride<style>p{color:red}</style>"))
    }

    @Test
    fun `HTML entities are decoded`() {
        assertEquals("Fish & chips été", plainText("Fish &amp; chips&nbsp;&#233;t&#xE9;"))
    }

    @Test
    fun `angle brackets in normal text are kept`() {
        assertEquals("Climb 5 < 10 km > fun", plainText("Climb 5 < 10 km > fun"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("abc", plainText("a\u0000b\u0007c"))
    }

    @Test
    fun `runs of whitespace collapse and blank lines are limited to one`() {
        assertEquals("many spaces\n\nnext", plainText("  many   spaces \n\n\n\n next "))
    }

    @Test
    fun `long text is cut at the limit with an ellipsis`() {
        val result = plainText("x".repeat(300), maxLength = 100)!!
        assertEquals(100, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `text exactly at the limit is not cut`() {
        assertEquals("x".repeat(100), plainText("x".repeat(100), maxLength = 100))
    }
}
