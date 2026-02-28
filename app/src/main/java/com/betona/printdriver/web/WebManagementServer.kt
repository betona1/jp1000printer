package com.betona.printdriver.web

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Embedded web server for printer management.
 * Serves SPA frontend and REST API on port 8080.
 */
class WebManagementServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

    private val TAG = "WebManagementServer"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        Log.i(TAG, "Request: $method $uri from ${session.remoteIpAddress}")

        return try {
            val response = route(method, uri, session)
            corsResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            corsResponse(jsonResponse(Response.Status.INTERNAL_ERROR,
                JSONObject().put("success", false).put("error", "Internal error")))
        }
    }

    private fun route(method: Method, uri: String, session: IHTTPSession): Response {
        // Static assets
        if (uri == "/" || uri == "/index.html") {
            return serveAsset("web/index.html", "text/html")
        }
        if (uri.startsWith("/web/")) {
            val assetPath = uri.removePrefix("/")
            val mime = when {
                uri.endsWith(".css") -> "text/css"
                uri.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            return serveAsset(assetPath, mime)
        }

        // Auth API (no token required)
        if (uri == "/api/auth/login" && method == Method.POST) {
            return handleLogin(session)
        }

        // All other /api/* require authentication
        if (uri.startsWith("/api/")) {
            val authHeader = session.headers["authorization"]
            val token = AuthManager.extractToken(authHeader)
            if (!AuthManager.validateToken(token)) {
                return jsonResponse(Response.Status.UNAUTHORIZED,
                    JSONObject().put("success", false).put("error", "인증이 필요합니다"))
            }

            return routeAuthenticated(method, uri, session, token!!)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun routeAuthenticated(method: Method, uri: String, session: IHTTPSession, token: String): Response {
        return when {
            uri == "/api/auth/logout" && method == Method.POST -> {
                AuthManager.logout(token)
                jsonResponse(Response.Status.OK,
                    JSONObject().put("success", true).put("data", JSONObject().put("message", "로그아웃 완료")))
            }
            uri == "/api/status" && method == Method.GET -> {
                jsonResponse(Response.Status.OK, PrinterApi.getStatus())
            }
            uri == "/api/print/test" && method == Method.POST -> {
                jsonResponse(Response.Status.OK, PrinterApi.testPrint(context))
            }
            uri == "/api/print/feed" && method == Method.POST -> {
                jsonResponse(Response.Status.OK, PrinterApi.feed())
            }
            uri == "/api/print/cut" && method == Method.POST -> {
                jsonResponse(Response.Status.OK, PrinterApi.cut())
            }
            uri == "/api/device" && method == Method.GET -> {
                jsonResponse(Response.Status.OK, DeviceApi.getDeviceInfo(context))
            }
            uri == "/api/settings" && method == Method.GET -> {
                jsonResponse(Response.Status.OK, DeviceApi.getSettings(context))
            }
            uri == "/api/settings" && method == Method.PUT -> {
                val body = parseJsonBody(session)
                jsonResponse(Response.Status.OK, DeviceApi.updateSettings(context, body))
            }
            else -> {
                jsonResponse(Response.Status.NOT_FOUND,
                    JSONObject().put("success", false).put("error", "API not found"))
            }
        }
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val username = body.optString("username", "")
        val password = body.optString("password", "")
        val token = AuthManager.login(username, password)
        return if (token != null) {
            jsonResponse(Response.Status.OK,
                JSONObject().put("success", true).put("data", JSONObject().put("token", token)))
        } else {
            jsonResponse(Response.Status.UNAUTHORIZED,
                JSONObject().put("success", false).put("error", "아이디 또는 비밀번호가 올바르지 않습니다"))
        }
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open(path)
            val response = newChunkedResponse(Response.Status.OK, mimeType, stream)
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response
        } catch (e: Exception) {
            Log.w(TAG, "Asset not found: $path")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            JSONObject(postData)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON body", e)
            JSONObject()
        }
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        response.addHeader("Connection", "close")
        return response
    }
}
