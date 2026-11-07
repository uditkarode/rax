package de.uditkaro.rax.ui.home

import android.content.res.Resources
import androidx.annotation.DrawableRes
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.BlockFacts
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.ProcessInfo
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
    "cursor-agent", "copilot", "qwen", "kiro", "kiro-cli", "droid", "auggie",
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
    "cursor-agent" to "Cursor Agent", "copilot" to "Copilot CLI", "qwen" to "Qwen Code", "kiro" to "Kiro", "kiro-cli" to "Kiro",
    "droid" to "Droid", "auggie" to "Auggie",
)

/** The name people know a program by, for the IDs programs report about themselves. */
fun appName(id: String): String = appNames[id.lowercase()] ?: id

private val programIcons = mapOf(
    "claude" to R.drawable.ic_program_claude, "claude-code" to R.drawable.ic_program_claude,
    "codex" to R.drawable.ic_program_codex, "amp" to R.drawable.ic_program_amp, "goose" to R.drawable.ic_program_goose,
    "kiro" to R.drawable.ic_program_kiro, "kiro-cli" to R.drawable.ic_program_kiro,
    "gemini" to R.drawable.ic_program_googlegemini, "copilot" to R.drawable.ic_program_githubcopilot,
    "cursor" to R.drawable.ic_program_cursor, "cursor-agent" to R.drawable.ic_program_cursor,
    "opencode" to R.drawable.ic_program_opencode, "qwen" to R.drawable.ic_program_qwen,
    "cline" to R.drawable.ic_program_cline, "windsurf" to R.drawable.ic_program_windsurf,
    "nvim" to R.drawable.ic_program_neovim, "vim" to R.drawable.ic_program_vim, "vi" to R.drawable.ic_program_vim,
    "emacs" to R.drawable.ic_program_gnuemacs, "hx" to R.drawable.ic_program_helix, "helix" to R.drawable.ic_program_helix,
    "zsh" to R.drawable.ic_program_zsh, "bash" to R.drawable.ic_program_gnubash,
    "fish" to R.drawable.ic_program_fishshell, "nu" to R.drawable.ic_program_nushell,
    "git" to R.drawable.ic_program_git, "gh" to R.drawable.ic_program_github, "glab" to R.drawable.ic_program_gitlab,
    "docker" to R.drawable.ic_program_docker, "docker-compose" to R.drawable.ic_program_docker,
    "podman" to R.drawable.ic_program_podman, "kubectl" to R.drawable.ic_program_kubernetes,
    "helm" to R.drawable.ic_program_helm, "terraform" to R.drawable.ic_program_terraform,
    "ansible" to R.drawable.ic_program_ansible, "ansible-playbook" to R.drawable.ic_program_ansible,
    "nix" to R.drawable.ic_program_nixos, "nix-shell" to R.drawable.ic_program_nixos,
    "nixos-rebuild" to R.drawable.ic_program_nixos, "brew" to R.drawable.ic_program_homebrew,
    "python" to R.drawable.ic_program_python, "ipython" to R.drawable.ic_program_python, "pip" to R.drawable.ic_program_python,
    "uv" to R.drawable.ic_program_uv, "uvx" to R.drawable.ic_program_uv, "pytest" to R.drawable.ic_program_pytest,
    "jupyter" to R.drawable.ic_program_jupyter, "jupyter-lab" to R.drawable.ic_program_jupyter,
    "node" to R.drawable.ic_program_nodedotjs, "npm" to R.drawable.ic_program_npm, "npx" to R.drawable.ic_program_npm,
    "pnpm" to R.drawable.ic_program_pnpm, "yarn" to R.drawable.ic_program_yarn,
    "bun" to R.drawable.ic_program_bun, "bunx" to R.drawable.ic_program_bun, "deno" to R.drawable.ic_program_deno,
    "tsc" to R.drawable.ic_program_typescript, "vite" to R.drawable.ic_program_vite,
    "vitest" to R.drawable.ic_program_vitest, "jest" to R.drawable.ic_program_jest,
    "cargo" to R.drawable.ic_program_rust, "rustc" to R.drawable.ic_program_rust, "rustup" to R.drawable.ic_program_rust,
    "go" to R.drawable.ic_program_go, "swift" to R.drawable.ic_program_swift, "xcodebuild" to R.drawable.ic_program_xcode,
    "zig" to R.drawable.ic_program_zig, "ruby" to R.drawable.ic_program_ruby, "irb" to R.drawable.ic_program_ruby,
    "bundle" to R.drawable.ic_program_ruby, "php" to R.drawable.ic_program_php,
    "elixir" to R.drawable.ic_program_elixir, "iex" to R.drawable.ic_program_elixir, "mix" to R.drawable.ic_program_elixir,
    "dart" to R.drawable.ic_program_dart, "flutter" to R.drawable.ic_program_flutter, "julia" to R.drawable.ic_program_julia,
    "lua" to R.drawable.ic_program_lua, "luajit" to R.drawable.ic_program_lua,
    "kotlin" to R.drawable.ic_program_kotlin, "kotlinc" to R.drawable.ic_program_kotlin,
    "gradle" to R.drawable.ic_program_gradle, "gradlew" to R.drawable.ic_program_gradle,
    "bazel" to R.drawable.ic_program_bazel, "bazelisk" to R.drawable.ic_program_bazel, "cmake" to R.drawable.ic_program_cmake,
    "psql" to R.drawable.ic_program_postgresql, "postgres" to R.drawable.ic_program_postgresql,
    "mysql" to R.drawable.ic_program_mysql, "mongosh" to R.drawable.ic_program_mongodb, "mongod" to R.drawable.ic_program_mongodb,
    "redis-cli" to R.drawable.ic_program_redis, "redis-server" to R.drawable.ic_program_redis,
    "sqlite" to R.drawable.ic_program_sqlite, "duckdb" to R.drawable.ic_program_duckdb,
    "ollama" to R.drawable.ic_program_ollama, "nginx" to R.drawable.ic_program_nginx, "curl" to R.drawable.ic_program_curl,
    "htop" to R.drawable.ic_program_htop, "tmux" to R.drawable.ic_program_tmux,
)

/**
 * The logo for a pane: the agent's own ID first, then the names of its process. A version at the
 * end of a name is ignored, so `python3.12` gets the Python logo.
 */
@DrawableRes
fun programIcon(app: String?, process: ProcessInfo?): Int? {
    val names = listOfNotNull(app, process?.label, process?.argv0?.removePrefix("-")?.substringAfterLast('/'), process?.name)
    for (name in names.map { it.lowercase() }) {
        programIcons[name]?.let { return it }
        programIcons[name.trimEnd { it.isDigit() || it == '.' }]?.let { return it }
    }
    return null
}

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
    val app = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }
    val agentName = app?.let(::appName)
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
        icon = programIcon(app, facts?.process?.foreground ?: facts?.process?.child),
        waiting = waiting != null,
    )
}

private fun linkOf(r: Resources, host: Host, state: HostState?): Link = when (val l = state?.link) {
    null, RexClient.State.Stopped, is RexClient.State.Connected -> Link.Connecting
    is RexClient.State.Connecting -> if (l.attempt < 2) Link.Connecting else Link.Offline(
        problemText(r, l.lastProblem ?: Problem.Unreachable("no answer"), host.tailnet),
    )
    // A single lost connection or failed attempt is common on a phone, and the next attempt follows
    // half a second later, so the host shows as offline from the second failure on.
    is RexClient.State.Down -> when {
        l.attempt == 0 && l.retryInMs != null && (l.problem is Problem.Unreachable || l.problem is Problem.Closed) -> Link.Connecting
        else -> Link.Offline(problemText(r, l.problem, host.tailnet))
    }
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
