/*
    Copyright 2020 Udit Karode <udit.karode@gmail.com>

    This file is part of AbleMusicPlayer.

    AbleMusicPlayer is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, version 3 of the License.

    AbleMusicPlayer is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with AbleMusicPlayer.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.github.uditkarode.able.services

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.glidebitmappool.GlideBitmapFactory
import com.glidebitmappool.GlideBitmapPool
import io.github.uditkarode.able.R
import io.github.uditkarode.able.activities.MainActivity
import io.github.uditkarode.able.activities.Player
import io.github.uditkarode.able.events.*
import io.github.uditkarode.able.models.Song
import io.github.uditkarode.able.models.SongState
import io.github.uditkarode.able.utils.Constants
import io.github.uditkarode.able.utils.Shared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The service that plays music.
 */
class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {
    companion object {
        val mediaPlayer = MediaPlayer()
        var currentIndex = -1
        var previousIndex=-1
        private var onShuffle = false
        private var onRepeat = false
        private var coverArtHeight: Int? = null
        var songCoverArt: WeakReference<Bitmap>? = null
        var playQueue = ArrayList<Song>()
        private var isInstantiated = false
        private var ps = PlaybackState.Builder()
        private var builder: Notification.Builder? = null
        private var wakeLock: PowerManager.WakeLock? = null
        private lateinit var mediaSession: MediaSession
        private lateinit var notificationManager: NotificationManager
        private var focusRequest: AudioFocusRequest? = null
    }

    private val binder = MusicBinder(this@MusicService)

    fun getMediaPlayer() = mediaPlayer
    fun getPlayQueue() = playQueue
    fun getCurrentIndex() = currentIndex
    fun getPreviousIndex() = previousIndex
    fun setPlayQueue(arrayList: ArrayList<Song>){
        playQueue = arrayList
    }

    fun setCurrentIndex(ind: Int){
        currentIndex = ind
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            setPlayPause(SongState.paused)
        }
    }

    private val actions: Long = (PlaybackState.ACTION_PLAY
            or PlaybackState.ACTION_PAUSE
            or PlaybackState.ACTION_PLAY_PAUSE
            or PlaybackState.ACTION_SKIP_TO_NEXT
            or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            or PlaybackState.ACTION_STOP
            or PlaybackState.ACTION_SEEK_TO)

    override fun onCreate() {
        super.onCreate()

        registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(false)
                setOnAudioFocusChangeListener {
                    if (it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || it == AudioManager.AUDIOFOCUS_LOSS) pauseAudio()
                }
                build()
            }
        }

        GlideBitmapPool.initialize(1 * 1024 * 1024)

        if(coverArtHeight == null)
            coverArtHeight = resources.getDimension(R.dimen.top_art_height).toInt()

        if(!isInstantiated){
            notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }

        mediaSession = MediaSession(this, "AbleSession")

        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos)
                seekTo(pos.toInt())
            }

            override fun onPause() {
                super.onPause()
                onPause()
            }

            override fun onPlay() {
                super.onPlay()
                playAudio()
            }
        })

        mediaPlayer.setOnErrorListener { _, _, _ ->
            true
        }

        mediaPlayer.setOnCompletionListener {
            nextSong()

        }

        if(builder == null){
            builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "10002")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val intent = Intent(this, MusicService::class.java)
            intent.action = "ACTION_STOP"
            val style = Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
            style.setShowActionsInCompactView(0, 1, 2, 3)

            (builder as Notification.Builder)
                .setSmallIcon(R.drawable.ic_notification)
                .setSubText(getString(R.string.music))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, Player::class.java),
                        0
                    )
                )
                .setDeleteIntent(PendingIntent.getService(this, 1, intent, 0))
                .setOngoing(true)
                .style = style
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().post(ExitEvent())
        EventBus.getDefault().unregister(this)
        unregisterReceiver(receiver)
        exitProcess(0)
    }

    class MusicBinder(private val service: MusicService) : Binder() {
        fun getService(): MusicService {
            return service
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action == null) return
        val action = intent.action
        when {
            action.equals("ACTION_PLAY", ignoreCase = true) -> {
                mediaSessionPlay()
                setPlayPause(SongState.playing)
            }
            action.equals("ACTION_PAUSE", ignoreCase = true) -> {
                setPlayPause(SongState.paused)
            }
            action.equals("ACTION_PREVIOUS", ignoreCase = true) -> {
                setNextPrevious(next = false)
            }
            action.equals("ACTION_NEXT", ignoreCase = true) -> {
                setNextPrevious(next = true)
            }
            action.equals("ACTION_KILL", ignoreCase = true) -> {
                cleanUp()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    stopForeground(true)
                stopSelf()
            }
        }
    }

    /**
     * @param song the song to be added to the play queue.
     */
    fun addToQueue(song: Song) {
        addToPlayQueue(song)
        EventBus.getDefault().post(GetQueueEvent(playQueue))
    }

    /**
     * @param queue an ArrayList of Song objects to be set as the new queue.
     */
    fun setQueue(queue: ArrayList<Song>) {
        playQueue = queue
        EventBus.getDefault().post(GetQueueEvent(playQueue))
    }

    /**
     * @param shuffle boolean value for turning shuffle on/off.
     * @param repeat value for turning repeat on/off.
     */
    fun setShuffleRepeat(shuffle: Boolean, repeat: Boolean) {
        setShuffle(shuffle)
        onRepeat = repeat
        EventBus.getDefault().post(GetShuffleRepeatEvent(onShuffle, onRepeat))
    }

    /**
     * @param index sets the new index to play from the queue.
     */
    fun setIndex(index: Int) {
        previousIndex= currentIndex
        currentIndex = index
        songChanged()
        EventBus.getDefault().post(GetIndexEvent(currentIndex))
    }

    /**
     * @param state a SongState object used to play or pause audio.
     */
    fun setPlayPause(state: SongState) {
        if (state == SongState.playing) playAudio()
        else pauseAudio()

        EventBus.getDefault().post(GetPlayPauseEvent(state))
    }

    /**
     * @param next changes to the next song if true, previous song if false.
     */
    fun setNextPrevious(next: Boolean) {
        previousIndex= currentIndex
        if (next) nextSong()
        else previousSong()
    }

    /* Music Related Helper Functions Here */

    /**
     * @param enabled enables or disables shuffle.
     */
    private fun setShuffle(enabled: Boolean) {
        if (enabled) {
            val detachedSong = playQueue[currentIndex]
            playQueue.removeAt(currentIndex)
            playQueue.shuffle()
            playQueue.add(0, detachedSong)
            currentIndex = 0
            onShuffle = true
        } else {
            onShuffle = false
            val currSong = playQueue[currentIndex]
            playQueue = ArrayList(playQueue.sortedBy { it.name.toUpperCase() })
            currentIndex = playQueue.indexOf(currSong)
        }

        EventBus.getDefault().post(GetQueueEvent(playQueue))
        EventBus.getDefault().post(GetIndexEvent(currentIndex))
    }

    /**
     * changes to the previous song
     */
    private fun previousSong() {
        if (mediaPlayer.currentPosition > 2000) {
            seekTo(0)
        } else {
            if (currentIndex == 0) currentIndex = playQueue.size - 1
            else currentIndex--
            songChanged()
            playAudio()
        }
    }

    /**
     * changes to the next song
     */
    private fun nextSong() {
        if (onRepeat) seekTo(0)
        if (currentIndex + 1 < playQueue.size) {
            if (!onRepeat) currentIndex++
            songChanged()
            playAudio()
        } else {
            currentIndex = 0
            songChanged()
            playAudio()
        }
    }

    /**
     * @param song the song to be added to the play queue.
     */
    private fun addToPlayQueue(song: Song) {
        if (currentIndex != playQueue.size - 1) playQueue.add(currentIndex + 1, song)
        else playQueue.add(song)
    }

    /**
     * @param position the position to seek the current song to.
     */
    fun seekTo(position: Int) {
        mediaPlayer.seekTo(position)
        mediaSessionPlay()
    }
    private fun mediaSessionPlay()
    {
        mediaSession.setPlaybackState(
            ps
                .setActions(actions)
                .setState(
                    PlaybackState.STATE_PLAYING,
                    mediaPlayer.currentPosition.toLong(), 1f
                )
                .build()
        )
    }

    /**
     * changes the current song to playQueue[currentIndex] and starts playing it.
     */
    private fun songChanged() {
        if (!isInstantiated) isInstantiated = true
        else {
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.reset()
        }
        if (playQueue[currentIndex].filePath == "") {
            thread {
                streamAudio()
            }
        }
        else {
            try {
                mediaPlayer.setDataSource(playQueue[currentIndex].filePath)//Inside Try To Handle in case File is not found but still shows in songList
                mediaPlayer.prepareAsync()
                EventBus.getDefault().post(GetSongChangedEvent())
                EventBus.getDefault().post(GetIndexEvent(currentIndex))
                mediaPlayer.setOnPreparedListener{
                    EventBus.getDefault().post(GetDurationEvent(mediaPlayer.duration))
                    mediaSessionPlay()//start notification seekbar after prepared, if not in onPrepared, then seekbar start moving before song starts playing
                    setPlayPause(SongState.playing)
                }
            } catch (e: java.lang.Exception){
                Log.e("ERR>", "$e")
            }
        }
    }

    /* user might sleep with songs on, let it jam */
    @SuppressLint("WakelockTimeout")

    /**
     * Starts playing audio (after pause).
     * Acquires a wakelock in the progress.
     */
    private fun playAudio() {
        val audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (wakeLock?.isHeld != true) {
                wakeLock =
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                        newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AbleMusic::lock").apply {
                            acquire()
                        }
                    }
            }


            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, mediaPlayer.duration.toLong())
                    .build()
            )

           showNotification(
                generateAction(
                    R.drawable.notif_pause,
                    getString(R.string.pause),
                    "ACTION_PAUSE"
                )
            )

            if (!mediaPlayer.isPlaying) {
                try {
                    thread {
                        mediaPlayer.start()
                    }
                } catch (e: Exception) {
                    Log.e("ERR>", "-$e-")
                }
            }
        } else {
            Log.e("ERR>", "Unable to get focus - $result")
        }
    }

    fun showNotif() {
        showNotification(
            generateAction(
                R.drawable.notif_play,
                getString(R.string.pause),
                "ACTION_PLAY"
            )
        )
    }

    /**
     * used to pause audio play. Abandons audio focus and releases held wakelock.
     */
    private fun pauseAudio() {
        val audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus {}
        }

        if (wakeLock?.isHeld == true)
            wakeLock?.release()

        showNotification(
            generateAction(
                R.drawable.notif_play,
                getString(R.string.pause),
                "ACTION_PLAY"
            )
        )

        mediaSession.setPlaybackState(
            ps
                .setActions(actions)
                .setState(
                    PlaybackState.STATE_PAUSED,
                    mediaPlayer.currentPosition.toLong(), 1f
                )
                .build()
        )

        mediaPlayer.pause()
        EventBus.getDefault().post(GetPlayPauseEvent(run {
            if (mediaPlayer.isPlaying) SongState.playing
            else SongState.paused
        }))
    }

    /**
     * releases the media session and wakelock and gets ready to die.
     */
    private fun cleanUp() {
        EventBus.getDefault().post(ExitEvent())
        EventBus.getDefault().unregister(this)
        mediaPlayer.stop()
        mediaSession.release()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also {
            it.cancel(1)
        }
        if (wakeLock?.isHeld == true)
            wakeLock?.release()
    }

    /**
     * @param file the File object pointing to the album art image.
     *
     */
    private fun getAlbumArt(file: File) {
        val bitmap = GlideBitmapFactory.decodeFile(file.absolutePath)
        songCoverArt = WeakReference(if (bitmap.height > coverArtHeight!! * 2) {
            val ratio = bitmap.width / bitmap.height.toFloat()
            Bitmap.createScaledBitmap(bitmap, (coverArtHeight!! * ratio).toInt(), coverArtHeight!!, false)
        } else {
            bitmap
        })
    }

    fun generateAction(
        icon: Int,
        title: String,
        intentAction: String
    ): Notification.Action {
        val intent = Intent(this, MusicService::class.java)
        intent.action = intentAction
        val pendingIntent =
            PendingIntent.getService(this, 1, intent, 0)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icon, title, pendingIntent).build()
    }

    fun showNotification(
        action: Notification.Action,
        image: Bitmap? = null
    ) {
        try {
            if (image == null) {
                File(
                    Constants.ableSongDir.absolutePath + "/album_art",
                    File(playQueue[currentIndex].filePath).nameWithoutExtension
                ).also {
                    if (it.exists() && it.isFile) {
                        getAlbumArt(it)
                    }
                }

                File(
                    Constants.ableSongDir.absolutePath + "/cache",
                    "sCache" + Shared.getIdFromLink(playQueue[currentIndex].youtubeLink)
                ).also {
                    if (it.exists() && it.isFile) {
                        getAlbumArt(it)
                    }
                }
                builder?.setLargeIcon(songCoverArt?.get())
            }
        }
        catch(e:java.lang.Exception){
            e.printStackTrace()
        }

        if (songCoverArt == null || songCoverArt?.get()?.isRecycled == true) {
           /* songCoverArt = WeakReference(
                GlideBitmapFactory.decodeResource(
                    this.resources,
                     R.drawable.def_albart
                )
            )   Reason: Freezes Notification for 1sec as it fetches the bitmap everytime */
            builder?.setLargeIcon(Shared.defBitmap) //Reduces ram usage as we already have the Bitmap
        }

        builder?.setContentTitle(playQueue[currentIndex].name)
        builder?.setContentText(playQueue[currentIndex].artist)

        /* clear actions */
        try {
            val f: Field = builder!!.javaClass.getDeclaredField("mActions")
            f.isAccessible = true
            f.set(builder, ArrayList<Notification.Action>())
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }

        builder?.addAction(
            generateAction(
                R.drawable.notif_previous,
                getString(R.string.prev),
                "ACTION_PREVIOUS"
            )
        )

        builder?.addAction(action)

        builder?.addAction(
            generateAction(
                R.drawable.notif_next,
                getString(R.string.next),
                "ACTION_NEXT"
            )
        )

        builder?.addAction(
            generateAction(
                R.drawable.kill,
                getString(R.string.kill),
                "ACTION_KILL"
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                "10002",
                getString(R.string.music),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationChannel.enableLights(false)
            notificationChannel.enableVibration(false)
            notificationManager.createNotificationChannel(notificationChannel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, builder?.setChannelId("10002")?.build())
        } else {
            notificationManager.notify(1, builder?.build())
        }

        songCoverArt?.get()?.recycle()
        songCoverArt = null
    }

    override fun onAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pauseAudio()
        else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) cleanUp()
    }

    /**
     * if the filePath of the current song is empty,
     * it is assumed that it contains a youtubeLink.
     * fetches the album art and streams the song.
     */
    private fun streamAudio() {
        try {
            val song = playQueue[currentIndex]
            val streamInfo = StreamInfo.getInfo(song.youtubeLink)
            val stream = streamInfo.audioStreams.run { this[this.size - 1] }

            if(song.ytmThumbnail.isNotBlank()){
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(song.ytmThumbnail)
                    .signature(ObjectKey("save"))
                    .listener(object: RequestListener<Bitmap> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Bitmap>?,
                            isFirstResource: Boolean
                        ): Boolean { return false }

                        override fun onResourceReady(
                            resource: Bitmap?,
                            model: Any?,
                            target: Target<Bitmap>?,
                            dataSource: DataSource?,
                            isFirstResource: Boolean
                        ): Boolean {
                            if(resource != null)
                                Shared.saveStreamingAlbumArt(resource, Shared.getIdFromLink(song.youtubeLink))
                            return false
                        }
                    }).submit()

                val url = stream.url
                playQueue[currentIndex].filePath = url
                songChanged()
            }
        } catch (e: java.lang.Exception) {
            nextSong()
        }
    }
}
