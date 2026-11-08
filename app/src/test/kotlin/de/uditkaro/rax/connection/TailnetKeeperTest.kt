package de.uditkaro.rax.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailnetKeeperTest {
    private val hostsNeed = MutableStateFlow<Boolean?>(null)
    private val calls = mutableListOf<String>()

    private fun TestScope.keeper() = TailnetKeeper(
        scope = backgroundScope,
        hostsNeed = hostsNeed,
        start = { calls += "start" },
        stop = { calls += "stop" },
        graceMs = 1_000,
    ).also { runCurrent() }

    @Test
    fun nothingHappensBeforeTheHostsAreRead() = runTest {
        keeper()
        advanceTimeBy(5_000)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun stopsAWhileAfterTheHostsStopNeedingIt() = runTest {
        keeper()
        hostsNeed.value = true
        runCurrent()
        assertEquals(listOf("start"), calls)

        hostsNeed.value = false
        advanceTimeBy(999)
        assertEquals(listOf("start"), calls)
        advanceTimeBy(2)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun aNeedWithinTheGracePeriodKeepsItRunning() = runTest {
        keeper()
        hostsNeed.value = true
        runCurrent()
        hostsNeed.value = false
        advanceTimeBy(500)
        hostsNeed.value = true
        advanceTimeBy(5_000)
        assertEquals(listOf("start", "start"), calls)
    }

    @Test
    fun aHoldKeepsItRunningWithoutHosts() = runTest {
        val keeper = keeper()
        hostsNeed.value = false
        val release = keeper.hold()
        runCurrent()
        assertEquals(listOf("start"), calls)

        val second = keeper.hold()
        release()
        release()
        advanceTimeBy(5_000)
        assertEquals(listOf("start"), calls)

        second()
        advanceTimeBy(1_001)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun holdingReleasesWhenTheBlockEnds() = runTest {
        val keeper = keeper()
        hostsNeed.value = false
        runCatching { keeper.holding { runCurrent(); throw IllegalStateException() } }
        advanceTimeBy(1_001)
        assertEquals(listOf("start", "stop"), calls)
    }
}
