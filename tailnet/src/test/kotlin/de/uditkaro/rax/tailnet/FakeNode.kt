package de.uditkaro.rax.tailnet

internal class FakeNode : Node {
    lateinit var host: NodeHost
    var started: List<String>? = null
    var starts = 0
    var startError: Exception? = null
    var loginError: Exception? = null
    var stops = 0
    var logins = 0
    var logouts = 0
    var lookups: (String) -> String = { throw Exception("not connected") }
    val networkChanges = mutableListOf<Pair<String, String>>()

    override fun init(host: NodeHost) {
        this.host = host
    }

    override fun start(dir: String, hostname: String, deviceModel: String, osVersion: String) {
        startError?.let { throw it }
        started = listOf(dir, hostname, deviceModel, osVersion)
        starts++
    }

    override fun stop() {
        stops++
    }

    override fun login() {
        loginError?.let { throw it }
        logins++
    }

    override fun logout() {
        logouts++
    }

    override fun lookup(name: String): String = lookups(name)

    override fun newDial(): PendingDial = throw UnsupportedOperationException()

    override fun networkChanged(interfaceName: String, gateway: String) {
        networkChanges += interfaceName to gateway
    }
}

internal class FakeNetworkWatcher : NetworkWatcher {
    var listener: ((String, String) -> Unit)? = null

    override fun start(onChange: (interfaceName: String, gateway: String) -> Unit) {
        listener = onChange
    }

    override fun stop() {
        listener = null
    }
}
