package com.lm.player.core.widget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.lm.player.MainActivity
import com.lm.player.R
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 小部件拉起 App 时携带的请求（目前只有「本地音乐」→ 打开资料库），由 MainActivity 在 onCreate / onNewIntent 里交给 [consume]，
 * 界面层收集 [pending] 后执行并清空。
 */
object WidgetLaunch {
    const val EXTRA_SEARCH_QUERY = "lm_widget_search_query"
    const val EXTRA_OPEN_LIBRARY = "lm_widget_open_library"

    sealed class Request {
        data class Search(val query: String) : Request()
        object Library : Request()
    }

    val pending = MutableStateFlow<Request?>(null)

    fun consume(intent: Intent?) {
        intent ?: return
        val query = intent.getStringExtra(EXTRA_SEARCH_QUERY)?.trim()
        when {
            !query.isNullOrEmpty() -> pending.value = Request.Search(query)
            intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) -> pending.value = Request.Library
            else -> return
        }
        // 只消费一次：避免旋转屏幕 / 从最近任务恢复时重复执行
        intent.removeExtra(EXTRA_SEARCH_QUERY)
        intent.removeExtra(EXTRA_OPEN_LIBRARY)
    }
}

/**
 * 桌面悬浮搜索面板：由 3x3 歌词小部件顶部的搜索框拉起。
 *
 * 小部件（RemoteViews）本身不支持输入框，所以点搜索框后在桌面上弹出这个半透明面板：
 * 它运行在独立任务里（manifest 里单独的 taskAffinity），不会把 App 主界面带到前台。
 * - 输入 / 历史 / 热歌榜 / 语音 → 结果直接列在面板里（本地曲库命中在前，在线结果在后）；
 * - 点一首：以整页结果为播放队列开始播放，面板关闭，桌面上的歌词小部件随即更新；
 * - 返回键：结果页先回到面板首页，再按一次关闭；点卡片外部关闭。
 * 听歌识曲需要第三方音频指纹服务，本应用不具备，入口隐藏。
 */
class WidgetSearchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"
        const val EXTRA_ACTION_MODE = "extra_action_mode"
        const val MODE_DEFAULT = 0
        const val MODE_VOICE = 2
    }

    private lateinit var etSearchInput: EditText
    private lateinit var btnClear: ImageView
    private lateinit var homeScroll: View
    private lateinit var resultsBox: View
    private lateinit var resultsStatus: TextView
    private lateinit var resultsList: ListView
    private val adapter = ResultAdapter()
    private var searchJob: Job? = null

    private val historyChipIds = intArrayOf(
        R.id.history_chip_1, R.id.history_chip_2, R.id.history_chip_3, R.id.history_chip_4
    )
    private val hotItemIds = intArrayOf(
        R.id.hot_search_item_1, R.id.hot_search_item_2, R.id.hot_search_item_3,
        R.id.hot_search_item_4, R.id.hot_search_item_5
    )

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) {
            setInput(text)
            performSearch(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_search)
        initViews()
        renderHistory()
        renderHotSearch()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (resultsBox.visibility == View.VISIBLE) showHome() else finish()
            }
        })
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        etSearchInput = findViewById(R.id.et_search_input)
        btnClear = findViewById(R.id.btn_search_clear)
        homeScroll = findViewById(R.id.search_home_scroll)
        resultsBox = findViewById(R.id.search_results_box)
        resultsStatus = findViewById(R.id.search_results_status)
        resultsList = findViewById(R.id.search_results_list)
        resultsList.adapter = adapter
        resultsList.setOnItemClickListener { _, _, position, _ -> playResult(position) }

        // 点卡片外部关闭面板（卡片本身 clickable，不会把点击传到背后的遮罩）
        findViewById<View>(R.id.search_panel_scrim).setOnClickListener { closePanel() }
        findViewById<View>(R.id.btn_search_back).setOnClickListener {
            if (resultsBox.visibility == View.VISIBLE) showHome() else closePanel()
        }
        btnClear.setOnClickListener {
            etSearchInput.text.clear()
            showHome()
            etSearchInput.requestFocus()
            showKeyboard()
        }
        findViewById<View>(R.id.btn_search_submit).setOnClickListener { submitInput() }
        findViewById<View>(R.id.btn_search_mic).setOnClickListener { startVoiceSearch() }

        btnClear.visibility = if (etSearchInput.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        etSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (s.isNullOrEmpty() && resultsBox.visibility == View.VISIBLE) showHome()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput()
                true
            } else false
        }

        findViewById<View>(R.id.tool_recognize)?.visibility = View.GONE
        findViewById<View>(R.id.tool_voice)?.setOnClickListener { startVoiceSearch() }
        findViewById<View>(R.id.tool_local)?.setOnClickListener { openLocalMusic() }
        findViewById<View>(R.id.tool_lyric)?.setOnClickListener {
            etSearchInput.hint = "输入一句你记得的歌词…"
            etSearchInput.requestFocus()
            showKeyboard()
        }
        findViewById<View>(R.id.btn_clear_history)?.setOnClickListener {
            WidgetDataRepository.clearSearchHistory(this)
            renderHistory()
            Toast.makeText(this, "搜索历史已清空", Toast.LENGTH_SHORT).show()
        }

        etSearchInput.post {
            etSearchInput.requestFocus()
            showKeyboard()
        }
    }

    private fun setInput(text: String) {
        etSearchInput.setText(text)
        etSearchInput.setSelection(text.length)
    }

    private fun submitInput() {
        val query = etSearchInput.text.toString().trim()
        if (query.isNotEmpty()) performSearch(query)
        else Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 面板首页：历史 + 热歌榜 ----------------

    private fun showHome() {
        searchJob?.cancel()
        resultsBox.visibility = View.GONE
        homeScroll.visibility = View.VISIBLE
        renderHistory()
    }

    /** 搜索历史标签：最多 4 个，没有历史时整个分组（标题 + 标签）隐藏 */
    private fun renderHistory() {
        val history = WidgetDataRepository.searchHistory(this)
        val container = findViewById<View>(R.id.history_container)
        val header = findViewById<View>(R.id.btn_clear_history)?.parent as? View
        val visible = if (history.isEmpty()) View.GONE else View.VISIBLE
        container?.visibility = visible
        header?.visibility = visible
        historyChipIds.forEachIndexed { i, id ->
            val chip = findViewById<TextView>(id) ?: return@forEachIndexed
            val text = history.getOrNull(i)
            if (text == null) {
                chip.visibility = View.GONE
            } else {
                chip.visibility = View.VISIBLE
                chip.text = text
                chip.setOnClickListener {
                    setInput(text)
                    performSearch(text)
                }
            }
        }
    }

    /** 热歌榜前 5 首（与榜单小部件共用缓存）；没有缓存时后台拉取 */
    private fun renderHotSearch() {
        val cached = WidgetDataRepository.cachedChart(ChartTab.HOT)
        if (cached != null) {
            bindHot(cached.songs)
            if (WidgetDataRepository.isChartFresh(ChartTab.HOT)) return
        } else {
            bindHotMessage("热歌榜加载中…")
        }
        lifecycleScope.launch {
            val data = WidgetDataRepository.loadChart(applicationContext, ChartTab.HOT)
            if (isFinishing || isDestroyed) return@launch
            when {
                data != null -> bindHot(data.songs)
                cached == null -> bindHotMessage("暂无热歌榜（需要连接柠檬服务器或启用本地音源）")
            }
            if (data != null) ChartWidgetController.updateAll(applicationContext, allowFetch = false)
        }
    }

    /** 热门行布局（Antigravity）：序号 / 「歌名 · 歌手」/ 标签，三个子视图没有单独 ID，按顺序取 */
    private fun bindHot(songs: List<UnifiedSong>) {
        hotItemIds.forEachIndexed { i, id ->
            val row = findViewById<ViewGroup>(id) ?: return@forEachIndexed
            val song = songs.getOrNull(i)
            if (song == null) {
                row.visibility = View.GONE
                return@forEachIndexed
            }
            row.visibility = View.VISIBLE
            (row.getChildAt(0) as? TextView)?.text = (i + 1).toString()
            (row.getChildAt(1) as? TextView)?.text =
                if (song.artist.isNotBlank()) "${song.title} · ${song.artist}" else song.title
            // 平台没有热度数值：只给前三名标「热」
            (row.getChildAt(2) as? TextView)?.let { tag ->
                tag.visibility = if (i < 3) View.VISIBLE else View.GONE
                tag.text = "热"
            }
            // 热歌榜条目直接播放（以整张热歌榜为队列），不必再搜一次
            row.setOnClickListener { playList(songs, i) }
        }
    }

    private fun bindHotMessage(msg: String) {
        hotItemIds.forEachIndexed { i, id ->
            val row = findViewById<ViewGroup>(id) ?: return@forEachIndexed
            if (i == 0) {
                row.visibility = View.VISIBLE
                (row.getChildAt(0) as? TextView)?.text = ""
                (row.getChildAt(1) as? TextView)?.text = msg
                row.getChildAt(2)?.visibility = View.GONE
                row.setOnClickListener(null)
            } else {
                row.visibility = View.GONE
            }
        }
    }

    // ---------------- 搜索与结果 ----------------

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val initialQuery = intent.getStringExtra(EXTRA_INITIAL_QUERY)
        if (!initialQuery.isNullOrBlank()) {
            intent.removeExtra(EXTRA_INITIAL_QUERY)
            setInput(initialQuery)
            performSearch(initialQuery)
            return
        }
        if (intent.getIntExtra(EXTRA_ACTION_MODE, MODE_DEFAULT) == MODE_VOICE) {
            intent.removeExtra(EXTRA_ACTION_MODE)
            startVoiceSearch()
        }
    }

    private fun performSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        etSearchInput.clearFocus()
        WidgetDataRepository.addSearchHistory(this, q)

        homeScroll.visibility = View.GONE
        resultsBox.visibility = View.VISIBLE
        adapter.submit(emptyList())
        resultsStatus.text = "正在搜索「$q」…"

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val result = WidgetDataRepository.searchSongs(applicationContext, q)
            if (isFinishing || isDestroyed) return@launch
            adapter.submit(result.songs)
            resultsList.setSelection(0)
            resultsStatus.text = when {
                result.songs.isNotEmpty() && !result.onlineAvailable ->
                    "本地曲库找到 ${result.songs.size} 首（未连接柠檬服务器或本地音源，没有在线结果）"
                result.songs.isNotEmpty() -> "找到 ${result.songs.size} 首 · 点一首直接播放"
                !result.onlineAvailable -> "本地曲库没有找到；连接柠檬服务器或启用本地音源后可搜索在线歌曲"
                result.onlineFailed -> "搜索失败，请检查网络后重试"
                else -> "没有找到「$q」相关的歌曲"
            }
        }
    }

    private fun playResult(position: Int) {
        playList(adapter.items, position)
    }

    private fun playList(songs: List<UnifiedSong>, index: Int) {
        val song = songs.getOrNull(index) ?: return
        Toast.makeText(this, "正在播放：${song.title}", Toast.LENGTH_SHORT).show()
        // 播放链路跑在全局协程里，面板关闭不影响起播
        val appCtx = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            WidgetDataRepository.playList(appCtx, songs, index)
        }
        closePanel()
    }

    /** 语音搜歌：系统语音输入（国内部分系统没有该服务，此时提示用输入法自带的语音键） */
    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出歌名或歌手")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "本机没有系统语音识别服务，可以用输入法键盘上的语音键说出歌名", Toast.LENGTH_LONG).show()
            etSearchInput.requestFocus()
            showKeyboard()
        } catch (_: SecurityException) {
            Toast.makeText(this, "无法启动语音识别", Toast.LENGTH_SHORT).show()
        }
    }

    /** 「本地音乐」是明确要进 App 的入口：打开资料库 */
    private fun openLocalMusic() {
        hideKeyboard()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(WidgetLaunch.EXTRA_OPEN_LIBRARY, true)
            }
        )
        finish()
    }

    private fun closePanel() {
        hideKeyboard()
        finish()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
    }

    /** 结果行：序号 / 歌名 / 「本地 · 歌手 · 专辑」 */
    private inner class ResultAdapter : BaseAdapter() {
        var items: List<UnifiedSong> = emptyList()
            private set

        fun submit(list: List<UnifiedSong>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_search_result, parent, false)
            val song = items[position]
            view.findViewById<TextView>(R.id.result_index).text = (position + 1).toString()
            view.findViewById<TextView>(R.id.result_title).text = song.title.ifBlank { "未知歌曲" }
            val isLocal = !song.localFilePath.isNullOrBlank()
            view.findViewById<TextView>(R.id.result_sub).text = listOfNotNull(
                if (isLocal) "本地" else null,
                song.artist.takeIf { it.isNotBlank() } ?: "未知歌手",
                song.album.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            // 行内播放圆钮与整行同一动作；不单独抢焦点，保证 ListView 的点击 / 遥控器确认正常
            view.findViewById<View>(R.id.result_play).apply {
                isFocusable = false
                isClickable = false
            }
            return view
        }
    }
}
