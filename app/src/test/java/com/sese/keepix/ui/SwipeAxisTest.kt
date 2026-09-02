package com.sese.keepix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeAxisTest {

    private val threshold = 100f

    @Test fun `right past threshold keeps`() =
        assertEquals(SwipeAction.KEEP, resolveSwipeAction(150f, 0f, threshold))

    @Test fun `left past threshold deletes`() =
        assertEquals(SwipeAction.DELETE, resolveSwipeAction(-150f, 0f, threshold))

    @Test fun `up past threshold favorites`() =
        assertEquals(SwipeAction.FAVORITE, resolveSwipeAction(0f, -150f, threshold))

    @Test fun `down does nothing`() =
        assertNull(resolveSwipeAction(0f, 150f, threshold))

    @Test fun `below threshold does nothing`() =
        assertNull(resolveSwipeAction(50f, -50f, threshold))

    // Horizontal wins ties: keeping is the common case, favoriting is deliberate,
    // so an ambiguous diagonal must not silently star something.
    @Test fun `equal diagonal resolves horizontally`() =
        assertEquals(SwipeAction.KEEP, resolveSwipeAction(150f, -150f, threshold))

    @Test fun `mostly-vertical diagonal favorites`() =
        assertEquals(SwipeAction.FAVORITE, resolveSwipeAction(40f, -150f, threshold))
}
