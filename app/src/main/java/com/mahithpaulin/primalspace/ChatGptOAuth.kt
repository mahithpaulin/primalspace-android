package com.mahithpaulin.primalspace

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sign in with ChatGPT (SIWC) for open-source clients, plus Responses API access
 * using the signed-in account's ChatGPT plan.
 */
object ChatGptOAuth {
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
    const val API_BASE = "https://api.openai.com/v1"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val CALLBACK_PATH = "/auth/callback"
    const val ISSUER = "https://auth.openai.com"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    class OAuthException(message: String) : Exception(message)

    data class Session(
        val clientId: String,
        val hostId: String,
        val idToken: String?,
        val accessToken: String,
        val refreshToken: String?,
        val email: String,
        val scopes: List<String>,
        val expiresAt: Long,
    )

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomUrlSafe(length: Int): String {
        val bytes = ByteArray(length)
        java.security.SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return base64Url(digest)
    }

    fun hostId(prefs: Prefs): String {
        if (prefs.agentHostId.isBlank()) {
            prefs.agentHostId = "urn:uuid:${UUID.randomUUID()}"
        }
        return prefs.agentHostId
    }

    data class Authorization(val url: String, val verifier: String, val state: String, val nonce: String, val redirectUri: String)

    fun authorizationRequest(prefs: Prefs, agentName: String): Authorization {
        val verifier = randomUrlSafe(48)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(24)
        val nonce = randomUrlSafe(24)
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH"
        val savedClient = prefs.chatgptClientId
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to if (savedClient.isBlank()) DYNAMIC_CLIENT_ID else savedClient,
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "ext_agent_host_id" to hostId(prefs),
        )
        if (savedClient.isBlank()) params["agent_name_hint"] = agentName.ifBlank { "PrimalSpace" }
        if (!prefs.chatgptIdToken.isNullOrBlank()) params["id_token_hint"] = prefs.chatgptIdToken
        if (!prefs.chatgptEmail.isBlank()) params["login_hint"] = prefs.chatgptEmail
        val url = AUTHORIZE_URL + "?" + params.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        }
        return Authorization(url, verifier, state, nonce, redirectUri)
    }

    /**
     * Waits for the loopback callback, then exchanges the code for tokens.
     */
    fun completeLogin(prefs: Prefs, authorization: Authorization, server: ServerSocket, timeoutSeconds: Int = 300): Session {
        val latch = CountDownLatch(1)
        var code: String? = null
        var returnedState: String? = null
        var issuedClient: String? = null
        var error: String? = null
        val thread = Thread {
            try {
                server.soTimeout = timeoutSeconds * 1000
                val socket = server.accept()
                socket.getInputStream().bufferedReader().use { reader ->
                    val requestLine = reader.readLine() ?: ""
                    val path = requestLine.split(" ").getOrNull(1) ?: ""
                    val query = path.substringAfter("?", "")
                    val parsed = android.net.Uri.parse("http://127.0.0.1/?$query")
                    code = parsed.getQueryParameter("code")
                    returnedState = parsed.getQueryParameter("state")
                    issuedClient = parsed.getQueryParameter("client_id")
                    error = parsed.getQueryParameter("error")
                }
                socket.getOutputStream().use { output ->
                    val body = "<html><body><h3>PrimalSpace</h3><p>Sign in complete. You can close this tab.</p></body></html>"
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    output.write(response.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (error: Exception) {
                error = error.message
            } finally {
                latch.countDown()
            }
        }
        thread.isDaemon = true
        thread.start()
        if (!latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            throw OAuthException("timed out waiting for the browser sign-in")
        }
        if (error != null) throw OAuthException("authorization failed: $error")
        val authorizationCode = code ?: throw OAuthException("authorization response had no code")
        if (returnedState != authorization.state) throw OAuthException("authorization state mismatch")
        val clientId = issuedClient?.takeIf { it.isNotBlank() } ?: prefs.chatgptClientId.takeIf { it.isNotBlank() }
            ?: throw OAuthException("authorization response had no client id")
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to authorizationCode,
            "code_verifier" to authorization.verifier,
            "redirect_uri" to authorization.redirectUri,
            "resource" to RESOURCE,
        )
        val token = postForm(TOKEN_URL, fields)
        val granted = token.optString("scope").split(" ").filter { it.isNotBlank() }
        if (!granted.contains("chatgpt.tokens.use.direct")) {
            throw OAuthException("the account did not grant ChatGPT plan usage")
        }
        val claims = validateIdToken(token.optString("id_token"), clientId, authorization.nonce)
        val expiresIn = token.optLong("expires_in", 3600L)
        return Session(
            clientId = clientId,
            hostId = hostId(prefs),
            idToken = token.optString("id_token").ifBlank { null },
            accessToken = token.getString("access_token"),
            refreshToken = token.optString("refresh_token").ifBlank { null },
            email = claims.optString("email"),
            scopes = granted,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    fun saveSession(prefs: Prefs, session: Session) {
        prefs.chatgptClientId = session.clientId
        prefs.chatgptAccessToken = session.accessToken
        prefs.chatgptRefreshToken = session.refreshToken ?: ""
        prefs.chatgptIdToken = session.idToken ?: ""
        prefs.chatgptEmail = session.email
        prefs.chatgptScopes = session.scopes.joinToString(" ")
        prefs.chatgptExpiresAt = session.expiresAt
    }

    fun session(prefs: Prefs): Session? {
        if (prefs.chatgptAccessToken.isBlank()) return null
        return Session(
            clientId = prefs.chatgptClientId,
            hostId = hostId(prefs),
            idToken = prefs.chatgptIdToken.ifBlank { null },
            accessToken = prefs.chatgptAccessToken,
            refreshToken = prefs.chatgptRefreshToken.ifBlank { null },
            email = prefs.chatgptEmail,
            scopes = prefs.chatgptScopes.split(" ").filter { it.isNotBlank() },
            expiresAt = prefs.chatgptExpiresAt,
        )
    }

    fun accessToken(prefs: Prefs): String {
        val current = session(prefs) ?: throw OAuthException("not signed in with ChatGPT")
        if (current.expiresAt - System.currentTimeMillis() > 120000L) return current.accessToken
        return refresh(prefs).accessToken
    }

    fun refresh(prefs: Prefs): Session {
        val current = session(prefs) ?: throw OAuthException("not signed in with ChatGPT")
        val refreshToken = current.refreshToken ?: throw OAuthException("the saved session has no refresh token")
        val fields = linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to current.clientId,
            "refresh_token" to refreshToken,
            "resource" to RESOURCE,
        )
        val token = postForm(TOKEN_URL, fields)
        val granted = token.optString("scope").split(" ").filter { it.isNotBlank() }
        val updated = current.copy(
            accessToken = token.optString("access_token").ifBlank { current.accessToken },
            refreshToken = token.optString("refresh_token").ifBlank { refreshToken },
            idToken = token.optString("id_token").ifBlank { current.idToken },
            scopes = if (granted.isEmpty()) current.scopes else granted,
            expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L,
        )
        saveSession(prefs, updated)
        return updated
    }

    fun signOut(prefs: Prefs) {
        prefs.edit().remove("chatgpt_client_id").remove("chatgpt_access_token")
            .remove("chatgpt_refresh_token").remove("chatgpt_id_token").remove("chatgpt_email")
            .remove("chatgpt_scopes").remove("chatgpt_expires_at").apply()
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 30000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        }
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw OAuthException("token request failed ($code): ${text.take(300)}")
        return JSONObject(text)
    }

    private fun validateIdToken(idToken: String, audience: String, nonce: String): JSONObject {
        if (idToken.isBlank()) throw OAuthException("token response did not include an id token")
        val parts = idToken.split(".")
        if (parts.size != 3) throw OAuthException("id token is malformed")
        val header = JSONObject(decodeSegment(parts[0]))
        val claims = JSONObject(decodeSegment(parts[1]))
        if (header.optString("alg") != "RS256") throw OAuthException("unsupported id token algorithm")
        val keyset = JSONObject(fetch(JWKS_URL, null))
        val keys = keyset.optJSONArray("keys") ?: org.json.JSONArray()
        var jwk: JSONObject? = null
        for (index in 0 until keys.length()) {
            val candidate = keys.getJSONObject(index)
            val kid = header.optString("kid")
            if (kid.isNotBlank() && candidate.optString("kid") != kid) continue
            jwk = candidate
            break
        }
        val selected = jwk ?: throw OAuthException("no matching signing key for the id token")
        val modulus = java.math.BigInteger(1, Base64.decode(selected.getString("n"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val exponent = java.math.BigInteger(1, Base64.decode(selected.getString("e"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        val raw = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if (!signature.verify(raw)) throw OAuthException("id token signature verification failed")
        if (claims.optString("iss") != ISSUER) throw OAuthException("id token issuer is not OpenAI")
        if (claims.optString("aud") != audience) throw OAuthException("id token audience mismatch")
        if (claims.optLong("exp") * 1000 < System.currentTimeMillis()) throw OAuthException("id token has expired")
        if (claims.optString("nonce") != nonce) throw OAuthException("id token nonce mismatch")
        return claims
    }

    private fun decodeSegment(segment: String): String {
        val padded = segment + "=".repeat((4 - segment.length % 4) % 4)
        return String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }

    private fun fetch(url: String, bearer: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw OAuthException("request failed ($code): ${text.take(200)}")
        return text
    }

    fun listModels(token: String): List<String> {
        val payload = JSONObject(fetch("$API_BASE/models", token))
        val models = payload.optJSONArray("models") ?: payload.optJSONArray("data") ?: org.json.JSONArray()
        val result = mutableListOf<String>()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val visibility = model.optString("visibility")
            if (visibility.isNotBlank() && visibility != "list") continue
            val slug = model.optString("slug").ifBlank { model.optString("id") }
            if (slug.isNotBlank()) result.add(slug)
        }
        return result
    }

    data class Completion(val text: String, val toolCalls: List<JSONObject>)

    fun responses(
        token: String,
        model: String,
        instructions: String,
        input: List<JSONObject>,
        tools: List<JSONObject>,
    ): Completion {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", org.json.JSONArray(input))
            .put("store", false)
            .put("stream", true)
        if (tools.isNotEmpty()) body.put("tools", org.json.JSONArray(tools))
        val connection = URL("$API_BASE/responses").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 180000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw OAuthException("responses request failed ($code): ${text.take(300)}")
        }
        val text = StringBuilder()
        val calls = mutableListOf<JSONObject>()
        var completed = false
        connection.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        try {
                            val event = JSONObject(payload)
                            when (event.optString("type")) {
                                "response.output_text.delta" -> text.append(event.optString("delta"))
                                "response.output_item.done" -> {
                                    val item = event.optJSONObject("item")
                                    if (item != null && item.optString("type") == "function_call") calls.add(item)
                                }
                                "response.failed" -> {
                                    val failure = event.optJSONObject("response")?.optJSONObject("error")
                                    throw OAuthException("response failed: ${failure?.optString("code") ?: "unknown"}")
                                }
                                "response.completed" -> completed = true
                            }
                        } catch (error: org.json.JSONException) {
                            // ignore keep-alive frames
                        }
                    }
                }
                line = reader.readLine()
            }
        }
        connection.disconnect()
        if (!completed) throw OAuthException("response stream ended without response.completed")
        return Completion(text.toString().trim(), calls)
    }
}
