package com.betona.printdriver.web

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session token authentication for web management interface.
 * Credentials: admin / 1234 (same as MainActivity admin password).
 */
object AuthManager {

    private const val USERNAME = "admin"
    private const val PASSWORD = "1234"

    private val tokens = ConcurrentHashMap<String, Long>() // token → created timestamp

    fun login(username: String, password: String): String? {
        if (username == USERNAME && password == PASSWORD) {
            val token = UUID.randomUUID().toString()
            tokens[token] = System.currentTimeMillis()
            return token
        }
        return null
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return tokens.containsKey(token)
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
