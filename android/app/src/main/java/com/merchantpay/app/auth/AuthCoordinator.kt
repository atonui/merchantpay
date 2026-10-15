package com.merchantpay.app.auth

import kotlinx.coroutines.CancellationException

/**
 * Contract for an OIDC Authorization Code + PKCE exchange in the system browser.
 * No implementation exists until issuer, mobile client ID and verified redirect
 * domain have been reviewed and configured. Never use an embedded WebView.
 */
fun interface ExternalAuthorizationFlow {
    suspend fun authorize(): AccessTokenGrant
}

enum class SignInResult { ESTABLISHED, FAILED, NOT_ESTABLISHED }

/**
 * Coordinates local state without interpreting a browser redirect as an access
 * token. The eventual adapter must exchange the authorization code with the
 * issuer, validate the issuer configuration, and return the access-token grant.
 */
class AuthCoordinator(
    private val sessions: AuthSessionManager,
    private val authorizationFlow: ExternalAuthorizationFlow
) {
    suspend fun signIn(): SignInResult {
        val attempt = sessions.beginSignIn()
        return try {
            val grant = authorizationFlow.authorize()
            if (sessions.completeSignIn(attempt, grant)) {
                SignInResult.ESTABLISHED
            } else {
                SignInResult.NOT_ESTABLISHED
            }
        } catch (cancelled: CancellationException) {
            sessions.failSignIn(attempt)
            throw cancelled
        } catch (_: Exception) {
            sessions.failSignIn(attempt)
            SignInResult.FAILED
        }
    }

    fun signOutLocally() {
        sessions.clearLocalSession()
    }
}
