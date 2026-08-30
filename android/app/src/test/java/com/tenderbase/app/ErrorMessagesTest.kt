package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

/** Rule: users never see raw exceptions — every failure maps to a friendly kind. */
class ErrorMessagesTest {

    @Test
    fun `certificate chain failures are SECURITY not GENERIC`() {
        val wrapped = RuntimeException(
            "chain failed",
            java.io.IOException(
                CertPathValidatorException("Path does not chain with any of the trust anchors")
            )
        )
        assertEquals(UserErrorKind.SECURITY, ErrorMessages.kindOf(wrapped))
        assertEquals(UserErrorKind.SECURITY, ErrorMessages.kindOf(
            SSLHandshakeException("handshake failed")
        ))
    }

    @Test
    fun `connectivity failures are OFFLINE`() {
        assertEquals(UserErrorKind.OFFLINE, ErrorMessages.kindOf(SocketTimeoutException()))
        assertEquals(UserErrorKind.OFFLINE, ErrorMessages.kindOf(ConnectException()))
        assertEquals(UserErrorKind.OFFLINE, ErrorMessages.kindOf(
            RuntimeException(ConnectException("Failed to connect"))
        ))
    }

    @Test
    fun `http statuses map to actionables`() {
        assertEquals(UserErrorKind.NOT_FOUND, ErrorMessages.kindOfHttp(404))
        assertEquals(UserErrorKind.NOT_FOUND, ErrorMessages.kindOfHttp(410))
        assertEquals(UserErrorKind.ACCESS_DENIED, ErrorMessages.kindOfHttp(403))
        assertEquals(UserErrorKind.ACCESS_DENIED, ErrorMessages.kindOfHttp(401))
        assertEquals(UserErrorKind.SERVER_UNAVAILABLE, ErrorMessages.kindOfHttp(503))
        assertEquals(UserErrorKind.GENERIC, ErrorMessages.kindOfHttp(400))
    }

    @Test
    fun `null and unknown are safe`() {
        assertEquals(UserErrorKind.GENERIC, ErrorMessages.kindOf(null))
        assertEquals("unknown error", ErrorMessages.forLogging(null))
    }

    @Test
    fun `forLogging is a single line for the log, never the UI`() {
        val line = ErrorMessages.forLogging(RuntimeException("boom", IllegalStateException("inner")))
        assertEquals("RuntimeException: boom <- IllegalStateException: inner", line)
        assert(!line.contains("\n"))
    }
}
