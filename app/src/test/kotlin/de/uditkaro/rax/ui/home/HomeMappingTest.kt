package de.uditkaro.rax.ui.home

import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.BlockFacts
import de.uditkaro.rax.rex.BlockView
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.LayerView
import de.uditkaro.rax.rex.Problem
import de.uditkaro.rax.rex.ProgramRecord
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.SessionInfo
import de.uditkaro.rax.rex.SessionState
import de.uditkaro.rax.rex.SessionView
import de.uditkaro.rax.rex.Welcome
import de.uditkaro.rax.rex.WindowView
import de.uditkaro.rax.tailnet.TailnetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeMappingTest {
    /** Returns each string's resource ID as its text, so tests can tell which one was picked. */
    private val r = object : Resources(null as AssetManager?, null as DisplayMetrics?, null as Configuration?) {
        override fun getString(id: Int): String = "#$id"
        override fun getString(id: Int, vararg formatArgs: Any?): String = "#$id"
    }

    private val online = HostState(
        link = RexClient.State.Connected(Welcome("c", 1, 1, 1 shl 20, resumed = false)),
        ready = true,
        loaded = true,
    )
    private val access = Access(TailnetStatus.Stopped, localNetwork = true)

    private fun session(id: String, vararg blocks: Pair<String, BlockFacts>) = SessionState(
        SessionInfo(id, label = id),
        SessionView(
            windows = blocks.map { (block, _) ->
                WindowView(id = "w-$block", layers = listOf(LayerView(blocks = listOf(BlockView(id = block)))))
            },
        ),
    )

    private fun waitingFacts(session: String, since: String?) =
        BlockFacts(session, status = mapOf("" to ProgramRecord(state = "blocked", updatedAt = since)), statusRead = true)

    @Test
    fun shortPathReplacesOnlyAWholeHomeDirectory() {
        assertEquals("~/project", shortPath("/Users/alice/project", "/Users/alice"))
        assertEquals("~", shortPath("/Users/alice", "/Users/alice"))
        assertEquals("/Users/alice2/project", shortPath("/Users/alice2/project", "/Users/alice"))
        assertEquals("/", shortPath("/", "/"))
    }

    @Test
    fun needsYouCountsOnlySessionsOnScreen() {
        val waiting = waitingFacts("a1", since = null)
        val a = Host(id = "a", name = "A", address = "192.168.1.20")
        val b = Host(id = "b", name = "B", address = "192.168.1.21")
        val states = mapOf(
            "a" to online.copy(sessions = listOf(session("a1", "p1" to waiting)), blocks = mapOf("p1" to waiting)),
            // Offline, but it still remembers its sessions.
            "b" to HostState(
                link = RexClient.State.Down(de.uditkaro.rax.rex.Problem.Unreachable("x"), retryInMs = 1000, attempt = 3, failingMs = 10_000),
                sessions = listOf(session("b1"), session("b2")),
            ),
        )
        assertEquals(emptyList<TabRow>(), buildHome(r, listOf(a, b), states, access).waiting)
    }

    @Test
    fun needsYouListsTheNewestWaitFirst() {
        val old = waitingFacts("s1", since = "2026-11-07T01:00:00+09:00")
        val new = waitingFacts("s2", since = "2026-11-07T02:00:00+09:00")
        val undated = waitingFacts("s3", since = null)
        val host = Host(id = "a", name = "A", address = "192.168.1.20")
        val state = online.copy(
            sessions = listOf(session("s1", "p1" to old), session("s2", "p2" to new), session("s3", "p3" to undated)),
            blocks = mapOf("p1" to old, "p2" to new, "p3" to undated),
        )
        val waiting = buildHome(r, listOf(host), mapOf("a" to state), access).waiting
        assertEquals(listOf("p2", "p1", "p3"), waiting.map { it.paneId })
    }

    @Test
    fun localNetworkBlocksOnlyHostsOnTheLocalNetwork() {
        val denied = Access(TailnetStatus.Stopped, localNetwork = false, localNames = mapOf("lan.example" to true, "rex.example.com" to false))
        fun link(address: String) = hostLink(r, Host(name = "h", address = address), null, denied)
        val blocked = Link.Blocked(Need.LocalNetwork)
        assertEquals(blocked, link("192.168.1.20"))
        assertEquals(blocked, link("[fd00::5]:7297"))
        assertEquals(blocked, link("studio.local"))
        assertEquals(blocked, link("studio"))
        assertEquals(blocked, link("lan.example"))
        assertEquals(Link.Connecting, link("https://rex.example.com"))
        assertEquals(Link.Connecting, link("203.0.113.5:7297"))
        // Not looked up yet.
        assertEquals(Link.Connecting, link("nas.example.org"))
    }

    @Test
    fun anAddressThatDoesNotParseSaysSo() {
        val link = hostLink(r, Host(name = "h", address = "kuro-rex", tailnet = true), null, access)
        assertEquals(Link.Offline("#${R.string.host_invalid_short_tailnet}", retryable = false), link)
    }

    @Test
    fun waitingSinceIsReadFromTheRecord() {
        val facts = waitingFacts("s", since = "2026-11-07T02:42:04.685979+09:00")
        val row = tabRow(r, "h", "s", "s", WindowView(id = "w", layers = listOf(LayerView(blocks = listOf(BlockView(id = "p"))))), 0, mapOf("p" to facts))
        assertEquals(1_793_986_924_685L, row?.waitingSince)
        val noDate = tabRow(r, "h", "s", "s", WindowView(id = "w", layers = listOf(LayerView(blocks = listOf(BlockView(id = "p"))))), 0, mapOf("p" to waitingFacts("s", "soon")))
        assertNull(noDate?.waitingSince)
    }

    private val lan = Host(id = "a", name = "A", address = "192.168.1.20")

    private fun linkWith(state: RexClient.State) = hostLink(r, lan, HostState(link = state), access)

    @Test
    fun aDropOrAQuickFailureShowsOnlyOnceItLasts() {
        val unreachable = Problem.Unreachable("refused")
        assertEquals(Link.Connecting, linkWith(RexClient.State.Down(Problem.Closed(1006, "gone"), 500, attempt = 0, failingMs = 0)))
        assertEquals(Link.Connecting, linkWith(RexClient.State.Down(unreachable, 1000, attempt = 2, failingMs = 1_500)))
        assertEquals(Link.Connecting, linkWith(RexClient.State.Connecting(attempt = 3, lastProblem = unreachable, failingMs = 1_500)))
        assertEquals(Link.Offline("#${R.string.problem_unreachable}"), linkWith(RexClient.State.Down(unreachable, 2000, attempt = 3, failingMs = 3_500)))
        // The attempts after that keep the problem on screen.
        assertEquals(Link.Offline("#${R.string.problem_unreachable}"), linkWith(RexClient.State.Connecting(attempt = 4, lastProblem = unreachable, failingMs = 3_500)))
    }

    @Test
    fun aProblemThatIsNotTheNetworkShowsAtOnceAndBetweenAttempts() {
        val tls = Problem.Tls("bad certificate")
        assertEquals(Link.Offline("#${R.string.problem_tls}"), linkWith(RexClient.State.Down(tls, 500, attempt = 0, failingMs = 100)))
        assertEquals(Link.Offline("#${R.string.problem_tls}"), linkWith(RexClient.State.Connecting(attempt = 1, lastProblem = tls, failingMs = 100)))
    }

    @Test
    fun anAttemptTheUserAskedForShowsAsConnecting() {
        val unreachable = Problem.Unreachable("refused")
        assertEquals(Link.Connecting, linkWith(RexClient.State.Connecting(attempt = 0, lastProblem = unreachable, failingMs = 60_000)))
        assertEquals(Link.Connecting, linkWith(RexClient.State.Connecting(attempt = 0, lastProblem = Problem.Tls("x"), failingMs = 60_000)))
    }

    @Test
    fun aHostThatConnectsAgainKeepsItsSessionsAndAFirstLoadShowsNone() {
        val again = HostState(link = RexClient.State.Down(Problem.Closed(1006, "gone"), 500, attempt = 0), sessions = listOf(session("s1")), loaded = true)
        assertEquals(listOf("s1"), buildHome(r, listOf(lan), mapOf("a" to again), access).hosts.single().sessions.map { it.id })
        val first = online.copy(sessions = listOf(session("s1")), loaded = false)
        val card = buildHome(r, listOf(lan), mapOf("a" to first), access).hosts.single()
        assertEquals(Link.Connecting, card.link)
        assertEquals(emptyList<SessionGroup>(), card.sessions)
    }

    @Test
    fun aHostThatShowedAProblemShowsItsSessionsOnlyOnceOnlineAgain() {
        val sessions = listOf(session("s1"))
        val down = HostState(link = RexClient.State.Down(Problem.Unreachable("x"), 1000, attempt = 3, failingMs = 5_000), sessions = sessions, loaded = true)
        val offline = buildHome(r, listOf(lan), mapOf("a" to down), access)
        // The user asks to try again: the host connects, with its old sessions still in memory.
        val retrying = down.copy(link = RexClient.State.Connecting(attempt = 0))
        assertEquals(emptyList<SessionGroup>(), buildHome(r, listOf(lan), mapOf("a" to retrying), access, offlineBefore = offline.offline).hosts.single().sessions)
        val back = buildHome(r, listOf(lan), mapOf("a" to online.copy(sessions = sessions)), access, offlineBefore = offline.offline)
        assertEquals(listOf("s1"), back.hosts.single().sessions.map { it.id })
        assertEquals(emptySet<String>(), back.offline)
    }
}
