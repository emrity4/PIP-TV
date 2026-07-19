package com.emrity.piptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var pauseIndicator: View
    private var player: ExoPlayer? = null
    private lateinit var channelList: RecyclerView
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var emptyView: View
    private lateinit var nowPlayingBar: View
    private lateinit var nowPlayingText: TextView
    private lateinit var channelCount: TextView

    private val channels = mutableListOf<Channel>()
    private var currentIndex = -1
    private var adapter: ChannelAdapter? = null
    private var isListVisible = true

    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            scope = CoroutineScope(Dispatchers.Main)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)

            playerView = findViewById(R.id.player_view)
            pauseIndicator = findViewById(R.id.pause_indicator)
            channelList = findViewById(R.id.channel_list)
            loadingView = findViewById(R.id.loading_view)
            errorView = findViewById(R.id.error_view)
            errorText = findViewById(R.id.error_text)
            emptyView = findViewById(R.id.empty_view)
            nowPlayingBar = findViewById(R.id.now_playing_bar)
            nowPlayingText = findViewById(R.id.now_playing_text)
            channelCount = findViewById(R.id.channel_count)

            channelList.layoutManager = LinearLayoutManager(this)
            adapter = ChannelAdapter(channels) { index ->
                playChannel(index)
            }
            channelList.adapter = adapter

            findViewById<Button>(R.id.retry_button).setOnClickListener { loadPlaylist() }

            loadPlaylist()
        } catch (e: Exception) {
            setContentView(TextView(this).apply {
                text = "Error: ${e.message ?: e.javaClass.simpleName}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(40, 40, 40, 40)
            })
        }
    }

    private fun loadPlaylist() {
        showLoading(true)
        errorView.visibility = View.GONE
        emptyView.visibility = View.GONE

        scope?.launch {
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
            adapter?.notifyDataSetChanged()
            channelCount.text = "${channels.size} channels"
            if (channels.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                return@launch
            }
            playChannel(0)
        }
    }

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return
        currentIndex = index
        val channel = channels[index]

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
        playerView.useController = false

        adapter?.setActiveIndex(index)
        if (isListVisible) {
            channelList.smoothScrollToPosition(index)
        }

        scope?.launch {
            delay(3000)
            nowPlayingBar.animate().alpha(0f).setDuration(500).start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (isListVisible) {
                    adapter?.let { a ->
                        val focused = channelList.findFocus()
                        val pos = if (focused != null) channelList.getChildAdapterPosition(focused) else currentIndex
                        val next = (pos + step).coerceIn(0, channels.size - 1)
                        a.setActiveIndex(next)
                        channelList.getChildAt(next - (channelList.computeVerticalScrollOffset() / channelList.computeVerticalScrollRange()))?.requestFocus()
                    }
                } else {
                    val newIndex = (currentIndex + step).coerceIn(0, channels.size - 1)
                    playChannel(newIndex)
                }
                return true
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
                        Handler(Looper.getMainLooper()).postDelayed({ pauseIndicator.visibility = View.GONE }, 1500)
                    } else {
                        p.play()
                        pauseIndicator.visibility = View.GONE
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isListVisible) {
                    finish()
                    return true
                }
                toggleList()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleList() {
        isListVisible = !isListVisible
        channelList.animate()
            .alpha(if (isListVisible) 1f else 0f)
            .translationX(if (isListVisible) 0f else channelList.width.toFloat())
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
    }
}

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private var activeIndex = -1

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.channel_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.name.text = channel.name
        holder.view.isSelected = position == activeIndex
        holder.view.isFocusable = true
        holder.view.setOnClickListener { onClick(position) }

        holder.view.setOnFocusChangeListener { _, hasFocus ->
            holder.view.animate()
                .scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(150)
                .start()
            holder.name.typeface = if (hasFocus) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            holder.name.alpha = if (hasFocus || position == activeIndex) 1f else 0.55f
        }

        if (position == activeIndex && !holder.view.hasFocus()) {
            holder.view.requestFocus()
        }
    }

    override fun getItemCount() = channels.size

    fun setActiveIndex(index: Int) {
        val prev = activeIndex
        activeIndex = index
        if (prev >= 0) notifyItemChanged(prev)
        notifyItemChanged(index)
    }
}
