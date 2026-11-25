package com.mahithpaulin.primalspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.ServerSocket

class AgentActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var groups: TextView
    private lateinit var nameInput: EditText
    private lateinit var modelInput: EditText
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)
        prefs = Prefs(this)
        status = findViewById(R.id.agent_status)
        groups = findViewById(R.id.agent_groups)
        nameInput = findViewById(R.id.agent_name)
        modelInput = findViewById(R.id.agent_model)
        findViewById<Button>(R.id.agent_save).setOnClickListener { save() }
        findViewById<Button>(R.id.agent_login).setOnClickListener { login() }
        findViewById<Button>(R.id.agent_signout).setOnClickListener {
            ChatGptOAuth.signOut(prefs)
            refresh()
        }
        findViewById<Button>(R.id.agent_start).setOnClickListener { startAgent() }
        findViewById<Button>(R.id.agent_stop).setOnClickListener { stopAgent() }
        findViewById<Button>(R.id.agent_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.close_agent).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun save() {
        prefs.agentName = nameInput.text.toString().trim()
        prefs.agentModel = modelInput.text.toString().trim()
        refresh()
    }

    private fun startAgent() {
        if (prefs.chatgptAccessToken.isBlank()) {
            status.text = "Sign in with ChatGPT first."
            return
        }
        prefs.agentEnabled = true
        val intent = Intent(this, AgentService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        refresh()
    }

    private fun stopAgent() {
        stopService(Intent(this, AgentService::class.java))
        prefs.agentEnabled = false
        refresh()
    }

    private fun login() {
        status.text = "Opening the ChatGPT sign-in page..."
        Thread({
            try {
                val authorization = ChatGptOAuth.authorizationRequest(prefs, prefs.agentName.ifBlank { "PrimalSpace" })
                val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
                main.post {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.url)))
                    }.onFailure {
                        status.text = "Could not open a browser: ${it.message}"
                    }
                }
                val session = ChatGptOAuth.completeLogin(prefs, authorization, server)
                ChatGptOAuth.saveSession(prefs, session)
                main.post {
                    status.text = "Signed in as ${session.email.ifBlank { "ChatGPT account" }}"
                    refresh()
                }
            } catch (error: Exception) {
                main.post { status.text = "Sign-in failed: ${error.message}" }
            }
        }, "primal-agent-login").start()
    }

    private fun refresh() {
        nameInput.setText(prefs.agentName)
        modelInput.setText(prefs.agentModel)
        val signedIn = prefs.chatgptAccessToken.isNotBlank()
        val lines = mutableListOf<String>()
        lines.add(if (signedIn) "ChatGPT connected as ${prefs.chatgptEmail.ifBlank { "unknown account" }}" else "Not signed in with ChatGPT")
        lines.add("Client id: ${prefs.chatgptClientId.ifBlank { "-" }}")
        lines.add("Host id: ${prefs.agentHostId.ifBlank { "-" }}")
        lines.add("Agent enabled: ${if (prefs.agentEnabled) "yes" else "no"}")
        lines.add("Replies sent: ${prefs.agentReplies} · last run: ${prefs.agentLastRun.ifBlank { "never" }}")
        status.text = lines.joinToString("\n")
        Thread({
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val groupsJson = client.getGroups()
                val builder = StringBuilder()
                if (groupsJson.length() == 0) {
                    builder.append("No group chats yet.")
                } else {
                    for (index in 0 until groupsJson.length()) {
                        val group = groupsJson.optJSONObject(index) ?: continue
                        builder.append(group.optString("name")).append("  ·  ")
                        val members = group.optJSONArray("members") ?: org.json.JSONArray()
                        val names = mutableListOf<String>()
                        for (memberIndex in 0 until members.length()) {
                            names.add(members.optJSONObject(memberIndex)?.optString("name").orEmpty())
                        }
                        builder.append(names.joinToString(", ")).append('\n')
                    }
                }
                main.post { groups.text = builder.toString() }
            } catch (error: Exception) {
                main.post { groups.text = "Could not load groups: ${error.message}" }
            }
        }, "primal-agent-groups").start()
    }
}
