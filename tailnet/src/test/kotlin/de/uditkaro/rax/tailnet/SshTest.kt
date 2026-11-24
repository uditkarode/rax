package de.uditkaro.rax.tailnet

import de.uditkaro.rax.rex.SshFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTest {
    private fun reason(message: String) = (goFailure(Exception(message)) as SshFailure).reason

    @Test
    fun goErrorsSayWhyAConnectionFailed() {
        val changed = goFailure(Exception("hostkey: ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIf9izxMyKYvsylBru9PY4xZ5qHTqhKR6ici//iaAo9I")) as SshFailure
        assertEquals(SshFailure.Reason.HostKeyChanged, changed.reason)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIf9izxMyKYvsylBru9PY4xZ5qHTqhKR6ici//iaAo9I", changed.hostKey)
        assertEquals(SshFailure.Reason.SignIn, reason("auth: ssh: unable to authenticate"))
        assertEquals("ssh: unable to authenticate", goFailure(Exception("auth: ssh: unable to authenticate")).message)
        assertEquals(SshFailure.Reason.NoRex, reason("norex: rex was not found on the server"))
        assertEquals(SshFailure.Reason.RexFailed, reason("rex: the server's status cannot be read"))
        assertEquals(SshFailure.Reason.Other, reason("ssh: rejected"))
        assertTrue(goFailure(Exception("timeout: connecting to 10.0.0.2:22: i/o timeout")) is SocketTimeoutException)
        assertTrue(goFailure(Exception("unreachable: connecting to 10.0.0.2:22: connection refused")) is ConnectException)
        assertTrue(goFailure(Exception("something else")) is ConnectException)
        val io = IOException("closed")
        assertSame(io, goFailure(io))
    }

    @Test
    fun fingerprintsMatchSshKeygen() {
        // ssh-keygen -lf prints SHA256:vzduHZVczTHEesRJRikD5/4zcBeK2pQFhyriRs5oHfY for this key.
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIf9izxMyKYvsylBru9PY4xZ5qHTqhKR6ici//iaAo9I someone@host"
        assertEquals("SHA256:vzduHZVczTHEesRJRikD5/4zcBeK2pQFhyriRs5oHfY", SshKeys.fingerprint(key))
        assertNull(SshKeys.fingerprint("not a key"))
        assertNull(SshKeys.fingerprint("ssh-ed25519 !!!"))
    }
}
