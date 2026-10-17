package com.apex.workspace.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogParserTest {

    private val rs = '\u001e'
    private val us = '\u001f'

    @Test
    fun `single record`() {
        val text = "0123456789abcdef0123456789abcdef01234567" + us + "0123456" + us + "Guru Agent" +
            us + "1700000000" + us + "feat: hello" + us + "body line" + rs
        val log = LogParser.parse(text)
        val c = log.single()
        assertEquals("0123456789abcdef0123456789abcdef01234567", c.sha)
        assertEquals("0123456", c.shortSha)
        assertEquals("Guru Agent", c.author)
        assertEquals(1_700_000_000_000L, c.dateMs)
        assertEquals("feat: hello", c.subject)
        assertEquals("body line", c.body)
    }

    @Test
    fun `multiple records`() {
        val text = buildString {
            append("a").append(us).append("a1").append(us).append("X").append(us)
                .append("100").append(us).append("s1").append(us).append(rs)
            append("b").append(us).append("b1").append(us).append("Y").append(us)
                .append("200").append(us).append("s2").append(us).append(rs)
        }
        val log = LogParser.parse(text)
        assertEquals(2, log.size)
        assertEquals("s2", log[1].subject)
        assertEquals(200_000L, log[1].dateMs)
    }

    @Test
    fun `blank body becomes null`() {
        val text = "a" + us + "a1" + us + "X" + us + "100" + us + "s1" + us + us + rs
        val c = LogParser.parse(text).single()
        assertNull(c.body)
    }

    @Test
    fun `bad timestamp skipped`() {
        val text = "a" + us + "a1" + us + "X" + us + "notanumber" + us + "s1" + us + rs
        assertTrue(LogParser.parse(text).isEmpty())
    }

    @Test
    fun `empty input`() {
        assertTrue(LogParser.parse("").isEmpty())
    }
}

class BranchParserTest {

    @Test
    fun `local and remote branches`() {
        val text = "*\u0000main\u0000100\n \u0000dev\u000050\n \u0000origin/main\u000080\n"
        val branches = BranchParser.parse(text)
        assertEquals(3, branches.size)
        val main = branches[0]
        assertTrue(main.current)
        assertFalse(main.isRemote)
        assertEquals(100_000L, main.lastCommitMs)
        val dev = branches[1]
        assertFalse(dev.current)
        assertFalse(dev.isRemote)
        val origin = branches[2]
        assertFalse(origin.current)
        assertTrue(origin.isRemote)
    }

    @Test
    fun `no commit time`() {
        val text = " \u0000orphan\u0000\n"
        val b = BranchParser.parse(text).single()
        assertNull(b.lastCommitMs)
    }

    @Test
    fun `empty input`() {
        assertTrue(BranchParser.parse("").isEmpty())
    }
}
