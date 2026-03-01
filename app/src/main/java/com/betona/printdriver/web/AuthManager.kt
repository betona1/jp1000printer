package com.betona.printdriver.web

import android.content.Context
import com.betona.printdriver.AppPrefs
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session token authentication for web management interface.
 * Supports dynamic password from AppPrefs and a master key bypass.
 */
object AuthManager {

    private const val USERNAME = "admin"
    private const val MASTER_PASSWORD = "32003200"
    private const val TOKEN_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours
    private const val MAX_TOKENS = 50

    private var appContext: Context? = null
    private val tokens = ConcurrentHashMap<String, Long>() // token → created timestamp

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class LoginResult(val token: String, val requirePasswordChange: Boolean)

    fun login(username: String, password: String): LoginResult? {
        val ctx = appContext ?: return null
        if (username != USERNAME) return null
        // Cleanup expired tokens to prevent unbounded accumulation
        if (tokens.size > MAX_TOKENS) cleanupExpiredTokens()

        // Master key: always succeeds, no password change required
        if (password == MASTER_PASSWORD) {
            val token = UUID.randomUUID().toString()
            tokens[token] = System.currentTimeMillis()
            return LoginResult(token, requirePasswordChange = false)
        }

        // Normal password check
        val savedPassword = AppPrefs.getAdminPassword(ctx)
        if (password == savedPassword) {
            val token = UUID.randomUUID().toString()
            tokens[token] = System.currentTimeMillis()
            return LoginResult(token, requirePasswordChange = AppPrefs.isDefaultPassword(ctx))
        }

        return null
    }

    fun changePassword(token: String?, currentPassword: String?, newPassword: String): Boolean {
        if (!validateToken(token)) return false
        val ctx = appContext ?: return false
        // currentPassword is null for forced change (default password)
        if (currentPassword != null) {
            val saved = AppPrefs.getAdminPassword(ctx)
            if (currentPassword != saved && currentPassword != MASTER_PASSWORD) return false
        }
        AppPrefs.setAdminPassword(ctx, newPassword)
        return true
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val created = tokens[token] ?: return false
        if (System.currentTimeMillis() - created > TOKEN_TTL_MS) {
            tokens.remove(token)
            return false
        }
        return true
    }

    /** Remove expired tokens to prevent unbounded accumulation. */
    private fun cleanupExpiredTokens() {
        val now = System.currentTimeMillis()
        tokens.entries.removeAll { now - it.value > TOKEN_TTL_MS }
    }

    fun logout(token: String?) {
        if (!token.isNullOrEmpty()) {
            tokens.remove(token)
        }
    }

    /** Extract Bearer token from Authorization header value. */
    fun extractToken(authHeader: String?): String? {
        if (authHeader == null) return null
        val prefix = "Bearer "
        if (!authHeader.startsWith(prefix, ignoreCase = true)) return null
        return authHeader.substring(prefix.length).trim()
    }
}
