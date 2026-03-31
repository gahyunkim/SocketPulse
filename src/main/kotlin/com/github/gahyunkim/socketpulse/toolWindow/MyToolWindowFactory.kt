package com.github.gahyunkim.socketpulse.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Insets
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

data class EventSpec(val name: String, val direction: String? = null, val desc: String? = null) {
    override fun toString(): String = direction?.let { "[$it] $name" } ?: name
}

data class SocketLogEntry(val timestamp: Long, val event: String, val data: String, val message: String)

private val allLogs = mutableListOf<SocketLogEntry>()

class MyToolWindowFactory : ToolWindowFactory {
    private val timelineStore = EventTimelineStore(windowMs = 30_000L)
    private var selectedEventForTimeline: String? = null
    private val byteRateStore = ByteRateStore(windowSec = 30)
    private var chartTimer: javax.swing.Timer? = null

    private lateinit var searchField: JBTextField
    private lateinit var caseSensitiveCheck: JCheckBox
    private lateinit var regexCheck: JCheckBox
    private lateinit var detailArea: JTextArea
    private val logListModel = DefaultListModel<SocketLogEntry>()
    private lateinit var logList: JList<SocketLogEntry>
    private lateinit var statusLabel: JBLabel
    private lateinit var urlField: JBTextField
    private val eventListModel = DefaultListModel<EventSpec>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private var monitorServer: com.sun.net.httpserver.HttpServer? = null
    private var socket: Socket? = null
    private var isMonitoring = false
    private var isFilterMode = false
    private var activeFilterQuery = ""

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = JBPanel<JBPanel<*>>(BorderLayout()).apply { border = JBUI.Borders.empty(10) }
        val topPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildConnectPanel())
            add(buildStatusPanel().apply { border = JBUI.Borders.emptyTop(5) })
        }
        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout(12, 0)).apply {
            add(buildEventPanel(), BorderLayout.WEST)
            add(buildMainLogContainer(), BorderLayout.CENTER)
        }
        root.add(topPanel, BorderLayout.NORTH)
        root.add(centerPanel, BorderLayout.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
    }

    private fun buildConnectPanel() = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        val monitorBtn = JButton("Start App Monitoring")
        val portField = JBTextField("9999", 4)
        monitorBtn.addActionListener {
            if (!isMonitoring) {
                startMonitoringServer(portField.text.toIntOrNull() ?: 9999)
                monitorBtn.text = "Stop Monitoring"
                isMonitoring = true
            } else {
                monitorServer?.stop(0)
                monitorBtn.text = "Start App Monitoring"
                isMonitoring = false
            }
        }
        add(JBLabel("Port:")); add(portField); add(monitorBtn); add(JBLabel("| Direct:"))
        urlField = JBTextField("http://localhost:3000", 12)
        add(urlField)
        add(JButton("Connect").apply { addActionListener { connect(urlField.text) } })
    }

    private fun buildStatusPanel() = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        statusLabel = JBLabel("State: Waiting for connect").apply { foreground = Color.GRAY }
        add(statusLabel)
    }

    private fun buildMainLogContainer() = JBPanel<JBPanel<*>>(BorderLayout(0, 5)).apply {
        val searchBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            searchField = JBTextField("", 15).apply { addActionListener { applyFilter(text) } }
            caseSensitiveCheck = JCheckBox("Aa"); regexCheck = JCheckBox("Regex")
            add(JBLabel("Find:")); add(searchField)
            add(JButton("Next").apply { addActionListener { findNext() } })
            add(caseSensitiveCheck); add(regexCheck)
            add(JButton("Clear").apply {
                addActionListener {
                    allLogs.clear(); logListModel.clear(); detailArea.text = ""
                }
            })
        }
        add(searchBar, BorderLayout.NORTH)

        val logBoxPanel = JBPanel<JBPanel<*>>(GridLayout(1, 2, 10, 0))
        logList = JList(logListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    v: Any?,
                    i: Int,
                    s: Boolean,
                    f: Boolean
                ): Component {
                    return super.getListCellRendererComponent(list, v, i, s, f).apply {
                        if (v is SocketLogEntry) text = v.message
                        border = JBUI.Borders.empty(2, 5)
                    }
                }
            }
            addListSelectionListener { if (!it.valueIsAdjusting) selectedValue?.let { entry -> showDetailFromEntry(entry) } }
        }

        detailArea = JTextArea().apply {
            isEditable = false
            font = JBUI.Fonts.create("JetBrains Mono", 13)
            lineWrap = false // 가로 스크롤 활성화
            val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            margin = Insets(10, 10, 10, 10)
        }

        logBoxPanel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Log List"); add(JBScrollPane(logList))
        })
        logBoxPanel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Event Details"); add(JBScrollPane(detailArea))
        })
        add(logBoxPanel, BorderLayout.CENTER)
    }

    private fun buildEventPanel() = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        preferredSize = Dimension(320, -1)
        border = JBUI.Borders.empty(0, 10, 0, 10) // 양옆 여백

        // 헬퍼 함수 활용
        fun addLeft(comp: JComponent, topMargin: Int = 5) {
            comp.alignmentX = Component.LEFT_ALIGNMENT
            add(Box.createVerticalStrut(topMargin))
            add(comp)
        }

        // 1. 차트 및 기존 리스트 레이아웃
        val throughputChart = ThroughputChart(byteRateStore, 30).apply { preferredSize = Dimension(320, 100) }
        val timelineChart = TimelineChart(timelineStore, 30_000L) {
            selectedEventForTimeline?.let { listOf(it) } ?: timelineStore.snapshotTopEvents(6).map { it.first }
        }.apply { preferredSize = Dimension(320, 100) }

        chartTimer = javax.swing.Timer(200) { throughputChart.repaint(); timelineChart.repaint() }.apply { start() }

        addLeft(JBLabel("Throughput").apply { font = JBUI.Fonts.label().asBold() })
        addLeft(throughputChart)
        addLeft(JBLabel("Timeline").apply { font = JBUI.Fonts.label().asBold() }, 15)
        addLeft(timelineChart)
        addLeft(JBLabel("Detected Events").apply { font = JBUI.Fonts.label().asBold() }, 15)

        // 이벤트 리스트 정의
        val eventList = JList(eventListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        addLeft(JBScrollPane(eventList).apply { preferredSize = Dimension(320, 150) })
        addLeft(JButton("Reset View").apply { addActionListener { showAllLogs() } }, 10)

        // ---------------------------------------------------------
        // 2. 수동 Emit 패널 (manualEmitPanel)
        // ---------------------------------------------------------
        val manualEmitPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT // 부모 패널에서의 정렬
            border = JBUI.Borders.emptyTop(20)
        }

        // 내부 컴포넌트들
        val eventInput = JBTextField().apply {
            emptyText.text = "Event Name"
            maximumSize = Dimension(Int.MAX_VALUE, 30) // 가로로 꽉 차게
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val dataInput = JBTextArea(3, 20).apply {
            font = JBUI.Fonts.create("JetBrains Mono", 12)
            lineWrap = true
        }
        val emitBtn = JButton("🚀 Send Emit (C2S)").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // 리스트 선택 시 자동 입력 로직
        eventList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                eventList.selectedValue?.let { spec ->
                    eventInput.text = spec.name
                }
            }
        }

        // 전송 버튼 로직
        emitBtn.addActionListener {
            val name = eventInput.text.trim()
            val rawData = dataInput.text.trim()
            if (name.isNotEmpty()) {
                try {
                    val jsonPayload = if (rawData.isEmpty()) JSONObject() else JSONObject(rawData)
                    emitJson(name, jsonPayload)
                } catch (e: Exception) {
                    appendLog("ERROR: Invalid JSON format for emit")
                }
            }
        }

        // manualEmitPanel에 차례대로 추가 (여기서 addLeft 대신 직접 add 하되 정렬 유지)
        manualEmitPanel.add(JBLabel("Manual Emit Test").apply {
            font = JBUI.Fonts.label().asBold()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        manualEmitPanel.add(Box.createVerticalStrut(8))
        manualEmitPanel.add(eventInput)
        manualEmitPanel.add(Box.createVerticalStrut(5))
        manualEmitPanel.add(JBScrollPane(dataInput).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        manualEmitPanel.add(Box.createVerticalStrut(8))
        manualEmitPanel.add(emitBtn)

        // ✅ 핵심: 생성한 manualEmitPanel을 '메인 패널(this)'에 추가합니다.
        this.add(manualEmitPanel)
    }

    private fun startMonitoringServer(port: Int) {
        try {
            monitorServer?.stop(0)
            monitorServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0).apply {
                createContext("/log") { ex ->
                    if ("POST" == ex.requestMethod) {
                        val body = ex.requestBody.bufferedReader().readText()
                        val json = JSONObject(body)
                        val eventName = json.optString("event", "unknown")
                        val dataStr = json.optString("data", "{}")

                        // ✅ [핵심] 서버가 보낸 리스트 결과라면 리스트 모델 업데이트
                        if (eventName == "events:list:result") {
                            updateEventListFromJson(dataStr)
                        }

                        // 실시간 이벤트 감지 시 리스트에 자동 추가
                        val isNew = eventListModel.elements().asSequence().none { it.name == eventName }
                        if (isNew && !eventName.startsWith("SOCKET_") && eventName != "events:list:result") {
                            SwingUtilities.invokeLater { eventListModel.addElement(EventSpec(eventName, "Detected")) }
                        }

                        byteRateStore.addRx(body.toByteArray().size.toLong())
                        timelineStore.record(eventName)
                        SwingUtilities.invokeLater {
                            statusLabel.text = "State: Monitoring Active (Last: $eventName)"
                            statusLabel.foreground = Color(100, 200, 100)
                            appendLog("[APP->MONITOR] <$eventName> : $dataStr")
                        }
                        ex.sendResponseHeaders(200, 0)
                    }
                    ex.close()
                }
                start()
            }
            appendLog("SYSTEM: 모니터링 서버가 $port 포트에서 시작되었습니다.")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    private fun updateEventListFromJson(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val events = obj.optJSONArray("events") ?: return
            SwingUtilities.invokeLater {
                eventListModel.clear()
                for (i in 0 until events.length()) {
                    val item = events.getJSONObject(i)
                    eventListModel.addElement(
                        EventSpec(
                            item.getString("name"),
                            item.optString("direction"),
                            item.optString("desc")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showDetailFromEntry(entry: SocketLogEntry) {
        try {
            val rawData = entry.data.trim()
            var displayJson = rawData
            if (rawData.contains("{")) {
                val start = rawData.indexOf("{");
                val end = rawData.lastIndexOf("}")
                if (start != -1 && end != -1) displayJson = JSONObject(rawData.substring(start, end + 1)).toString(4)
            }
            detailArea.text = "EVENT: ${entry.event}\nTIME: ${timeFmt.format(Date(entry.timestamp))}\n---\n$displayJson"
        } catch (e: Exception) {
            detailArea.text = "RAW: ${entry.data}"
        }
        detailArea.caretPosition = 0 // 무조건 왼쪽 상단 고정
    }

    private fun appendLog(msg: String) {
        val entry = SocketLogEntry(
            System.currentTimeMillis(), msg.substringAfter("<", "").substringBefore(">", "unknown"),
            msg.substringAfter(" : ", "{}"), "[${timeFmt.format(Date())}] $msg"
        )
        allLogs.add(entry)
        SwingUtilities.invokeLater {
            if (!isFilterMode || matchesFilter(entry.message, activeFilterQuery)) {
                logListModel.addElement(entry); logList.ensureIndexIsVisible(logListModel.size() - 1)
            }
        }
    }

    private fun applyFilter(q: String) {
        activeFilterQuery = q; isFilterMode = q.isNotBlank()
        SwingUtilities.invokeLater {
            logListModel.clear(); if (isFilterMode) allLogs.filter {
            matchesFilter(
                it.message,
                q
            )
        }.forEach { logListModel.addElement(it) } else showAllLogs()
        }
    }

    private fun showAllLogs() {
        SwingUtilities.invokeLater {
            logListModel.clear(); allLogs.forEach { logListModel.addElement(it) }; if (!logListModel.isEmpty) logList.ensureIndexIsVisible(
            logListModel.size() - 1
        )
        }
    }

    private fun findNext() {

        val query = searchField.text

        if (query.isBlank()) return

        val start = logList.selectedIndex + 1

        for (i in start until logListModel.size()) {

            if (matchesFilter(logListModel[i].message, query)) {

                logList.selectedIndex = i

                logList.ensureIndexIsVisible(i)

                return

            }

        }

        for (i in 0 until start) {

            if (matchesFilter(logListModel[i].message, query)) {

                logList.selectedIndex = i

                logList.ensureIndexIsVisible(i)

                return

            }

        }

    }

    private fun matchesFilter(m: String, q: String) = m.contains(q, !caseSensitiveCheck.isSelected)
    private fun filterLogsByTime(start: Long, end: Long) {

        SwingUtilities.invokeLater {

            logListModel.clear()

            allLogs.filter { it.timestamp in start..end }.forEach { logListModel.addElement(it) }

            detailArea.text = "--- Filtered by Time Range ---"

        }

    }

    private fun connect(url: String) {
        cleanupSocket("Reconnect")
        try {
            SwingUtilities.invokeLater {
                statusLabel.text = "State: Connecting..."
                statusLabel.foreground = Color.ORANGE
            }

            socket = IO.socket(url).apply {
                // 연결 성공
                on(Socket.EVENT_CONNECT) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "State: Connected"
                        statusLabel.foreground = Color(100, 200, 100) // 초록색
                    }
                }

                // 연결 에러
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    SwingUtilities.invokeLater {
                        statusLabel.text = "State: Connection Error"
                        statusLabel.foreground = Color.RED
                    }
                    appendLog("ERROR: Connection failed - ${args?.getOrNull(0)}")
                }

                // 모든 들어오는 이벤트 가로채기
                onAnyIncoming { args ->
                    val eventName = args?.getOrNull(0)?.toString() ?: "unknown"

                    // ✅ 오류 해결 포인트: args를 안전하게 String으로 변환
                    val dataStr = if (args != null && args.size > 1) {
                        // 첫 번째는 이벤트 이름이므로 두 번째(index 1)부터 진짜 데이터임
                        args.sliceArray(1 until args.size).joinToString(", ") { it.toString() }
                    } else {
                        "[]"
                    }

                    SwingUtilities.invokeLater {
                        appendLog("RECV <$eventName> : $dataStr")
                    }
                }

                connect()
            }
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    private fun emitJson(event: String, payload: JSONObject) {
        socket?.let { s ->
            if (s.connected()) {
                s.emit(event, payload)

                // ✅ 내가 보낸 데이터 크기도 TX(전송) 데이터로 기록!
                val bytes = payload.toString().toByteArray().size.toLong()
                byteRateStore.addTx(bytes)

                appendLog("EMIT <$event> : $payload")
            } else {
                appendLog("WARN: Cannot emit. Socket is DISCONNECTED.")
            }
        } ?: appendLog("WARN: Direct Server is not connected. (Connect first!)")
    }

    private fun cleanupSocket(r: String) {
        socket?.apply { off(); disconnect(); close() }; socket = null
    }

    override fun shouldBeAvailable(project: Project) = true
}