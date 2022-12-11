package dev.aaa1115910.bv.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import dev.aaa1115910.biliapi.BiliApi
import dev.aaa1115910.biliapi.entity.video.Dash
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.entity.DanmakuSize
import dev.aaa1115910.bv.entity.DanmakuTransparency
import dev.aaa1115910.bv.entity.VideoCodec
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.swapList
import dev.aaa1115910.bv.util.swapMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging

class PlayerViewModel : ViewModel() {
    var player: ExoPlayer? by mutableStateOf(null)
    var danmakuPlayer: DanmakuPlayer? by mutableStateOf(null)
    var show by mutableStateOf(false)

    var loadState by mutableStateOf(RequestState.Ready)
    var errorMessage by mutableStateOf("")

    var availableQuality = mutableStateMapOf<Int, String>()
    var availableVideoCodec = mutableStateListOf<VideoCodec>()
    var currentQuality by mutableStateOf(0)
    var currentVideoCodec by mutableStateOf(VideoCodec.AVC)
    var currentDanmakuSize by mutableStateOf(DanmakuSize.fromOrdinal(Prefs.defaultDanmakuSize))
    var currentDanmakuTransparency by mutableStateOf(DanmakuTransparency.fromOrdinal(Prefs.defaultDanmakuTransparency))
    var currentDanmakuEnabled by mutableStateOf(Prefs.defaultDanmakuEnabled)

    var danmakuData = mutableStateListOf<DanmakuItemData>()

    var dashData: Dash? = null
    var title by mutableStateOf("")

    var logs by mutableStateOf("")
    var showLogs by mutableStateOf(false)
    var showBuffering by mutableStateOf(false)

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    fun preparePlayer(player: ExoPlayer) {
        logger.info { "Set player" }
        this.player = player
        show = true
    }

    fun prepareDanmakuPlayer(danmakuPlayer: DanmakuPlayer) {
        logger.info { "Set danmaku plauer" }
        this.danmakuPlayer = danmakuPlayer
    }

    init {
        initData()
    }

    fun initData() {

    }

    fun loadPlayUrl(
        avid: Int,
        cid: Int
    ) {
        showLogs = true
        addLogs("加载视频中")
        viewModelScope.launch(Dispatchers.Default) {
            addLogs("av$avid，cid:$cid")
            loadPlayUrl(avid, cid, 4048)
            addLogs("加载弹幕中")
            loadDanmaku(cid)
        }
    }

    private suspend fun loadPlayUrl(
        avid: Int,
        cid: Int,
        fnval: Int = 4048,
        qn: Int = 80,
        fnver: Int = 0,
        fourk: Int = 0
    ) {
        logger.info { "Load play url: [av=$avid, cid=$cid, fnval=$fnval, qn=$qn, fnver=$fnver, fourk=$fourk]" }
        loadState = RequestState.Ready
        logger.info { "Set request state: ready" }
        runCatching {
            val responseData = BiliApi.getVideoPlayUrl(
                av = avid,
                cid = cid,
                fnval = fnval,
                qn = qn,
                fnver = fnver,
                fourk = fourk,
                sessData = Prefs.sessData
            ).getResponseData()
            logger.info { "Load play url response: $responseData" }

            //读取清晰度
            val resolutionMap = mutableMapOf<Int, String>()
            responseData.dash?.video?.forEach {
                if (!resolutionMap.containsKey(it.id)) {
                    val index = responseData.acceptQuality.indexOf(it.id)
                    resolutionMap[it.id] = responseData.acceptDescription[index]
                }
            }

            logger.info { "Video available resolution: $resolutionMap" }
            availableQuality.swapMap(resolutionMap)

            currentQuality = Prefs.defaultQuality
            currentVideoCodec = Prefs.defaultVideoCodec

            //先确认最终所选清晰度
            val existDefaultResolution =
                availableQuality.keys.find { it == Prefs.defaultQuality } != null

            if (!existDefaultResolution) {
                val tempList = resolutionMap.keys.sorted()
                currentQuality = tempList.first()
                tempList.forEach {
                    if (it <= Prefs.defaultQuality) {
                        currentQuality = it
                    }
                }
            }

            val currentResolutionInfo =
                responseData.supportFormats.find { it.quality == currentQuality }

            //再确认最终所选视频编码
            val codecList = currentResolutionInfo!!.codecs!!
                .mapNotNull { VideoCodec.fromCodecString(it) }
            availableVideoCodec.swapList(codecList)

            currentVideoCodec = if (codecList.contains(Prefs.defaultVideoCodec)) {
                Prefs.defaultVideoCodec
            } else {
                codecList.minByOrNull { it.ordinal }!!
            }

            dashData = responseData.dash!!

            playQuality(qn = currentQuality, codec = currentVideoCodec)

        }.onFailure {
            addLogs("加载视频地址失败：${it.localizedMessage}")
            errorMessage = it.stackTraceToString()
            loadState = RequestState.Failed
            logger.warn { "Load video filed: ${it.message}" }
            logger.error { it.stackTraceToString() }
        }.onSuccess {
            addLogs("加载视频地址成功")
            loadState = RequestState.Success
            logger.warn { "Load play url success" }
        }
    }

    suspend fun playQuality(qn: Int = 80, codec: VideoCodec = currentVideoCodec) {
        showLogs = true
        addLogs("播放清晰度：${availableQuality[qn]}, 视频编码：${codec.getDisplayName(BVApp.context)}")
        val videoUrl = dashData!!.video
            .find { it.id == qn && it.codecs.startsWith(codec.prefix) }
            ?.baseUrl
            ?: dashData!!.video[0].baseUrl
        val audioUrl = dashData!!.audio
            .find { it.id == qn }
            ?.baseUrl
            ?: dashData!!.audio[0].baseUrl
        val videoMediaItem = MediaItem.fromUri(videoUrl)
        val audioMediaItem = MediaItem.fromUri(audioUrl)

        val userAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.0.0 Safari/537.36"
        val defaultHttpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(
                mapOf(
                    "referer" to "https://www.bilibili.com"
                )
            )

        val videoMediaSource =
            ProgressiveMediaSource.Factory(defaultHttpDataSourceFactory)
                .createMediaSource(videoMediaItem)
        val audioMediaSource =
            ProgressiveMediaSource.Factory(defaultHttpDataSourceFactory)
                .createMediaSource(audioMediaItem)
        //set data
        val mms = MergingMediaSource(videoMediaSource, audioMediaSource)

        withContext(Dispatchers.Main) {
            player!!.setMediaSource(mms)
            player!!.prepare()
            player!!.playWhenReady = true
            showBuffering = true
        }
    }

    suspend fun loadDanmaku(cid: Int) {
        runCatching {
            val danmakuXmlData = BiliApi.getDanmakuXml(cid = cid, sessData = Prefs.sessData)
            danmakuData.addAll(danmakuXmlData.data.map {
                DanmakuItemData(
                    danmakuId = it.dmid,
                    position = (it.time * 1000).toLong(),
                    content = it.text,
                    mode = when (it.type) {
                        4 -> DanmakuItemData.DANMAKU_MODE_CENTER_TOP
                        5 -> DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
                        else -> DanmakuItemData.DANMAKU_MODE_ROLLING
                    },
                    textSize = it.size,
                    textColor = Color(it.color).toArgb()
                )
            })
            danmakuPlayer?.updateData(danmakuData)
        }.onFailure {
            addLogs("加载弹幕失败：${it.localizedMessage}")
            logger.warn { "Load danmaku filed: ${it.message}" }
        }.onSuccess {
            addLogs("已加载 ${danmakuData.size} 条弹幕")
            logger.warn { "Load danmaku success: ${danmakuData.size}" }
        }
    }

    private fun addLogs(text: String) {
        val lines = logs.lines().toMutableList()
        lines.add(text)
        while (lines.size > 8) {
            lines.removeAt(0)
        }
        var newTip = ""
        lines.forEach {
            newTip += if (newTip == "") it else "\n$it"
        }
        logs = newTip
    }
}

enum class RequestState {
    Ready, Doing, Done, Success, Failed
}