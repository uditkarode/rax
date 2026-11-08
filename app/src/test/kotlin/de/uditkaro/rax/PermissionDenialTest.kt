package de.uditkaro.rax

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionDenialTest {
    @Test
    fun aDenialWithARationaleAsksAgain() {
        assertEquals(Denial.AsksAgain, afterDenial(rationaleBefore = false, rationaleNow = true, refused = false))
        assertEquals(Denial.AsksAgain, afterDenial(rationaleBefore = true, rationaleNow = true, refused = true))
    }

    @Test
    fun aClosedFirstPromptOnlyMarksThePermission() {
        assertEquals(Denial.MayNotAsk, afterDenial(rationaleBefore = false, rationaleNow = false, refused = false))
    }

    @Test
    fun aSecondRefusalInThePromptDoesNotOpenSettings() {
        assertEquals(Denial.MayNotAsk, afterDenial(rationaleBefore = true, rationaleNow = false, refused = false))
    }

    @Test
    fun androidAnsweringByItselfAgainOpensSettings() {
        assertEquals(Denial.WillNotAsk, afterDenial(rationaleBefore = false, rationaleNow = false, refused = true))
    }
}
