package com.kexu.app

import com.kexu.app.domain.WeekRuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekRuleParserTest {

    @Test
    fun parsesBasicRange() {
        val r = WeekRuleParser.parse("1-16周")
        assertEquals((1..16).toSet(), r.weeks)
        assertNull(r.error)
    }

    @Test
    fun parsesOddRange() {
        assertEquals(setOf(1, 3, 5, 7), WeekRuleParser.parse("1-8周(单)").weeks)
    }

    @Test
    fun parsesEvenRange() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), WeekRuleParser.parse("2-16周(双)").weeks)
    }

    @Test
    fun parsesMultipleRanges() {
        assertEquals(setOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11), WeekRuleParser.parse("1-5周,7-11周").weeks)
    }

    @Test
    fun parsesMixedParityRanges() {
        val expected = setOf(1, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14)
        assertEquals(expected, WeekRuleParser.parse("1-3周(单),4-6周(双),7-14周").weeks)
    }

    @Test
    fun parsesChineseConnectorAndEnumeration() {
        assertEquals((1..8).toSet(), WeekRuleParser.parse("第1至8周").weeks)
        assertEquals(setOf(1, 3, 5, 7), WeekRuleParser.parse("1、3、5、7周").weeks)
        assertEquals(setOf(16), WeekRuleParser.parse("16周").weeks)
    }

    @Test
    fun handlesFullWidthAndSpaces() {
        val r = WeekRuleParser.parse("１－８周（单）")
        assertEquals(setOf(1, 3, 5, 7), r.weeks)
    }

    @Test
    fun deduplicatesWeeks() {
        assertEquals(setOf(1, 2, 3, 4, 5), WeekRuleParser.parse("1-3,2-5周").weeks)
    }

    @Test
    fun invalidExpressionKeepsRawAndError() {
        val r = WeekRuleParser.parse("随时上课")
        assertTrue(r.weeks.isEmpty())
        assertNotNull(r.error)
        assertEquals("随时上课", r.raw)
    }

    @Test
    fun invalidRangeReported() {
        val r = WeekRuleParser.parse("20-10周")
        assertNotNull(r.error)
        assertTrue(r.weeks.isEmpty())
    }

    @Test
    fun csvIsSortedAndDeduped() {
        assertEquals("1,2,3,5", WeekRuleParser.parse("1-3,3,5周").csv)
    }
}
