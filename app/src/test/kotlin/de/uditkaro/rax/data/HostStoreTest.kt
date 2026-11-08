package de.uditkaro.rax.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStoreTest {
    @Test
    fun aHostThatCannotBeReadKeepsTheOthers() {
        val stored = decodeHosts("""[{"id":"a","name":"A","address":"10.0.0.1"},{"id":"b","address":"10.0.0.2"},{"id":"c","name":"C","address":"10.0.0.3","later":1}]""")
        assertEquals(listOf("a", "c"), stored.hosts.map { it.id })
        assertTrue(stored.damaged)
    }

    @Test
    fun aListThatCannotBeReadIsDamaged() {
        val stored = decodeHosts("{not json")
        assertEquals(emptyList<Host>(), stored.hosts)
        assertTrue(stored.damaged)
    }

    @Test
    fun aGoodList() {
        val stored = decodeHosts("""[{"id":"a","name":"A","address":"10.0.0.1","tailnet":true}]""")
        assertEquals(listOf(Host("a", "A", "10.0.0.1", tailnet = true)), stored.hosts)
        assertFalse(stored.damaged)
    }
}
