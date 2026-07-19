package com.emrity.piptv

import android.os.Bundle
import android.view.*
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AdapterItem {
    data class Header(val name: String) : AdapterItem()
    data class ChannelItem(val channel: Channel) : AdapterItem()
}

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var pauseIndicator: View
    private var player: ExoPlayer? = null
    private lateinit var channelList: RecyclerView
    private lateinit var sidebar: View
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var emptyView: View
    private lateinit var nowPlayingBar: View
    private lateinit var nowPlayingText: TextView
    private lateinit var channelCount: TextView
    private lateinit var splashView: WebView

    private val channels = mutableListOf<Channel>()
    private var currentItems = listOf<AdapterItem>()
    private var currentChannel: Channel? = null
    private var adapter: GroupedChannelAdapter? = null
    private var isListVisible = false
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        pauseIndicator = findViewById(R.id.pause_indicator)
        sidebar = findViewById(R.id.sidebar)
        channelList = findViewById(R.id.channel_list)
        loadingView = findViewById(R.id.loading_view)
        errorView = findViewById(R.id.error_view)
        errorText = findViewById(R.id.error_text)
        emptyView = findViewById(R.id.empty_view)
        nowPlayingBar = findViewById(R.id.now_playing_bar)
        nowPlayingText = findViewById(R.id.now_playing_text)
        channelCount = findViewById(R.id.channel_count)

        channelList.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.hamburger_button).setOnClickListener { toggleList() }
        findViewById<View>(R.id.exit_button).setOnClickListener { finishAffinity() }
        findViewById<Button>(R.id.retry_button).setOnClickListener { loadPlaylist() }

        splashView = findViewById(R.id.splash_view)
        splashView.settings.javaScriptEnabled = true
        splashView.setBackgroundColor(0xFF0A0A0B.toInt())
        splashView.loadUrl("file:///android_asset/splash.html")

        loadPlaylist()

        scope.launch {
            delay(6000)
            splashView.animate().alpha(0f).setDuration(500).withEndAction {
                splashView.visibility = View.GONE
            }
        }
    }

    private fun buildGroupedItems(): List<AdapterItem> {
        val sorted = channels.sortedBy { it.name.lowercase() }
        val grouped = sorted.groupBy { it.category }
        val items = mutableListOf<AdapterItem>()
        for (cat in listOf("News", "Sports", "Religious", "General")) {
            val catChannels = grouped[cat] ?: continue
            if (catChannels.isEmpty()) continue
            items.add(AdapterItem.Header(cat))
            items.addAll(catChannels.map { AdapterItem.ChannelItem(it) })
        }
        val others = grouped.filterKeys { it !in setOf("News", "Sports", "Religious", "General") }.values.flatten()
        if (others.isNotEmpty()) {
            items.add(AdapterItem.Header("Other"))
            items.addAll(others.map { AdapterItem.ChannelItem(it) })
        }
        return items
    }

    private fun loadPlaylist() {
        showLoading(true)
        errorView.visibility = View.GONE
        emptyView.visibility = View.GONE

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PlaylistParser.load(this@MainActivity, getString(R.string.default_playlist_url))
            }
            showLoading(false)
            if (result.error != null) {
                showError(result.error)
                return@launch
            }
            channels.clear()
            channels.addAll(result.channels)
            if (channels.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                return@launch
            }
            currentItems = buildGroupedItems()
            adapter = GroupedChannelAdapter(currentItems) { channel ->
                playChannel(channel)
            }
            channelList.adapter = adapter
            channelCount.text = "${channels.size} channels"
            playChannel(channels.first())
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel

        nowPlayingText.text = channel.name
        nowPlayingBar.visibility = View.VISIBLE
        nowPlayingBar.alpha = 1f
        nowPlayingBar.animate().cancel()

        player?.release()
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(channel.url))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@MainActivity, getString(R.string.error_playing), Toast.LENGTH_SHORT).show()
                }
            })
        }
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)

        adapter?.setActiveChannel(channel)
        val chPos = currentItems.indexOfFirst { it is AdapterItem.ChannelItem && it.channel.url == channel.url }
        if (chPos >= 0 && isListVisible) {
            channelList.smoothScrollToPosition(chPos)
        }

        scope.launch {
            delay(3000)
            nowPlayingBar.animate().alpha(0f).setDuration(500).start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isListVisible) {
                    val sortedChannels = channels.sortedBy { it.name.lowercase() }
                    val idx = currentChannel?.let { c -> sortedChannels.indexOfFirst { it.url == c.url } } ?: -1
                    if (idx >= 0) {
                        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        val next = (idx + step).coerceIn(0, sortedChannels.size - 1)
                        playChannel(sortedChannels[next])
                    }
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isListVisible) toggleList()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isListVisible) toggleList()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                        pauseIndicator.visibility = View.VISIBLE
                        scope.launch {
                            delay(1500)
                            pauseIndicator.visibility = View.GONE
                        }
                    } else {
                        p.play()
                        pauseIndicator.visibility = View.GONE
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isListVisible) {
                    toggleList()
                    return true
                }
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleList() {
        isListVisible = !isListVisible
        sidebar.animate()
            .translationX(if (isListVisible) 0f else -sidebar.width.toFloat())
            .setDuration(200)
            .start()
    }

    private fun showLoading(show: Boolean) {
        loadingView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorView.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        scope.cancel()
    }
}

class GroupedChannelAdapter(
    private val items: List<AdapterItem>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    private var activeChannel: Channel? = null

    class ChannelViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.channel_name)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.ChannelItem -> TYPE_CHANNEL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_header, parent, false)
            object : RecyclerView.ViewHolder(view) {}
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            ChannelViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AdapterItem.Header -> {
                (holder.itemView as TextView).text = item.name
            }
            is AdapterItem.ChannelItem -> {
                val ch = holder as ChannelViewHolder
                ch.name.text = item.channel.name
                val isActive = item.channel.url == activeChannel?.url
                ch.view.isSelected = isActive
                ch.view.isFocusable = true
                ch.view.setOnClickListener { onClick(item.channel) }

                ch.view.setOnFocusChangeListener { _, hasFocus ->
                    ch.view.animate()
                        .scaleX(if (hasFocus) 1.05f else 1f)
                        .scaleY(if (hasFocus) 1.05f else 1f)
                        .setDuration(150)
                        .start()
                    ch.name.typeface = if (hasFocus) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    ch.name.alpha = if (hasFocus || isActive) 1f else 0.55f
                }

                if (isActive && !ch.view.hasFocus()) {
                    ch.view.requestFocus()
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun setActiveChannel(channel: Channel) {
        activeChannel = channel
        notifyDataSetChanged()
    }
}
