package de.uditkaro.rax.ui.home

import android.content.res.Resources
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.BlockFacts
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.Problem
import de.uditkaro.rax.rex.ProgramRecord
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.WindowView
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.ui.components.ProgramKind

private val shells = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "nu", "pwsh", "xonsh", "elvish", "login")
private val editors = setOf("nvim", "vim", "vi", "nano", "emacs", "hx", "helix", "micro", "kak", "kakoune", "joe", "mg")
private val agents = setOf(
    "claude", "claude-code", "codex", "gemini", "aider", "opencode", "amp", "goose", "crush", "pi",
    "cursor-agent", "copilot", "qwen", "kiro", "droid", "auggie",
)
private val defaultWindowLabel = Regex("^Window [0-9a-f]{8}$")

fun programKind(name: String?, facts: BlockFacts?): ProgramKind {
    val app = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }
    val n = (name ?: "").lowercase()
    return when {
        app != null || n in agents -> ProgramKind.Agent
        n in editors -> ProgramKind.Editor
        n in shells || n.isEmpty() -> ProgramKind.Shell
        else -> ProgramKind.Other
    }
}

private val appNames = mapOf(
    "claude" to "Claude Code", "claude-code" to "Claude Code", "codex" to "Codex", "gemini" to "Gemini CLI",
    "aider" to "Aider", "opencode" to "OpenCode", "amp" to "Amp", "goose" to "Goose", "crush" to "Crush",
    "cursor-agent" to "Cursor Agent", "copilot" to "Copilot CLI", "qwen" to "Qwen Code", "kiro" to "Kiro",
    "droid" to "Droid", "auggie" to "Auggie",
)

/** The name people know a program by, for the IDs programs report about themselves. */
fun appName(id: String): String = appNames[id.lowercase()] ?: id

fun shortPath(path: String?, home: String?): String? {
    if (path.isNullOrBlank()) return null
    val withHome = if (home != null && home.length > 1 && path.startsWith(home)) "~" + path.removePrefix(home) else path
    val parts = withHome.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 3) withHome else (if (withHome.startsWith("~")) "~/…/" else "/…/") + parts.takeLast(2).joinToString("/")
}

fun waitingText(r: Resources, record: ProgramRecord): String =
    record.msg?.takeIf { it.isNotBlank() } ?: when (record.kind) {
        "permission" -> r.getString(R.string.waiting_permission)
        "question" -> r.getString(R.string.waiting_question)
        "auth" -> r.getString(R.string.waiting_auth)
        else -> r.getString(R.string.waiting_generic)
    }

fun problemText(r: Resources, problem: Problem, viaTailnet: Boolean): String = when (problem) {
    is Problem.Unreachable -> r.getString(if (viaTailnet) R.string.problem_unreachable_tailnet else R.string.problem_unreachable)
    is Problem.Tls -> r.getString(R.string.problem_tls)
    is Problem.Refused -> r.getString(if (viaTailnet) R.string.problem_refused_tailnet else R.string.problem_refused)
    is Problem.NotRex -> r.getString(R.string.problem_not_rex)
    is Problem.Incompatible -> when {
        problem.reason.endsWith("update the server") -> r.getString(R.string.problem_update_rex)
        problem.reason.endsWith("update the client") -> r.getString(R.string.problem_update_rax)
        else -> problem.reason
    }
    is Problem.Closed -> r.getString(R.string.problem_closed)
}

/**
 * The row for one tab: what it is called, what it runs, and whether it waits for the user. It
 * describes [pane] when given, else the pane that waits, else the tab's main pane.
 */
fun tabRow(
    r: Resources,
    hostId: String,
    sessionId: String,
    sessionName: String,
    window: WindowView,
    index: Int,
    blocks: Map<String, BlockFacts>,
    pane: String? = null,
): TabRow? {
    val windowId = window.id ?: return null
    val panes = window.blocks.filter { it.isTerminal }.mapNotNull { it.id }
    val waitingPane = panes.firstOrNull { blocks[it]?.waiting != null }
    val paneId = pane ?: waitingPane ?: window.primaryBlockId
    val facts = paneId?.let { blocks[it] }
    val program = facts?.programName
    val kind = programKind(program, facts)
    val customLabel = window.label?.takeIf { it.isNotBlank() && !defaultWindowLabel.matches(it) }
    val agentName = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName)
    val title = customLabel
        ?: (if (kind != ProgramKind.Shell) facts?.title?.takeIf { it.isNotBlank() } else null)
        ?: agentName
        ?: program
        ?: r.getString(R.string.tab_fallback, index + 1)
    val where = shortPath(facts?.cwd, facts?.process?.home)
    val waiting = facts?.waiting
    val working = facts?.working
    val detail = when {
        waiting != null -> waitingText(r, waiting)
        working != null -> working.msg?.takeIf { it.isNotBlank() } ?: r.getString(R.string.status_working)
        program != null && program != title && where != null -> r.getString(R.string.program_in, program, where)
        where != null -> where
        else -> program?.takeIf { it != title }
    }
    return TabRow(
        hostId = hostId,
        sessionId = sessionId,
        sessionName = sessionName,
        tabId = windowId,
        paneId = paneId,
        title = title,
        detail = detail,
        program = kind,
        programName = agentName ?: program ?: title,
        waiting = waiting != null,
    )
}

private fun linkOf(r: Resources, host: Host, state: HostState?): Link = when (val l = state?.link) {
    null, RexClient.State.Stopped, is RexClient.State.Connected -> Link.Connecting
    is RexClient.State.Connecting -> if (l.attempt < 2) Link.Connecting else Link.Offline(r.getString(R.string.problem_unreachable))
    is RexClient.State.Down -> Link.Offline(problemText(r, l.problem, host.tailnet))
}

/** Whether Rax can talk to [host] now, and if not, why. */
fun hostLink(r: Resources, host: Host, state: HostState?, access: Access): Link = when {
    state?.link is RexClient.State.Connected && state.ready -> Link.Online(host.tailnet)
    host.tailnet -> when (access.tailnet) {
        is TailnetStatus.NeedsLogin -> Link.Blocked(Need.TailscaleSignIn)
        TailnetStatus.NeedsApproval -> Link.Offline(r.getString(R.string.problem_tailnet_approval), retryable = false)
        is TailnetStatus.Error -> Link.Offline(r.getString(R.string.problem_tailnet_failed))
        TailnetStatus.Stopped, TailnetStatus.Starting -> Link.Connecting
        is TailnetStatus.Running -> linkOf(r, host, state)
    }
    !access.localNetwork -> Link.Blocked(Need.LocalNetwork)
    else -> linkOf(r, host, state)
}

fun buildHome(r: Resources, hosts: List<Host>, states: Map<String, HostState>, access: Access): HomeState {
    val cards = hosts.map { host ->
        val state = states[host.id]
        val link = hostLink(r, host, state, access)
        val sessions = state?.sessions.orEmpty().map { s ->
            val name = s.info.label
            SessionGroup(
                id = s.info.id,
                name = name,
                tabs = s.view?.tabs.orEmpty().mapIndexedNotNull { i, w -> tabRow(r, host.id, s.info.id, name, w, i, state!!.blocks) },
            )
        }
        HostCard(host, link, sessions)
    }
    // With a single session, its waiting tabs already stand out in its own list.
    val sessionCount = cards.sumOf { it.sessions.size }
    val waiting = if (sessionCount < 2) emptyList()
    else cards.filter { it.link is Link.Online }.flatMap { c -> c.sessions.flatMap { it.tabs } }.filter { it.waiting }
    return HomeState(cards, waiting)
}
