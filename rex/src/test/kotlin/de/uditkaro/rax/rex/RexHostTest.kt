package de.uditkaro.rax.rex

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RexHostTest {
    private val rex = FakeRex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = RexHost(rex.endpoint, rex.http, ClientIdentity("Rax test", "0", "jvm"), scope)
    private val alerts = CopyOnWriteArrayList<Alert>()

    init {
        rex.sessions += "session:1" to "work"
        scope.launch { host.alerts.collect { alerts += it } }
    }

    @After
    fun tearDown() {
        host.stop()
        scope.cancel()
        rex.close()
    }

    private fun waitFor(what: String, check: (HostState) -> Boolean): HostState = runBlocking {
        try {
            withTimeout(10_000) { host.state.first(check) }
        } catch (e: Exception) {
            throw AssertionError("$what; state: ${host.state.value}", e)
        }
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!check()) {
            if (System.nanoTime() > end) throw AssertionError(what)
            Thread.sleep(20)
        }
    }

    @Test
    fun factsAreReadAgainAfterAReconnectWithTheSameView() {
        rex.views["session:1"] = FakeRex.view(3, null to listOf("block:a"))
        rex.facts["block:a" to "program_status"] = FakeRex.status("" to "working")
        host.start()
        val first = rex.nextControl()
        waitFor("status read") { it.blocks["block:a"]?.statusRead == true }

        // The agent starts to wait while the phone has no connection; the layout stays the same.
        rex.facts["block:a" to "program_status"] = FakeRex.status("" to "blocked")
        rex.resumed = true
        first.drop()
        rex.nextControl()

        waitFor("waiting after the reconnect") { it.blocks["block:a"]?.waiting != null }
        waitUntil("waiting alert") { alerts.any { it is Alert.Waiting && it.blockId == "block:a" } }
    }

    @Test
    fun statusEventDuringTheReadKeepsTheRestOfTheRead() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        val held = CountDownLatch(1)
        lateinit var read: FakeRex.Request
        rex.onRequest = { _, r ->
            if (r.method == "$TERMINAL.program_status") {
                read = r
                held.countDown()
                true
            } else {
                false
            }
        }
        host.start()
        val control = rex.nextControl()
        assertTrue(held.await(10, TimeUnit.SECONDS))

        // A subagent reports while the read is on its way; the read already holds that report.
        control.blockEvent("session:1", "block:a", "program_status_changed", buildJsonObject {
            put("id", "child")
            put("record", buildJsonObject { put("id", "child"); put("state", "working") })
        })
        waitFor("child record from the event") { it.blocks["block:a"]?.status?.containsKey("child") == true }
        control.reply(read, FakeRex.status("" to "blocked", "child" to "working"))

        val facts = waitFor("root record from the read") { it.blocks["block:a"]?.statusRead == true }.blocks["block:a"]!!
        assertEquals(setOf("", "child"), facts.status.keys)
        assertNotNull(facts.waiting)
    }

    @Test
    fun failedStatusReadIsTriedAgain() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        rex.facts["block:a" to "program_status"] = FakeRex.status("" to "blocked")
        var failed = false
        rex.onRequest = { control, r ->
            if (r.method == "$TERMINAL.program_status" && !failed) {
                failed = true
                control.fail(r, "internal", 500)
                true
            } else {
                false
            }
        }
        host.start()
        waitFor("waiting after the second read") { it.blocks["block:a"]?.waiting != null }
    }

    @Test
    fun failedAttachIsTriedAgain() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        var failed = false
        rex.onRequest = { control, r ->
            if (r.method == "session.attach" && !failed) {
                failed = true
                control.fail(r, "timeout", 504)
                true
            } else {
                false
            }
        }
        host.start()
        waitFor("view after the second attach") { it.session("session:1")?.view?.revision == 1L }
        assertEquals(2, rex.requests.count { it == "session.attach" })
    }

    @Test
    fun minimizedPanesKeepTheirFacts() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"), "minimized" to listOf("block:m"))
        rex.facts["block:m" to "program_status"] = FakeRex.status("" to "blocked")
        host.start()
        val control = rex.nextControl()
        waitFor("minimized pane read") { it.blocks["block:m"]?.waiting != null }

        rex.views["session:1"] = FakeRex.view(2, null to listOf("block:a"), "minimized" to listOf("block:m"))
        control.sessionEvent("session:1", "session_view_changed", buildJsonObject { put("revision", 2) })
        val st = waitFor("second view") { it.session("session:1")?.view?.revision == 2L }
        assertNotNull(st.blocks["block:m"]?.waiting)
        assertEquals(1, alerts.count { it is Alert.Waiting && it.blockId == "block:m" })
    }

    @Test
    fun notificationBeforeTheSessionListIsDelivered() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        val held = CountDownLatch(1)
        lateinit var bootstrap: FakeRex.Request
        rex.onRequest = { _, r ->
            if (r.method == "session.bootstrap") {
                bootstrap = r
                held.countDown()
                true
            } else {
                false
            }
        }
        host.start()
        val control = rex.nextControl()
        assertTrue(held.await(10, TimeUnit.SECONDS))
        control.blockEvent("session:1", "block:a", "desktop_notification", buildJsonObject {
            put("title", "")
            put("body", "build done")
        })
        control.answer(bootstrap)
        waitUntil("notification") { alerts.any { it is Alert.Notification && it.body == "build done" } }
    }

    @Test
    fun restartedServerReplacesTheKeptViews() {
        rex.views["session:1"] = FakeRex.view(120, null to listOf("block:old"))
        host.start()
        val first = rex.nextControl()
        waitFor("first view") { it.session("session:1")?.view?.revision == 120L }

        // Rex restarts and restores the session under the same ID, with its revisions from the start.
        rex.instanceId = "instance-2"
        rex.views["session:1"] = FakeRex.view(3, null to listOf("block:new"))
        first.drop()
        rex.nextControl()

        val st = waitFor("restored view") { it.session("session:1")?.view?.revision == 3L }
        assertEquals(setOf("block:new"), st.blocks.keys)
    }

    @Test
    fun bootstrapCutOffByADropStillResumes() {
        var cut = false
        rex.onRequest = { control, r ->
            if (r.method == "session.bootstrap" && !cut) {
                cut = true
                control.drop()
                true
            } else {
                false
            }
        }
        host.start()
        val first = rex.nextControl()
        val second = rex.nextControl()
        val hello = second.hello.poll(10, TimeUnit.SECONDS)!!
        assertEquals(first.token, hello["reconnect_token"]?.jsonPrimitive?.contentOrNull)
        waitFor("ready on the second connection") { it.ready }
    }

    @Test
    fun theFirstLoadEndsWhenThePanesFactsAreInAndStaysOverAReconnect() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        val held = CountDownLatch(1)
        lateinit var read: FakeRex.Request
        rex.onRequest = { _, r ->
            if (r.method == "$TERMINAL.program_status" && held.count > 0) {
                read = r
                held.countDown()
                true
            } else {
                false
            }
        }
        host.start()
        val control = rex.nextControl()
        assertTrue(held.await(10, TimeUnit.SECONDS))
        val waiting = waitFor("session attached") { it.session("session:1")?.attach == Attach.Done }
        assertFalse(waiting.loaded)

        // Well before the 2 s the first load waits at most.
        control.reply(read, FakeRex.status("" to "working"))
        runBlocking { withTimeout(500) { host.state.first { it.loaded } } }

        // Every state from the drop until the new connection is ready keeps the sessions loaded.
        val states = CopyOnWriteArrayList<HostState>()
        val recording = scope.launch { host.state.collect { states += it } }
        control.drop()
        rex.nextControl()
        waitFor("ready again") { it.ready && it.session("session:1")?.attach == Attach.Done }
        recording.cancel()
        assertTrue(states.all { it.loaded })
    }

    @Test
    fun aConnectionThatFailsItsSetupCountsAsFailing() {
        rex.onRequest = { c, r ->
            if (r.method == "session.bootstrap") {
                c.fail(r, "internal", 500)
                true
            } else {
                false
            }
        }
        host.start()
        // Without counting, each new connection would start again from the first attempt.
        runBlocking { withTimeout(10_000) { host.client.state.first { it is RexClient.State.Connecting && it.attempt >= 2 } } }
    }

    @Test
    fun theFirstLoadDoesNotWaitLongForAReadThatHangs() {
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
        rex.onRequest = { _, r -> r.method == "$TERMINAL.program_status" }
        host.start()
        rex.nextControl()
        val started = System.nanoTime()
        waitFor("loaded") { it.loaded }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }
}
