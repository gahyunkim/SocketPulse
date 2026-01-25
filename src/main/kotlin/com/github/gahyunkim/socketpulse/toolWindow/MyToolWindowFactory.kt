package com.github.gahyunkim.socketpulse.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

data class EventSpec(
    val name: String,
    val direction: String? = null, // "S2C", "C2S", etc
    val desc: String? = null
) {
    override fun toString(): String {
        val dir = direction?.let { "[$it] " } ?: ""
        val d = desc?.let { " - $it" } ?: ""
        return "$dir$name$d"
    }
}

// 1. 이벤트 데이터를 담을 클래스 추가
data class SocketLogEntry(
    val timestamp: Long,
    val message: String
)

// 2. Factory 클래스 내부에 저장소 추가
private val allLogs = mutableListOf<SocketLogEntry>()

class MyToolWindowFactory : ToolWindowFactory {
    private val timelineStore = EventTimelineStore(windowMs = 30_000L)
    private var selectedEventForTimeline: String? = null
    private val byteRateStore = ByteRateStore(windowSec = 30)
    private var chartTimer: javax.swing.Timer? = null

    private lateinit var searchField: JBTextField
    private lateinit var caseSensitiveCheck: javax.swing.JCheckBox
    private lateinit var regexCheck: javax.swing.JCheckBox

    private var lastQuery: String = ""
    private var lastMatchIndex: Int = -1

    private var monitorServer: com.sun.net.httpserver.HttpServer? = null

    private var socket: Socket? = null

    // 중복 등록 방지용: 현재 소켓에 등록된 커스텀 이벤트들
    private val subscribedEvents = ConcurrentHashMap.newKeySet<String>()

    // UI 모델
    private val eventListModel = DefaultListModel<EventSpec>()

    // 로그 영역
    private lateinit var logArea: JTextArea
    private lateinit var statusLabel: JBLabel
    private lateinit var connectBtn: JButton
    private lateinit var urlField: JBTextField

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var isMonitoring = false

    private fun startMonitoringServer(port: Int = 9999) {
        try {
            // 이미 서버가 돌고 있다면 닫고 새로 시작 (중복 방지)
            monitorServer?.stop(0)

            monitorServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0).apply {
                createContext("/log") { exchange ->
                    if ("POST" == exchange.requestMethod) {
                        val body = exchange.requestBody.bufferedReader().readText()
                        val json = JSONObject(body)
                        val eventName = json.optString("event", "unknown")
                        val dataStr = json.optString("data", "{}")

                        // [알림 로직] 기존 리스트에 없는 새 이벤트인가?
                        val isNewEvent = eventListModel.elements().asSequence().none { it.name == eventName }
                        if (isNewEvent) {
                            SwingUtilities.invokeLater {
                                // 새 이벤트면 리스트에 추가하고 하이라이트
                                eventListModel.addElement(EventSpec(eventName, "S2C (Detected)"))
                                appendLog("✨ NEW EVENT DETECTED: <$eventName>")
                                // 여기서 OS 알림이나 IntelliJ Notification을 띄울 수도 있습니다.
                            }
                        }

                        // 로그 및 차트 업데이트
                        byteRateStore.addRx(body.toByteArray().size.toLong())
                        timelineStore.record(eventName)

                        SwingUtilities.invokeLater {
                            appendLog("[APP->MONITOR] <$eventName> : $dataStr")
                        }

                        exchange.sendResponseHeaders(200, 0)
                    }
                    exchange.close()
                }
                executor = null
                start()
            }
            appendLog("SYSTEM: 모니터링 서버가 $port 포트에서 시작되었습니다.")
        } catch (e: Exception) {
            appendLog("ERROR: 서버 시작 실패 - ${e.message}")
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        urlField = JBTextField("http://localhost:3000", 15)
        connectBtn = JButton("Connect")
        logArea = JTextArea()
        statusLabel = JBLabel("State: Waiting for connect")
        searchField = JBTextField("", 22)
        caseSensitiveCheck = javax.swing.JCheckBox("Aa", false)
        regexCheck = javax.swing.JCheckBox("Regex", false)

        val root = JBPanel<JBPanel<*>>(BorderLayout())
        root.border = JBUI.Borders.empty(10)

        // 상단: 연결 패널
        val topPanel = JBPanel<JBPanel<*>>()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(buildConnectPanel())
        val statusPanel = buildStatusPanel().apply {
            border = JBUI.Borders.emptyTop(8)
        }
        topPanel.add(statusPanel)
        // 중앙: 이벤트/로그
        val centerPanel = JBPanel<JBPanel<*>>()
        centerPanel.layout = BorderLayout(12, 0)
        centerPanel.add(buildEventPanel(), BorderLayout.WEST)
        centerPanel.add(buildLogPanel(), BorderLayout.CENTER)

        root.add(topPanel, BorderLayout.NORTH)
        root.add(centerPanel, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }

    // -------------------------
    // UI builders
    // -------------------------

    private fun buildConnectPanel(): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))

        // 모니터링 시작/중지 버튼
        val monitorBtn = JButton("Start App Monitoring")
        val portField = JBTextField("9999", 4) // 포트 설정

        monitorBtn.addActionListener {
            if (!isMonitoring) {
                val port = portField.text.toIntOrNull() ?: 9999
                startMonitoringServer(port)
                monitorBtn.text = "Stop Monitoring"
                isMonitoring = true
                appendLog("SYSTEM: [$port] 포트에서 앱 데이터를 기다리는 중...")
            } else {
                monitorServer?.stop(0)
                monitorBtn.text = "Start App Monitoring"
                isMonitoring = false
                appendLog("SYSTEM: 모니터링이 중단되었습니다.")
            }
        }

        panel.add(JBLabel("Port:"))
        panel.add(portField)
        panel.add(monitorBtn)

        // 구분선 대용
        panel.add(JBLabel(" | "))

        // 기존 직접 연결용 UI (선택사항으로 유지)
        panel.add(JBLabel("Direct Server:"))
        urlField = JBTextField("http://localhost:3000", 15)
        panel.add(urlField)
        panel.add(connectBtn)

        return panel
    }

    private fun buildStatusPanel(): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        statusLabel = JBLabel("State: Waiting for connect")
        panel.add(statusLabel)
        return panel
    }

    // 로그 필터링 함수
    private fun filterLogsByTime(startMs: Long, endMs: Long) {
        val filtered = allLogs.filter { it.timestamp in startMs..endMs }

        logArea.text = "" // 로그창 비우고
        logArea.append("--- Filtered Results (${timeFmt.format(startMs)} ~ ${timeFmt.format(endMs)}) ---\n")
        filtered.forEach { logArea.append(it.message) }

        // 만약 전체 로그를 다시 보고 싶다면 "전체 보기" 버튼이 필요할 수 있습니다.
    }

    private fun buildEventPanel(): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.preferredSize = Dimension(360, 520)
        panel.add(JBLabel("Events"))
        panel.add(JBLabel(" "))

        // 이벤트 리스트
        val eventList = JList(eventListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
        }

        val throughputChart = ThroughputChart(byteRateStore, windowSec = 30).apply {
            val size = Dimension(360, 150)
            preferredSize = size
            minimumSize = size   // 추가: 최소 크기 보장
            maximumSize = size

            // 범위가 선택되었을 때 실행될 코드
            onRangeSelected = { startMs, endMs ->
                filterLogsByTime(startMs, endMs)
            }
        }
        panel.add(throughputChart)

        val chart = TimelineChart(
            store = timelineStore,
            windowMs = 30_000L,
            rowsProvider = {
                // 1) 선택된 이벤트만 1줄로 보기
                selectedEventForTimeline?.let { listOf(it) }
                // 2) 또는 Top N 이벤트를 row로 보기
                    ?: timelineStore.snapshotTopEvents(topN = 6).map { it.first }
            }
        ).apply {
            preferredSize = Dimension(360, 120)
        }

        chartTimer?.stop()
        chartTimer = javax.swing.Timer(200) {
            throughputChart.repaint()
            chart.repaint()
        }.apply { start() }


        panel.add(chart)
        javax.swing.Timer(50) { chart.repaint() }.start()

        val eventScroll = JBScrollPane(eventList).apply {
            preferredSize = Dimension(360, 240)
        }
        panel.add(eventScroll)

        panel.add(JBLabel(" "))

        // 수동 이벤트 입력 + 구독/해제
        val manualPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        val eventNameField = JBTextField("", 18)
        val subscribeBtn = JButton("Subscribe")
        val unsubscribeBtn = JButton("Unsubscribe")

        manualPanel.add(JBLabel("Event:"))
        manualPanel.add(eventNameField)
        manualPanel.add(subscribeBtn)
        manualPanel.add(unsubscribeBtn)
        panel.add(manualPanel)

        // 리스트 선택 → 입력칸 자동 반영
        eventList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val sel = eventList.selectedValue
                if (sel != null) {
                    eventNameField.text = sel.name
                    selectedEventForTimeline = sel.name
                }
            }
        }

        subscribeBtn.addActionListener {
            val eventName = eventNameField.text.trim()
            if (eventName.isNotBlank()) subscribeToEvent(eventName)
        }

        unsubscribeBtn.addActionListener {
            val eventName = eventNameField.text.trim()
            if (eventName.isNotBlank()) unsubscribeFromEvent(eventName)
        }

        panel.add(JBLabel(" "))

        // 서버 상태 스냅샷/구독을 위한 “권장” 버튼
        val quickPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        val getStateBtn = JButton("Get State")
        val subStateBtn = JButton("Subscribe State")
        val unsubStateBtn = JButton("Unsubscribe State")

        quickPanel.add(getStateBtn)
        quickPanel.add(subStateBtn)
        quickPanel.add(unsubStateBtn)
        panel.add(quickPanel)

        getStateBtn.addActionListener { emitJson("server:state:get", JSONObject()) }
        subStateBtn.addActionListener { emitJson("server:state:subscribe", JSONObject()) }
        unsubStateBtn.addActionListener { emitJson("server:state:unsubscribe", JSONObject()) }

        return panel
    }

    private fun buildLogPanel(): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        val applyFilterBtn = JButton("Apply")
        val resetBtn = JButton("Reset")

        // 상단: 타이틀 + 검색바
        val top = JBPanel<JBPanel<*>>()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

        val titleRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        val titleLabel = JBLabel("Event Log (Click to reset filter)")
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                showAllLogs()
            }
        })
        titleRow.add(titleLabel)
        top.add(titleRow)

        // 검색 Row
        val findRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        findRow.add(JBLabel("Find:"))

        findRow.add(applyFilterBtn)
        findRow.add(resetBtn)

        applyFilterBtn.addActionListener { applyFilter(searchField.text.trim()) }
        resetBtn.addActionListener {
            searchField.text = ""
            clearFilter()
        }


        searchField = JBTextField("", 22)
        val nextBtn = JButton("Next")
        val prevBtn = JButton("Prev")
        val clearFindBtn = JButton("Clear Find")

        caseSensitiveCheck = javax.swing.JCheckBox("Aa", false)
        regexCheck = javax.swing.JCheckBox("Regex", false)

        findRow.add(searchField)
        findRow.add(prevBtn)
        findRow.add(nextBtn)
        findRow.add(caseSensitiveCheck)
        findRow.add(regexCheck)
        findRow.add(clearFindBtn)

        top.add(findRow)

        panel.add(top, BorderLayout.NORTH)

        // 본문: 로그 텍스트
        logArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scroll = JBScrollPane(logArea)
        panel.add(scroll, BorderLayout.CENTER)

        // 하단: Clear 로그
        val bottom = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))
        val clearBtn = JButton("Clear")
        bottom.add(clearBtn)
        clearBtn.addActionListener {
            logArea.text = ""
            allLogs.clear()
            resetSearchState()
        }
        panel.add(bottom, BorderLayout.SOUTH)

        // 검색 액션 바인딩
        nextBtn.addActionListener { findNext() }
        prevBtn.addActionListener { findPrev() }
        clearFindBtn.addActionListener {
            searchField.text = ""
            resetSearchState()
            // 선택 해제
            logArea.select(0, 0)
        }

        // Enter 키로 Next
        searchField.addActionListener { applyFilter(searchField.text.trim()) }

        return panel
    }


    // -------------------------
    // Socket lifecycle
    // -------------------------

    private fun connect(url: String) {
        if (url.isBlank()) {
            setStatus("State: Invalid URL")
            return
        }

        // 기존 소켓 정리 (중복 리스너/이벤트 방지)
        cleanupSocket("Reconnect")

        try {
            setStatus("State: Connecting...")
            appendLog("CONNECT -> $url")

            val newSocket = IO.socket(url)
            socket = newSocket

            newSocket.onAnyIncoming { args ->
                val eventName = if (args.isNotEmpty()) args[0].toString() else "unknown"
                val dataStr = args.joinToStringSafe()

                // 데이터 크기 계산 (UTF-8 바이트 기준)
                val bytes = dataStr.toByteArray(Charsets.UTF_8).size.toLong()

                // 그래프 데이터 저장소에 추가
                byteRateStore.addRx(bytes)

                timelineStore.record(eventName)

                SwingUtilities.invokeLater {
                    appendLog("RECV <$eventName> -> $dataStr")
                }
            }

            newSocket.on("__debug:any__") { args ->
                val obj = args.firstOrNull() as? JSONObject ?: return@on
                val event = obj.optString("event", "(unknown)")
                timelineStore.record(event)

                // RX 바이트 근사 누적
                val bytes = obj.toString().toByteArray(Charsets.UTF_8).size.toLong()
                byteRateStore.addRx(bytes)

                SwingUtilities.invokeLater {
                    appendLog("ANY <$event> -> ${obj.optJSONArray("args")}")
                }
            }

            // 기본 이벤트
            newSocket.on(Socket.EVENT_CONNECT) {
                SwingUtilities.invokeLater {
                    setStatus("State: Socket connected")
                    connectBtn.text = "Reconnect"

                    appendLog("CONNECTED (id=${newSocket.id()})")

                    // 연결되면: 이벤트 목록 요청 + 상태 스냅샷도 한 번 요청(원치 않으면 제거 가능)
                    requestEventList()
                    emitJson("server:state:get", JSONObject())
                }
            }

            newSocket.on(Socket.EVENT_DISCONNECT) { args ->
                SwingUtilities.invokeLater {
                    setStatus("State: Socket disconnected")
                    appendLog("DISCONNECTED: ${args.joinToStringSafe()}")
                }
            }

            newSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                SwingUtilities.invokeLater {
                    setStatus("State: Socket connection error")
                    appendLog("CONNECT_ERROR: ${args.joinToStringSafe()}")
                }
            }

            // 디스커버리 응답(서버가 제공한다는 전제)
            newSocket.on("events:list:result") { args ->
                SwingUtilities.invokeLater {
                    appendLog("RECV events:list:result -> ${args.joinToStringSafe()}")
                    updateEventListFromArgs(args)
                }
            }

            // 권장 상태 이벤트(서버가 제공한다는 전제)
            subscribeToEventInternal("server:state")
            // 필요하면 서버 로그도
            // subscribeToEventInternal("log:line")

            newSocket.connect()
        } catch (e: Exception) {
            setStatus("State: Socket error")
            appendLog("ERROR: ${e.message}")
        }
    }

    private fun disconnect(reason: String) {
        appendLog("DISCONNECT -> $reason")
        cleanupSocket(reason)
        setStatus("State: Waiting for connect")
    }

    private fun cleanupSocket(reason: String) {
        try {
            socket?.let { s ->
                // 등록된 모든 리스너 제거 + 연결 종료
                s.off()
                s.disconnect()
                s.close()
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            socket = null
            subscribedEvents.clear()
            // 이벤트 목록은 유지해도 되고, 재연결 시 새로 받는 게 명확합니다.
            // eventListModel.clear()
        }
    }

    // -------------------------
    // Event subscription / emit
    // -------------------------

    private fun requestEventList() {
        val s = socket ?: run {
            appendLog("WARN: socket is null (cannot request events)")
            return
        }
        if (!s.connected()) {
            appendLog("WARN: not connected yet (cannot request events)")
            return
        }
        appendLog("EMIT events:list")
        s.emit("events:list")
    }

    private fun subscribeToEvent(eventName: String) {
        val s = socket ?: run {
            appendLog("WARN: socket is null (cannot subscribe)")
            return
        }
        if (!s.connected()) {
            appendLog("WARN: not connected (cannot subscribe)")
            return
        }
        subscribeToEventInternal(eventName)
        // 서버가 구독 프로토콜을 원하면 아래 emit을 사용
        // emitJson("event:subscribe", JSONObject().put("event", eventName))
    }

    private fun unsubscribeFromEvent(eventName: String) {
        val s = socket ?: run {
            appendLog("WARN: socket is null (cannot unsubscribe)")
            return
        }

        // 로컬 리스너 제거
        s.off(eventName)
        subscribedEvents.remove(eventName)
        appendLog("UNSUBSCRIBE local off('$eventName')")

        // 서버가 구독 해제를 원하면 아래 emit을 사용
        // emitJson("event:unsubscribe", JSONObject().put("event", eventName))
    }

    private fun subscribeToEventInternal(eventName: String) {
        val s = socket ?: return

        if (subscribedEvents.contains(eventName)) {
            appendLog("SKIP: already subscribed '$eventName'")
            return
        }

        subscribedEvents.add(eventName)
        appendLog("SUBSCRIBE on('$eventName')")

        s.on(eventName) { args ->
            // ★ 여기 추가
            timelineStore.record(eventName)

            SwingUtilities.invokeLater {
                appendLog("RECV $eventName -> ${args.joinToStringSafe()}")
            }
        }
    }

    private fun emitJson(eventName: String, payload: JSONObject) {
        val s = socket ?: run {
            appendLog("WARN: socket is null (cannot emit $eventName)")
            return
        }
        if (!s.connected()) {
            appendLog("WARN: not connected (cannot emit $eventName)")
            return
        }

        // [중요] TX 바이트 누적 - 이 코드가 있어야 송신 시 그래프가 움직입니다.
        val dataStr = payload.toString()
        val bytes = dataStr.toByteArray(Charsets.UTF_8).size.toLong()
        byteRateStore.addTx(bytes)

        appendLog("EMIT $eventName -> $dataStr")
        s.emit(eventName, payload)
    }

    // -------------------------
    // Event list parsing (events:list:result)
    // -------------------------

    /**
     * 기대 포맷 예시:
     * 1) {"events":[{"name":"server:state","direction":"S2C","desc":"..."} ...]}
     * 2) {"events":["server:state","log:line", ...]}
     */
    private fun updateEventListFromArgs(args: Array<Any>) {
        if (args.isEmpty()) return

        val first = args[0]
        val jsonObj = when (first) {
            is JSONObject -> first
            is String -> runCatching { JSONObject(first) }.getOrNull()
            else -> null
        }

        if (jsonObj == null) {
            appendLog("WARN: events:list:result payload is not JSON")
            return
        }

        val events = jsonObj.opt("events")
        val specs = mutableListOf<EventSpec>()

        when (events) {
            is JSONArray -> {
                for (i in 0 until events.length()) {
                    val item = events.get(i)
                    when (item) {
                        is JSONObject -> {
                            specs += EventSpec(
                                name = item.optString("name"),
                                direction = item.optString("direction").takeIf { it.isNotBlank() },
                                desc = item.optString("desc").takeIf { it.isNotBlank() }
                            )
                        }

                        is String -> specs += EventSpec(name = item)
                    }
                }
            }

            else -> {
                // 혹시 서버가 events를 문자열로 주는 경우도 대비
                val maybeArr = jsonObj.optJSONArray("events")
                if (maybeArr != null) {
                    for (i in 0 until maybeArr.length()) {
                        specs += EventSpec(name = maybeArr.getString(i))
                    }
                }
            }
        }

        // UI 업데이트
        eventListModel.clear()
        specs
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name }
            .forEach { eventListModel.addElement(it) }

        appendLog("EVENTS updated: ${eventListModel.size()} items")
    }

    // -------------------------
    // Helpers
    // -------------------------

    private fun setStatus(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        val ts = timeFmt.format(Date(now))
        val line = "[$ts] $message\n"

        allLogs.add(SocketLogEntry(now, line))

        SwingUtilities.invokeLater {
            val shouldAppend =
                !isFilterMode || matchesFilter(line, activeFilterQuery)

            if (shouldAppend) {
                logArea.append(line)
                // cap 로직 유지
                val maxChars = 200_000
                if (logArea.text.length > maxChars) {
                    logArea.text = logArea.text.takeLast(160_000)
                }
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun Array<Any>.joinToStringSafe(): String {
        return joinToString(
            prefix = "[",
            postfix = "]",
            separator = ", "
        ) { any ->
            when (any) {
                is JSONObject -> any.toString()
                is JSONArray -> any.toString()
                else -> any.toString()
            }
        }
    }

    private fun showAllLogs() {
        SwingUtilities.invokeLater {
            logArea.text = ""
            logArea.append("--- Showing All Logs ---\n")
            allLogs.forEach { logArea.append(it.message) }
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun resetSearchState() {
        lastQuery = ""
        lastMatchIndex = -1
    }

    private fun findNext() {
        val query = searchField.text ?: ""
        if (query.isBlank()) return

        SwingUtilities.invokeLater {
            val text = logArea.text ?: ""
            val startIndex = if (queryChanged(query)) 0 else (logArea.selectionEnd.coerceAtLeast(0))

            val match = findMatch(text, query, startIndex, forward = true)
            if (match != null) {
                highlightMatch(match.first, match.second)
                lastQuery = query
                lastMatchIndex = match.first
            } else {
                // 끝까지 갔으면 처음부터 한 번 더(랩)
                val wrap = findMatch(text, query, 0, forward = true)
                if (wrap != null) {
                    highlightMatch(wrap.first, wrap.second)
                    lastQuery = query
                    lastMatchIndex = wrap.first
                } else {
                    // 매칭 없음
                    appendLog("FIND: no match for '$query'")
                }
            }
        }
    }

    private fun findPrev() {
        val query = searchField.text ?: ""
        if (query.isBlank()) return

        SwingUtilities.invokeLater {
            val text = logArea.text ?: ""
            val startIndex = if (queryChanged(query)) text.length else (logArea.selectionStart.coerceAtLeast(0) - 1)

            val match = findMatch(text, query, startIndex, forward = false)
            if (match != null) {
                highlightMatch(match.first, match.second)
                lastQuery = query
                lastMatchIndex = match.first
            } else {
                // 처음까지 갔으면 끝에서부터(랩)
                val wrap = findMatch(text, query, text.length, forward = false)
                if (wrap != null) {
                    highlightMatch(wrap.first, wrap.second)
                    lastQuery = query
                    lastMatchIndex = wrap.first
                } else {
                    appendLog("FIND: no match for '$query'")
                }
            }
        }
    }

    private fun queryChanged(query: String): Boolean = query != lastQuery

    /**
     * @return Pair(startIndex, endIndex) or null
     */
    private fun findMatch(text: String, query: String, fromIndex: Int, forward: Boolean): Pair<Int, Int>? {
        val caseSensitive = caseSensitiveCheck.isSelected
        val useRegex = regexCheck.isSelected

        return if (useRegex) {
            runCatching {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = Regex(query, options)

                if (forward) {
                    val m = regex.find(text, startIndex = fromIndex) ?: return null
                    m.range.first to (m.range.last + 1)
                } else {
                    // 뒤에서 앞으로: fromIndex 이전 구간에서 마지막 매치
                    val sub = text.substring(0, (fromIndex + 1).coerceAtMost(text.length))
                    val matches = regex.findAll(sub).toList()
                    val m = matches.lastOrNull() ?: return null
                    m.range.first to (m.range.last + 1)
                }
            }.getOrNull()
        } else {
            // 일반 문자열 검색
            val haystack = if (caseSensitive) text else text.lowercase()
            val needle = if (caseSensitive) query else query.lowercase()

            if (forward) {
                val idx = haystack.indexOf(needle, startIndex = fromIndex)
                if (idx < 0) null else idx to (idx + needle.length)
            } else {
                val idx = haystack.lastIndexOf(needle, startIndex = fromIndex.coerceAtMost(haystack.length - 1))
                if (idx < 0) null else idx to (idx + needle.length)
            }
        }
    }

    private fun highlightMatch(start: Int, end: Int) {
        if (start < 0 || end <= start) return

        logArea.requestFocusInWindow()
        logArea.select(start, end)      // 선택 영역으로 하이라이트
        logArea.caretPosition = end     // 스크롤 이동
    }

    private fun applyFilter(query: String) {
        activeFilterQuery = query
        isFilterMode = query.isNotBlank()

        SwingUtilities.invokeLater {
            logArea.text = ""
            if (isFilterMode) {
                logArea.append("--- Filter: '$query' ---\n")
                allLogs
                    .asSequence()
                    .filter { matchesFilter(it.message, query) }
                    .forEach { logArea.append(it.message) }
            } else {
                showAllLogs()
            }
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun clearFilter() {
        activeFilterQuery = ""
        isFilterMode = false
        showAllLogs()
    }


    private var isFilterMode: Boolean = false
    private var activeFilterQuery: String = ""

    private fun matchesFilter(message: String, query: String): Boolean {
        if (query.isBlank()) return true
        val caseSensitive = ::caseSensitiveCheck.isInitialized && caseSensitiveCheck.isSelected
        val useRegex = ::regexCheck.isInitialized && regexCheck.isSelected

        return if (useRegex) {
            runCatching {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(query, options).containsMatchIn(message)
            }.getOrDefault(false)
        } else {
            if (caseSensitive) message.contains(query)
            else message.lowercase().contains(query.lowercase())
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

}
