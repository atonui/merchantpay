package com.merchantpay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KesAmountTest {
    @Test fun exact_minor_unit_conversion() {
        assertEquals(50000L, KesAmount.parseMinorUnits("500"))
        assertEquals(50001L, KesAmount.parseMinorUnits("500.01"))
        assertEquals(1L, KesAmount.parseMinorUnits("0.01"))
        assertEquals(9223372036854775807L, KesAmount.parseMinorUnits("92233720368547758.07"))
        assertEquals("KES 500.01", KesAmount.format(50001))
    }

    @Test fun rejects_invalid_and_overflow() {
        listOf("", "0", "-1", "1.001", "1e4", "1,000", "01.00",
               "92233720368547758.08", "abc", "1.").forEach {
            assertNull("Expected invalid: $it", KesAmount.parseMinorUnits(it))
        }
    }
}
