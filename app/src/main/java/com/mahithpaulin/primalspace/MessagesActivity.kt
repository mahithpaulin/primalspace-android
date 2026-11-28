package com.mahithpaulin.primalspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MessagesActivity : AppCompatActivity() {
    private data class Conversation(
        val key: String,
        val target: String?,
        val title: String,
        val avatar: String,
        val subtitle: String,
        val groupId: String?,
        val isGroup: Boolean,
        val online: Boolean,
        val memberNames: Map<String, String>,
        var lastAt: String = "",
        var preview: String = "",
        var count: Int = 0,
    )

    private lateinit var prefs: Prefs
    private lateinit var chatListPanel: LinearLayout
    private lateinit var threadPanel: LinearLayout
    private lateinit var conversationList: LinearLayout
    private lateinit var messageList: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var search: EditText
    private lateinit var syncStatus: TextView
    private lateinit var selfAvatar: TextView
    private lateinit var threadAvatar: TextView
    private lateinit var threadName: TextView
    private lateinit var threadSubtitle: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private val peers = mutableMapOf<String, JSONObject>()
    private val groups = mutableMapOf<String, JSONObject>()
    private var conversations = listOf<Conversation>()
    private var active: Conversation? = null
    private var loading = false
    private var lastInboxVersion = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val threadTicker = object : Runnable {
        override fun run() {
            if (threadPanel.visibility == View.VISIBLE && prefs.inboxVersion != lastInboxVersion) {
                renderThread()
            }
            handler.postDelayed(this, 2500)
        }
    }
    private val syncTicker = object : Runnable {
        override fun run() {
            refreshFromServer(false)
            handler.postDelayed(this, 12000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_messages)
        Ui.paintSystemBars(this, Color.rgb(11, 20, 26))

        chatListPanel = findViewById(R.id.chat_list_panel)
        threadPanel = findViewById(R.id.thread_panel)
        conversationList = findViewById(R.id.conversation_list)
        messageList = findViewById(R.id.message_list)
        messageScroll = findViewById(R.id.message_scroll)
        search = findViewById(R.id.conversation_search)
        syncStatus = findViewById(R.id.messages_sync_status)
        selfAvatar = findViewById(R.id.messages_self_avatar)
        threadAvatar = findViewById(R.id.thread_avatar)
        threadName = findViewById(R.id.thread_name)
        threadSubtitle = findViewById(R.id.thread_subtitle)
        messageInput = findViewById(R.id.message_body)
        sendButton = findViewById(R.id.message_send)

        AppNavigation.bind(this, "MessagesActivity")
        findViewById<ImageButton>(R.id.thread_back).setOnClickListener { closeThread() }
        findViewById<TextView>(R.id.messages_new_group).setOnClickListener { newGroup() }
        sendButton.setOnClickListener { send() }
        search.doAfterTextChanged { renderConversations() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (threadPanel.visibility == View.VISIBLE) {
                    closeThread()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        selfAvatar.text = prefs.profileAvatar.ifBlank {
            Ui.initials(prefs.profileName.ifBlank { prefs.deviceName }, "PS")
        }
        buildConversations()
        renderConversations()
        handler.removeCallbacks(threadTicker)
        handler.removeCallbacks(syncTicker)
        handler.postDelayed(threadTicker, 1500)
        handler.postDelayed(syncTicker, 500)
    }

    override fun onPause() {
        handler.removeCallbacks(threadTicker)
        handler.removeCallbacks(syncTicker)
        super.onPause()
    }

    private fun refreshFromServer(showStatus: Boolean) {
        if (!prefs.isRegistered()) {
            syncStatus.text = "Connect this device from Home to start messaging."
            renderConversations()
            return
        }
        if (loading) return
        loading = true
        if (showStatus) syncStatus.text = "Syncing conversations…"
        Thread {
            val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
            val peerResult = runCatching { client.getPeers() }
            val groupResult = runCatching { client.getGroups() }
            val messageResult = runCatching { client.getMessages(null, 100) }
            val peersJson = peerResult.getOrNull() ?: JSONArray()
            val groupsJson = groupResult.getOrNull() ?: JSONArray()
            val messagesJson = messageResult.getOrNull() ?: JSONArray()
            for (index in 0 until messagesJson.length()) {
                messagesJson.optJSONObject(index)?.let { prefs.addMessage(it) }
            }
            val error = peerResult.exceptionOrNull()?.message
                ?: groupResult.exceptionOrNull()?.message
                ?: messageResult.exceptionOrNull()?.message
            runOnUiThread {
                loading = false
                if (isFinishing) return@runOnUiThread
                peers.clear()
                groups.clear()
                for (index in 0 until peersJson.length()) {
                    val peer = peersJson.optJSONObject(index) ?: continue
                    val id = peer.optString("id")
                    if (id.isNotBlank()) peers[id] = peer
                }
                for (index in 0 until groupsJson.length()) {
                    val group = groupsJson.optJSONObject(index) ?: continue
                    val id = group.optString("id")
                    if (id.isNotBlank()) groups[id] = group
                }
                syncStatus.text = if (error != null) {
                    "Offline · $error"
                } else {
                    "Connected · ${peers.size} peers · ${groups.size} groups"
                }
                buildConversations()
                if (threadPanel.visibility == View.VISIBLE) renderThread() else renderConversations()
            }
        }.start()
    }

    private fun buildConversations() {
        val map = LinkedHashMap<String, Conversation>()
        map["community"] = Conversation(
            key = "community",
            target = null,
            title = "PrimalSpace",
            avatar = "PS",
            subtitle = "Community broadcasts",
            groupId = null,
            isGroup = false,
            online = true,
            memberNames = emptyMap(),
        )
        for ((id, peer) in peers) {
            val profile = peer.optJSONObject("profile")
            val name = profile?.optString("name").orEmpty().ifBlank {
                peer.optString("name", "Device ${id.take(6)}")
            }
            val avatar = profile?.optString("avatar").orEmpty().ifBlank { Ui.initials(name, "?") }
            val status = peer.optString("status", "offline")
            map["direct:$id"] = Conversation(
                key = "direct:$id",
                target = id,
                title = name,
                avatar = avatar,
                subtitle = status,
                groupId = null,
                isGroup = false,
                online = status == "online",
                memberNames = emptyMap(),
            )
        }
        for ((id, group) in groups) {
            val name = group.optString("name", "Group chat")
            val members = group.optJSONArray("members") ?: JSONArray()
            val memberNames = mutableMapOf<String, String>()
            var online = 0
            for (index in 0 until members.length()) {
                val member = members.optJSONObject(index) ?: continue
                val memberId = member.optString("id")
                if (memberId.isBlank()) continue
                val memberName = member.optString("name").ifBlank { "Device ${memberId.take(6)}" }
                memberNames[memberId] = memberName
                if (member.optBoolean("online")) online++
            }
            val subtitle = "${memberNames.size} members" + if (online > 0) " · $online online" else ""
            map["group:$id"] = Conversation(
                key = "group:$id",
                target = "group:$id",
                title = name,
                avatar = Ui.initials(name, "G"),
                subtitle = subtitle,
                groupId = id,
                isGroup = true,
                online = online > 0,
                memberNames = memberNames,
            )
        }
        val inbox = prefs.inbox()
        for (index in 0 until inbox.length()) {
            val message = inbox.optJSONObject(index) ?: continue
            if (message.optString("topic") == "credit") continue
            val key = conversationKey(message)
            val conversation = map[key] ?: fallbackConversation(key)
            if (map[key] == null) map[key] = conversation
            val from = message.optString("from")
            val mine = from == prefs.deviceId
            val body = message.optString("body")
            val sender = if (conversation.isGroup && !mine) {
                conversation.memberNames[from] ?: if (from == "operator") "PrimalSpace" else from.take(6)
            } else {
                ""
            }
            conversation.count++
            val at = message.optString("created_at")
            if (at >= conversation.lastAt) {
                conversation.lastAt = at
                conversation.preview = if (sender.isNotBlank()) "$sender: $body" else body
            }
        }
        conversations = map.values.sortedWith(
            compareByDescending<Conversation> { it.key == "community" }
                .thenByDescending { it.lastAt }
                .thenBy { it.title.lowercase() },
        )
        active = conversations.firstOrNull { it.key == active?.key } ?: active
    }

    private fun conversationKey(message: JSONObject): String {
        val groupId = message.optString("group_id")
        if (groupId.isNotBlank()) return "group:$groupId"
        val from = message.optString("from")
        if (from == "operator") return "community"
        if (from == prefs.deviceId) {
            val to = if (message.isNull("to")) "" else message.optString("to")
            return if (to.isBlank()) "community" else "direct:$to"
        }
        return "direct:$from"
    }

    private fun fallbackConversation(key: String): Conversation {
        val id = key.substringAfter(':', key)
        return if (key.startsWith("group:")) {
            Conversation(
                key = key,
                target = key,
                title = "Group ${id.take(6)}",
                avatar = "G",
                subtitle = "Group chat",
                groupId = id,
                isGroup = true,
                online = false,
                memberNames = emptyMap(),
            )
        } else {
            Conversation(
                key = key,
                target = id,
                title = "Device ${id.take(6)}",
                avatar = "?",
                subtitle = "Peer",
                groupId = null,
                isGroup = false,
                online = false,
                memberNames = emptyMap(),
            )
        }
    }

    private fun renderConversations() {
        conversationList.removeAllViews()
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = conversations.filter {
            query.isBlank() || it.title.lowercase(Locale.getDefault()).contains(query) ||
                it.preview.lowercase(Locale.getDefault()).contains(query)
        }
        if (filtered.isEmpty()) {
            conversationList.addView(emptyState("No conversations yet.\nPeers appear here as they join the network."))
            return
        }
        if (query.isBlank()) {
            val pinned = filtered.filter { it.key == "community" }
            val rest = filtered.filter { it.key != "community" }
            if (pinned.isNotEmpty()) {
                conversationList.addView(sectionLabel("PINNED"))
                pinned.forEach { addConversation(it) }
            }
            if (rest.isNotEmpty()) {
                conversationList.addView(sectionLabel("CONVERSATIONS"))
                rest.forEach { addConversation(it) }
            }
        } else {
            filtered.forEach { addConversation(it) }
        }
    }

    private fun addConversation(conversation: Conversation) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MessagesActivity, 14), Ui.dp(this@MessagesActivity, 11), Ui.dp(this@MessagesActivity, 14), Ui.dp(this@MessagesActivity, 11))
            background = Ui.ripple(this@MessagesActivity, Color.TRANSPARENT, 0f)
            isClickable = true
            isFocusable = true
            setOnClickListener { openConversation(conversation) }
        }

        val avatarWrap = FrameLayout(this)
        avatarWrap.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54))
        val avatar = TextView(this).apply {
            text = conversation.avatar
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            if (conversation.key == "community") {
                setBackgroundResource(R.drawable.bg_avatar_primal)
                setTextColor(Color.rgb(11, 20, 26))
            } else {
                background = Ui.oval(Ui.avatarColor(conversation.key))
                setTextColor(Color.WHITE)
            }
        }
        avatarWrap.addView(avatar, FrameLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50), Gravity.CENTER))
        if (conversation.online) {
            val dot = View(this).apply { background = Ui.oval(Color.rgb(37, 211, 102)) }
            val dotParams = FrameLayout.LayoutParams(Ui.dp(this, 11), Ui.dp(this, 11), Gravity.END or Gravity.TOP)
            dotParams.setMargins(0, Ui.dp(this, 2), Ui.dp(this, 2), 0)
            avatarWrap.addView(dot, dotParams)
        }
        row.addView(avatarWrap)

        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(this@MessagesActivity, 12)
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = conversation.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(233, 237, 239))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (conversation.isGroup) {
            titleRow.addView(TextView(this).apply {
                text = "GROUP"
                textSize = 8f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(37, 211, 102))
                background = Ui.rounded(this@MessagesActivity, Color.rgb(30, 58, 50), 99f)
                setPadding(Ui.dp(this@MessagesActivity, 6), Ui.dp(this@MessagesActivity, 2), Ui.dp(this@MessagesActivity, 6), Ui.dp(this@MessagesActivity, 2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.dp(this@MessagesActivity, 6)
                }
            })
        }
        textWrap.addView(titleRow)
        textWrap.addView(TextView(this).apply {
            text = conversation.preview.ifBlank { "Start a conversation" }
            textSize = 12f
            setTextColor(if (conversation.preview.isBlank()) Color.rgb(90, 106, 114) else Color.rgb(134, 150, 160))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(this@MessagesActivity, 3), 0, 0)
        })
        row.addView(textWrap)

        val time = TextView(this).apply {
            text = conversationTime(conversation.lastAt)
            textSize = 10f
            setTextColor(Color.rgb(102, 119, 129))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(this@MessagesActivity, 8)
            }
        }
        row.addView(time)
        conversationList.addView(row)
        conversationList.addView(View(this).apply {
            setBackgroundColor(Color.rgb(31, 44, 51))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = Ui.dp(this@MessagesActivity, 80)
            }
        })
    }

    private fun sectionLabel(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10f
            letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(134, 150, 160))
            setPadding(Ui.dp(this@MessagesActivity, 18), Ui.dp(this@MessagesActivity, 14), 0, Ui.dp(this@MessagesActivity, 4))
        }
    }

    private fun emptyState(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.rgb(134, 150, 160))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MessagesActivity, 24), Ui.dp(this@MessagesActivity, 40), Ui.dp(this@MessagesActivity, 24), Ui.dp(this@MessagesActivity, 40))
        }
    }

    private fun openConversation(conversation: Conversation) {
        active = conversation
        lastInboxVersion = prefs.inboxVersion
        chatListPanel.visibility = View.GONE
        threadPanel.visibility = View.VISIBLE
        threadName.text = conversation.title
        threadSubtitle.text = when {
            conversation.key == "community" -> "Community broadcasts"
            conversation.isGroup -> conversation.subtitle
            else -> conversation.subtitle
        }
        threadAvatar.text = conversation.avatar
        if (conversation.key == "community") {
            threadAvatar.backgroundTintList = ColorStateList.valueOf(Color.rgb(37, 211, 102))
            threadAvatar.setTextColor(Color.rgb(11, 20, 26))
        } else {
            threadAvatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(conversation.key))
            threadAvatar.setTextColor(Color.WHITE)
        }
        renderThread()
    }

    private fun closeThread() {
        active = null
        threadPanel.visibility = View.GONE
        chatListPanel.visibility = View.VISIBLE
        hideKeyboard()
        renderConversations()
    }

    private fun renderThread() {
        val conversation = active ?: return
        lastInboxVersion = prefs.inboxVersion
        messageList.removeAllViews()
        val inbox = prefs.inbox()
        val messages = mutableListOf<JSONObject>()
        for (index in 0 until inbox.length()) {
            val message = inbox.optJSONObject(index) ?: continue
            if (message.optString("topic") == "credit") continue
            if (conversationKey(message) == conversation.key) messages.add(message)
        }
        messages.sortBy { it.optString("created_at") }
        if (messages.isEmpty()) {
            messageList.addView(emptyState("No messages yet.\nSay hello to ${conversation.title}."))
            return
        }
        var lastDay = ""
        for (message in messages) {
            val created = message.optString("created_at")
            val day = dayLabel(created)
            if (day.isNotBlank() && day != lastDay) {
                messageList.addView(datePill(day))
                lastDay = day
            }
            addBubble(message, conversation)
        }
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(message: JSONObject, conversation: Conversation) {
        val mine = message.optString("from") == prefs.deviceId
        val credit = message.optString("topic") == "credit"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, Ui.dp(this@MessagesActivity, 2), 0, Ui.dp(this@MessagesActivity, 2))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MessagesActivity, 11), Ui.dp(this@MessagesActivity, 7), Ui.dp(this@MessagesActivity, 11), Ui.dp(this@MessagesActivity, 6))
            background = Ui.bubble(this@MessagesActivity, mine, credit)
        }
        if (conversation.isGroup && !mine) {
            val from = message.optString("from")
            val sender = conversation.memberNames[from]
                ?: if (from == "operator") "PrimalSpace" else "Device ${from.take(6)}"
            bubble.addView(TextView(this).apply {
                text = sender
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(201, 243, 107))
                setPadding(0, 0, 0, Ui.dp(this@MessagesActivity, 2))
            })
        }
        bubble.addView(TextView(this).apply {
            text = message.optString("body")
            textSize = 14.5f
            setTextColor(if (credit) Color.rgb(255, 222, 141) else Color.rgb(233, 237, 239))
            maxWidth = (resources.displayMetrics.widthPixels * 0.74).toInt()
            setLineSpacing(Ui.dp(this@MessagesActivity, 2).toFloat(), 1f)
        })
        bubble.addView(TextView(this).apply {
            text = clockLabel(message.optString("created_at"))
            textSize = 9f
            setTextColor(if (mine) Color.rgb(153, 201, 184) else Color.rgb(134, 150, 160))
            gravity = Gravity.END
            setPadding(0, Ui.dp(this@MessagesActivity, 2), 0, 0)
        })
        row.addView(bubble)
        messageList.addView(row)
    }

    private fun datePill(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10f
            setTextColor(Color.rgb(134, 150, 160))
            background = Ui.rounded(this@MessagesActivity, Color.rgb(24, 34, 41), 99f)
            setPadding(Ui.dp(this@MessagesActivity, 12), Ui.dp(this@MessagesActivity, 4), Ui.dp(this@MessagesActivity, 12), Ui.dp(this@MessagesActivity, 4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(this@MessagesActivity, 10)
                bottomMargin = Ui.dp(this@MessagesActivity, 6)
            }
        }
    }

    private fun send() {
        val conversation = active ?: return
        if (!prefs.isRegistered()) {
            syncStatus.text = "Connect this device from Home first."
            return
        }
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return
        sendButton.isEnabled = false
        syncStatus.text = "Sending…"
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val response = client.sendMessage(conversation.target, body)
                val echo = JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("from", prefs.deviceId)
                    .put("topic", "chat")
                    .put("body", body)
                    .put("created_at", isoNow())
                if (conversation.target == null) {
                    echo.put("to", JSONObject.NULL)
                } else {
                    echo.put("to", conversation.target)
                }
                if (conversation.groupId != null) echo.put("group_id", conversation.groupId)
                if (conversation.target == null) {
                    val serverMessage = response.optJSONObject("message")
                    prefs.addMessage(serverMessage ?: echo)
                } else {
                    prefs.addMessage(echo)
                }
                runOnUiThread {
                    sendButton.isEnabled = true
                    messageInput.setText("")
                    syncStatus.text = "Connected · message sent"
                    buildConversations()
                    renderThread()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    sendButton.isEnabled = true
                    syncStatus.text = error.message ?: "Could not send message."
                }
            }
        }.start()
    }

    private fun newGroup() {
        if (peers.isEmpty()) {
            Toast.makeText(this, "No peers available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val peerList = peers.values.toList()
        val names = peerList.map { peer ->
            peer.optJSONObject("profile")?.optString("name").orEmpty().ifBlank {
                peer.optString("name", "Device ${peer.optString("id").take(6)}")
            }
        }.toTypedArray()
        val checked = BooleanArray(peerList.size)
        AlertDialog.Builder(this)
            .setTitle("New group")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Next") { _, _ ->
                val memberIds = peerList.filterIndexed { index, _ -> checked[index] }
                    .map { it.optString("id") }
                    .filter { it.isNotBlank() }
                if (memberIds.isEmpty()) {
                    Toast.makeText(this, "Choose at least one peer", Toast.LENGTH_SHORT).show()
                } else {
                    promptGroupName(memberIds)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptGroupName(memberIds: List<String>) {
        val input = EditText(this).apply {
            hint = "Group name"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.rgb(233, 237, 239))
            setHintTextColor(Color.rgb(134, 150, 160))
            background = Ui.rounded(this@MessagesActivity, Color.rgb(32, 44, 51), 14f)
            setPadding(Ui.dp(this@MessagesActivity, 14), Ui.dp(this@MessagesActivity, 10), Ui.dp(this@MessagesActivity, 14), Ui.dp(this@MessagesActivity, 10))
        }
        AlertDialog.Builder(this)
            .setTitle("Name this group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                createGroup(input.text.toString().trim().ifBlank { "Group chat" }, memberIds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createGroup(name: String, memberIds: List<String>) {
        syncStatus.text = "Creating group…"
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                client.createGroup(name, memberIds)
                runOnUiThread {
                    syncStatus.text = "Group created"
                    refreshFromServer(false)
                }
            } catch (error: Exception) {
                runOnUiThread { syncStatus.text = error.message ?: "Could not create group." }
            }
        }.start()
    }

    private fun conversationTime(value: String): String {
        val date = parseIso(value) ?: return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        return when {
            sameDay(now, then) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            isYesterday(now, then) -> "Yesterday"
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
                SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        }
    }

    private fun dayLabel(value: String): String {
        val date = parseIso(value) ?: return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        return when {
            sameDay(now, then) -> "Today"
            isYesterday(now, then) -> "Yesterday"
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
                SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        }
    }

    private fun clockLabel(value: String): String {
        val date = parseIso(value) ?: return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }

    private fun parseIso(value: String): Date? {
        if (value.isBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, then: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            time = now.time
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return sameDay(yesterday, then)
    }

    private fun isoNow(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(messageInput.windowToken, 0)
    }
}
