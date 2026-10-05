package com.github.gahyunkim.socketpulse.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

// 데이터 클래스
data class EventSpec(val name: String, val direction: String? = null, val desc: String? = null)
data class SocketLogEntry(val timestamp: Long, val event: String, val data: String, val message: String)

private val allLogs = mutableListOf<SocketLogEntry>()

class MyToolWindowFactory : ToolWindowFactory {
    private val timelineStore = EventTimelineStore(windowMs = 30_000L)
    private val byteRateStore = ByteRateStore(windowSec = 30)
    private var chartTimer: javax.swing.Timer? = null

    // UI 컴포넌트
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

    private lateinit var mainSplitter: com.intellij.ui.OnePixelSplitter

    private var monitorServer: com.sun.net.httpserver.HttpServer? = null
    private var socket: Socket? = null
    private var isMonitoring = false
    private var isFilterMode = false
    private var activeFilterQuery = ""

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 1. 컴포넌트 초기화 (순서 중요: lateinit 에러 방지)
        setupUIComponents()

        val root = JBPanel<JBPanel<*>>(BorderLayout())

        // 2. 상단: 접속 바 + 시원한 차트 + 필터 바
        val topContainer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildConnectPanel())

            val chart = ThroughputChart(byteRateStore, 30).apply {
                preferredSize = Dimension(-1, 110)
            }
            add(chart)
            add(buildSearchBar())

            chartTimer = javax.swing.Timer(200) {
                chart.repaint()
                logList.repaint()
            }.apply { start() }
        }

        // 3. 중앙: Splitter (왼쪽 리스트 / 오른쪽 상세)
        mainSplitter = com.intellij.ui.OnePixelSplitter(false, 1.0f).apply {
            val leftContent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                // ✅ 왼쪽 사이드바 (이벤트 목록만) + 중앙 로그 리스트
                add(buildEventPanel(), BorderLayout.WEST)
                add(JBScrollPane(logList), BorderLayout.CENTER)
            }
            firstComponent = leftContent
            secondComponent = JBScrollPane(detailArea).apply {
                border = BorderFactory.createTitledBorder("Event Details")
                minimumSize = Dimension(0, 0)
            }
        }

        root.add(topContainer, BorderLayout.NORTH)
        root.add(mainSplitter, BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)

        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
    }

    private fun setupUIComponents() {
        logList = JList(logListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, v: Any?, i: Int, s: Boolean, f: Boolean): Component {
                    val entry = v as SocketLogEntry
                    val panel = JBPanel<JBPanel<*>>(BorderLayout())
                    val label = super.getListCellRendererComponent(list, v, i, s, f) as JBLabel
                    label.text = "  ${entry.event} (${timeFmt.format(Date(entry.timestamp))})"
                    panel.add(label, BorderLayout.CENTER)

                    val bar = object : JComponent() {
                        override fun paintComponent(g: Graphics) {
                            val g2 = g as Graphics2D
                            val age = System.currentTimeMillis() - entry.timestamp
                            if (age in 0..30000L) {
                                val x = width - ((age.toDouble() / 30000L) * width).toInt()
                                g2.color = Color(255, 150, 50)
                                g2.fillRect(x - 2, 4, 4, height - 8)
                            }
                        }
                        override fun getPreferredSize() = Dimension(120, 20)
                    }
                    panel.add(bar, BorderLayout.EAST)
                    panel.background = label.background
                    return panel
                }
            }
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val entry = logList.selectedValue
                    if (entry != null) {
                        showDetailFromEntry(entry)
                        mainSplitter.proportion = 0.6f
                    } else {
                        mainSplitter.proportion = 1.0f
                    }
                }
            }
        }

        detailArea = JTextArea().apply {
            isEditable = false
            font = JBUI.Fonts.create("JetBrains Mono", 13)
            val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            margin = Insets(10, 10, 10, 10)
        }
        statusLabel = JBLabel("State: Waiting...").apply { foreground = Color.GRAY }
        urlField = JBTextField("http://localhost:3000", 12)
    }

    private fun buildConnectPanel() = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 5)).apply {
        val monitorBtn = JButton("Start App Monitoring")
        val portField = JBTextField("9999", 4)
        monitorBtn.addActionListener {
            if (!isMonitoring) {
                startMonitoringServer(portField.text.toIntOrNull() ?: 9999)
                monitorBtn.text = "Stop Monitoring"; isMonitoring = true
            } else {
                monitorServer?.stop(0); monitorBtn.text = "Start App Monitoring"; isMonitoring = false
            }
        }
        add(JBLabel("Port:")); add(portField); add(monitorBtn)
        add(Box.createHorizontalStrut(10)); add(JBLabel("Direct:")); add(urlField)
        add(JButton("Connect").apply { addActionListener { connect(urlField.text) } })
    }

    private fun buildSearchBar() = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        searchField = JBTextField("", 25).apply {
            emptyText.text = "Filter by event name or data..."; addActionListener { applyFilter(text) }
        }
        caseSensitiveCheck = JCheckBox("Aa"); regexCheck = JCheckBox(".*")
        val clearBtn = JButton("Clear Logs").apply {
            addActionListener {
                allLogs.clear(); logListModel.clear(); detailArea.text = ""; mainSplitter.proportion = 1.0f
            }
        }
        add(JBLabel("Filter:")); add(searchField); add(caseSensitiveCheck); add(regexCheck)
        add(Box.createHorizontalStrut(10)); add(clearBtn)
    }

    private fun buildEventPanel() = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); preferredSize = Dimension(180, -1)
        border = JBUI.Borders.empty(0, 10)

        // ✅ 사이드바에서 중복된 차트들은 모두 제거했습니다.
        add(Box.createVerticalStrut(10))
        add(JBLabel("Detected Events").apply { font = JBUI.Fonts.label().asBold() })
        val eventList = JList(eventListModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
        add(JBScrollPane(eventList).apply { preferredSize = Dimension(160, 150) })

        add(Box.createVerticalStrut(20))
        val eventInput = JBTextField().apply { emptyText.text = "Event Name" }
        val dataInput = JBTextArea(4, 10).apply { lineWrap = true; font = JBUI.Fonts.create("JetBrains Mono", 12) }

        eventList.addListSelectionListener { if (!it.valueIsAdjusting) eventList.selectedValue?.let { spec -> eventInput.text = spec.name } }

        add(JBLabel("Manual Emit").apply { font = JBUI.Fonts.label().asBold() })
        add(eventInput); add(Box.createVerticalStrut(5))
        add(JBScrollPane(dataInput)); add(Box.createVerticalStrut(5))
        add(JButton("Send Emit").apply {
            addActionListener {
                val name = eventInput.text.trim(); val raw = dataInput.text.trim()
                if (name.isNotEmpty()) {
                    try { emitJson(name, if (raw.isEmpty()) JSONObject() else JSONObject(raw)) }
                    catch (e: Exception) { appendLog("ERROR: Invalid JSON") }
                }
            }
        })
        add(Box.createVerticalStrut(10))
        add(JButton("Reset View").apply { addActionListener { showAllLogs() } })
    }

    private fun startMonitoringServer(port: Int) {
        try {
            monitorServer?.stop(0)
            monitorServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0).apply {
                createContext("/log") { ex ->
                    if ("POST" == ex.requestMethod) {
                        val body = ex.requestBody.bufferedReader().readText()
                        val json = JSONObject(body)
                        val ev = json.optString("event", "unknown"); val ds = json.optString("data", "{}")

                        byteRateStore.addRx(body.toByteArray().size.toLong())
                        timelineStore.record(ev)

                        if (ev == "events:list:result") updateEventListFromJson(ds)
                        val isNew = eventListModel.elements().asSequence().none { it.name == ev }
                        if (isNew && !ev.startsWith("SOCKET_") && ev != "events:list:result") {
                            SwingUtilities.invokeLater { eventListModel.addElement(EventSpec(ev, "Detected")) }
                        }

                        SwingUtilities.invokeLater {
                            statusLabel.text = "Monitoring Active: $ev"; statusLabel.foreground = Color(100, 200, 100)
                            appendLog("[APP->MONITOR] <$ev> : $ds")
                        }
                        ex.sendResponseHeaders(200, 0)
                    }
                    ex.close()
                }
                start()
            }
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    private fun updateEventListFromJson(jsonStr: String) {
        try {
            val events = JSONObject(jsonStr).optJSONArray("events") ?: return
            SwingUtilities.invokeLater {
                eventListModel.clear()
                for (i in 0 until events.length()) {
                    val item = events.getJSONObject(i)
                    eventListModel.addElement(EventSpec(item.getString("name"), item.optString("direction"), item.optString("desc")))
                }
            }
        } catch (_: Exception) {}
    }

    private fun showDetailFromEntry(entry: SocketLogEntry) {
        try {
            val raw = entry.data.trim(); var dj = raw
            if (raw.contains("{")) {
                val s = raw.indexOf("{"); val e = raw.lastIndexOf("}")
                if (s != -1 && e != -1) dj = JSONObject(raw.substring(s, e + 1)).toString(4)
            }
            detailArea.text = "EVENT: ${entry.event}\nTIME: ${timeFmt.format(Date(entry.timestamp))}\n---\n$dj"
            detailArea.caretPosition = 0
        } catch (e: Exception) { detailArea.text = "RAW DATA:\n${entry.data}" }
    }

    private fun appendLog(msg: String) {
        val entry = SocketLogEntry(
            System.currentTimeMillis(),
            msg.substringAfter("<", "").substringBefore(">", "unknown"),
            msg.substringAfter(" : ", "{}"),
            msg
        )
        allLogs.add(entry)
        SwingUtilities.invokeLater {
            if (!isFilterMode || matchesFilter(entry.message, activeFilterQuery)) {
                logListModel.addElement(entry)
                logList.ensureIndexIsVisible(logListModel.size() - 1)
            }
        }
    }

    private fun applyFilter(q: String) {
        activeFilterQuery = q; isFilterMode = q.isNotBlank()
        logListModel.clear()
        if (isFilterMode) allLogs.filter { matchesFilter(it.message, q) }.forEach { logListModel.addElement(it) }
        else showAllLogs()
    }

    private fun showAllLogs() {
        logListModel.clear()
        allLogs.forEach { logListModel.addElement(it) }
        if (!logListModel.isEmpty) logList.ensureIndexIsVisible(logListModel.size() - 1)
    }

    private fun findNext() {
        val q = searchField.text; if (q.isBlank()) return
        val start = logList.selectedIndex + 1
        for (i in start until logListModel.size()) {
            if (matchesFilter(logListModel[i].message, q)) {
                logList.selectedIndex = i; logList.ensureIndexIsVisible(i); return
            }
        }
    }

    private fun matchesFilter(m: String, q: String) = m.contains(q, !caseSensitiveCheck.isSelected)

    private fun connect(url: String) {
        socket?.apply { off(); disconnect(); close() }
        try {
            statusLabel.text = "Connecting..."; statusLabel.foreground = Color.ORANGE
            socket = IO.socket(url).apply {
                on(Socket.EVENT_CONNECT) { SwingUtilities.invokeLater { statusLabel.text = "Connected"; statusLabel.foreground = Color(100, 200, 100) } }
                onAnyIncoming { args ->
                    val ev = args?.getOrNull(0)?.toString() ?: "unknown"
                    val ds = args?.sliceArray(1 until args.size)?.joinToString(", ") ?: "[]"
                    SwingUtilities.invokeLater { appendLog("RECV <$ev> : $ds") }
                }
                connect()
            }
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    private fun emitJson(event: String, payload: JSONObject) {
        socket?.let { if (it.connected()) { it.emit(event, payload); byteRateStore.addTx(payload.toString().toByteArray().size.toLong()); appendLog("EMIT <$event> : $payload") } }
    }

    override fun shouldBeAvailable(project: Project) = true
}