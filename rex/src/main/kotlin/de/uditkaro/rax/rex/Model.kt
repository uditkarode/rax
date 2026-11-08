package de.uditkaro.rax.rex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The parts of Rex's data model Rax reads, as the control connection sends them. Every field the
 * app does not need is left out, and every field may be missing from an older or newer server.
 */

@Serializable
data class SessionInfo(
    @SerialName("session_id") val id: String,
    val label: String = "",
)

@Serializable
data class SessionView(
    val revision: Long = 0,
    @SerialName("session_id") val sessionId: String? = null,
    val label: String? = null,
    @SerialName("active_window_id") val activeWindowId: String? = null,
    val windows: List<WindowView>? = null,
) {
    val tabs: List<WindowView> get() = windows.orEmpty()

    /** The IDs of every terminal pane in the session, minimized ones included. */
    val terminalIds: Set<String>
        get() = tabs.flatMap { w -> w.allBlocks.filter { it.isTerminal }.mapNotNull { it.id } }.toSet()
}

@Serializable
data class WindowView(
    @SerialName("window_id") val id: String? = null,
    val label: String? = null,
    @SerialName("focused_block_id") val focusedBlockId: String? = null,
    @SerialName("zoomed_block_id") val zoomedBlockId: String? = null,
    val layers: List<LayerView>? = null,
) {
    /** Panes in reading order: the tiled layout first, left to right and top to bottom, then floating ones. */
    val blocks: List<BlockView>
        get() = blocksOf(layers.orEmpty().filter { it.state != "minimized" })

    /** Every pane of the tab, minimized floating ones included, in the order of [blocks]. */
    val allBlocks: List<BlockView>
        get() = blocksOf(layers.orEmpty())

    private fun blocksOf(layers: List<LayerView>): List<BlockView> = layers.flatMap { layer ->
        val byId = layer.blocks.orEmpty().associateBy { it.id }
        val ordered = layer.layout?.blockIds().orEmpty().mapNotNull { byId[it] }
        ordered + layer.blocks.orEmpty().filter { it !in ordered }
    }

    /** The pane a phone should show first: the zoomed one, the focused one, or the first. */
    val primaryBlockId: String?
        get() = zoomedBlockId ?: focusedBlockId?.takeIf { id -> blocks.any { it.id == id } } ?: blocks.firstOrNull()?.id
}

@Serializable
data class LayerView(
    @SerialName("layer_id") val id: String? = null,
    val kind: String = "tiled",
    val state: String? = null,
    val layout: LayoutNode? = null,
    val blocks: List<BlockView>? = null,
)

@Serializable
data class LayoutNode(
    @SerialName("block_id") val blockId: String? = null,
    val split: Split? = null,
) {
    fun blockIds(): List<String> = when {
        blockId != null -> listOf(blockId)
        split != null -> split.before.blockIds() + split.after.blockIds()
        else -> emptyList()
    }

    @Serializable
    data class Split(
        @SerialName("split_id") val id: String? = null,
        val direction: String = "horizontal",
        val ratio: Double = 0.5,
        val before: LayoutNode = LayoutNode(),
        val after: LayoutNode = LayoutNode(),
    )
}

@Serializable
data class BlockView(
    @SerialName("block_id") val id: String? = null,
    val label: String? = null,
    @SerialName("creator_name") val creator: String? = null,
    val flavor: String? = null,
    val owner: String? = null,
) {
    val isTerminal: Boolean get() = creator == null || creator == TERMINAL

    /**
     * The label the user gave the block, or null for one Rex gave it: the flavor's short name for a
     * new block, or `Block 89abcdef` after the label was cleared.
     */
    val userLabel: String?
        get() = label?.takeIf { it.isNotBlank() && it != flavor?.substringAfterLast('.') && !defaultBlockLabel.matches(it) }
}

private val defaultBlockLabel = Regex("^Block [0-9a-f]{8}$")

const val TERMINAL = "com.superlogical.terminal"

/** A status a program reported with OSC 7501, such as an agent that waits for permission. */
@Serializable
data class ProgramRecord(
    val id: String? = null,
    val state: String = "idle",
    val kind: String? = null,
    val progress: Int? = null,
    val app: String? = null,
    @SerialName("effective_app") val effectiveApp: String? = null,
    val title: String? = null,
    val msg: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val blocked: Boolean get() = state == "blocked"
    val working: Boolean get() = state == "working"
}

@Serializable
data class ProcessInfo(
    val name: String? = null,
    val argv0: String? = null,
    val script: String? = null,
    val cwd: String? = null,
) {
    /** The name Rex suggests for labelling a process: the script, else argv0, else the executable. */
    val label: String?
        get() = (script?.substringAfterLast('/') ?: argv0?.removePrefix("-")?.substringAfterLast('/') ?: name)
            ?.takeIf { it.isNotBlank() }
}

@Serializable
data class ProcessReport(
    val child: ProcessInfo? = null,
    val foreground: ProcessInfo? = null,
    val home: String? = null,
)

@Serializable
data class TailscaleStatus(
    val state: String = "disabled",
    @SerialName("dns_name") val dnsName: String? = null,
    val https: Https? = null,
) {
    @Serializable
    data class Https(val state: String = "pending", val url: String? = null)
}

@Serializable
data class ServerStatus(
    @SerialName("host_id") val hostId: String? = null,
    @SerialName("instance_id") val instanceId: String? = null,
    @SerialName("api_revision") val apiRevision: Int = 0,
    val tailscale: TailscaleStatus? = null,
)

@Serializable
data class ServerVersion(
    @SerialName("rex_version") val rexVersion: String? = null,
    val commit: String? = null,
    @SerialName("api_revision") val apiRevision: Int = 0,
)
