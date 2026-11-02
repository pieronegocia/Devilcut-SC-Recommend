package com.devilcut.pinsaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinterestLinksTest {

    @Test
    fun shortLinkInsideSharedText() {
        assertEquals(
            PinLink.Short("https://pin.it/1a2B3c4D"),
            PinterestLinks.parse("¡Mira este Pin en Pinterest! https://pin.it/1a2B3c4D"),
        )
    }

    @Test
    fun shortLinkWithTrailingPunctuation() {
        assertEquals(PinLink.Short("https://pin.it/abc123"), PinterestLinks.parse("(https://pin.it/abc123)."))
    }

    @Test
    fun fullPinLinks() {
        assertEquals(PinLink.Pin("123456789012345678"), PinterestLinks.parse("https://www.pinterest.com/pin/123456789012345678/"))
        assertEquals(PinLink.Pin("98765432"), PinterestLinks.parse("https://es.pinterest.com/pin/98765432/?mt=login"))
        assertEquals(PinLink.Pin("98765432"), PinterestLinks.parse("https://www.pinterest.com.mx/pin/98765432/sent/?invite_code=x"))
        assertEquals(PinLink.Pin("98765432"), PinterestLinks.parse("https://pinterest.co.uk/pin/receta-facil-de-tacos--98765432/"))
        assertEquals(PinLink.Pin("AVxB3ZbcGhP1kQ2Zf8-yzT0"), PinterestLinks.parse("https://www.pinterest.com/pin/AVxB3ZbcGhP1kQ2Zf8-yzT0/"))
    }

    @Test
    fun linksWithoutScheme() {
        assertEquals(PinLink.Pin("123456"), PinterestLinks.parse("pinterest.com/pin/123456/"))
        assertEquals(PinLink.Short("https://pin.it/xyz987"), PinterestLinks.parse("pin.it/xyz987"))
    }

    @Test
    fun bareId() {
        assertEquals(PinLink.Pin("123456789012"), PinterestLinks.parse("  123456789012 "))
    }

    @Test
    fun shortenerApiLink() {
        assertEquals(
            PinLink.Short("https://api.pinterest.com/url_shortener/3xYz/redirect/"),
            PinterestLinks.parse("https://api.pinterest.com/url_shortener/3xYz/redirect/"),
        )
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(PinterestLinks.parse("https://example.com/pin/123456"))
        assertNull(PinterestLinks.parse("https://www.pinterest.com/pin/create/button/"))
        assertNull(PinterestLinks.parse("https://www.pinterest.com/someuser/recetas/"))
        assertNull(PinterestLinks.parse("hola"))
        assertNull(PinterestLinks.parse(""))
    }
}
