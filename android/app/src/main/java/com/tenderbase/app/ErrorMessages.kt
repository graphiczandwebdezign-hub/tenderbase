package com.tenderbase.app

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Human error classification (design system rule #17).
 *
 * Users never see raw exceptions, stack traces, certificate chatter or HTTP
 * bodies. This object maps a [Throwable] to a coarse, actionable
 * [UserErrorKind]; the UI layer maps the kind to friendly copy and offers
 * recovery actions. Technical details are logged by the callers, not shown.
 */
enum class UserErrorKind {
    /** No connectivity (DNS/connect/refused/timeout). */
    OFFLINE,

    /** Server responded 5xx or is cold-starting. */
    SERVER_UNAVAILABLE,

    /** Resource gone (404 / 410). */
    NOT_FOUND,

    /** Rejected credentials (401 / 403). */
    ACCESS_DENIED,

    /** TLS/certificate problems — worded as "secure connection", never as a stack trace. */
    SECURITY,

    /** Anything unexpected. */
    GENERIC
}

object ErrorMessages {

    /** Max cause-chain depth examined before giving up. */
    private const val MAX_CAUSE_DEPTH = 8

    /** Classify any throwable (walks the cause chain) into a user-facing kind. */
    fun kindOf(e: Throwable?): UserErrorKind {
        var t = e
        var depth = 0
        while (t != null && depth < MAX_CAUSE_DEPTH) {
            when {
                t is SSLException -> return UserErrorKind.SECURITY
                t is UnknownHostException -> return UserErrorKind.OFFLINE
                t is SocketTimeoutException -> return UserErrorKind.OFFLINE
                t is ConnectException -> return UserErrorKind.OFFLINE
                t is NoRouteToHostException -> return UserErrorKind.OFFLINE
                // Certificate path validation errors live in java.security and
                // are not SSLException subclasses on every platform — match by
                // name so they never fall through to GENERIC.
                t.javaClass.simpleName.contains("CertPathValidator") ||
                    t.javaClass.simpleName.contains("SSLHandshake") -> return UserErrorKind.SECURITY
                t is IOException -> {
                    // Plain IOException with no more specific cause: treat as
                    // connectivity; the recovery action is the same (retry).
                    return UserErrorKind.OFFLINE
                }
            }
            t = t.cause
            depth++
        }
        return UserErrorKind.GENERIC
    }

    /** Classify an HTTP status into a user-facing kind. */
    fun kindOfHttp(code: Int): UserErrorKind = when {
        code == 404 || code == 410 -> UserErrorKind.NOT_FOUND
        code == 401 || code == 403 -> UserErrorKind.ACCESS_DENIED
        code in 500..599 -> UserErrorKind.SERVER_UNAVAILABLE
        else -> UserErrorKind.GENERIC
    }

    /** True when the failure was "no connection" (drives the offline banner). */
    fun isOffline(e: Throwable?): Boolean = kindOf(e) == UserErrorKind.OFFLINE

    /**
     * The technical exception text for internal logging only (never UI):
     * class name + message + one cause frame, single line.
     */
    fun forLogging(e: Throwable?): String {
        if (e == null) return "unknown error"
        val cause = e.cause?.let { " <- ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        return "${e.javaClass.simpleName}: ${e.message}$cause"
    }
}
