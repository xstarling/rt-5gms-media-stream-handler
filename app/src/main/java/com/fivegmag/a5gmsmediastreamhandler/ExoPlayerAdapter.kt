/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediastreamhandler
import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.Player

import com.fivegmag.a5gmscommonlibrary.helpers.PlayerStates
import com.fivegmag.a5gmscommonlibrary.helpers.StatusInformation
import com.fivegmag.a5gmsmediastreamhandler.helpers.mapStateToConstant
import com.google.android.exoplayer2.ExoPlayerLibraryInfo.TAG


// 顶部的 import 需要加上这些：
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import java.util.UUID

class ExoPlayerAdapter() {

    private lateinit var playerInstance: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var activeMediaItem: MediaItem
    private lateinit var playerListener: ExoPlayerListener
    private lateinit var bandwidthMeter: DefaultBandwidthMeter
    private lateinit var mediaSessionHandlerAdapter: MediaSessionHandlerAdapter

    fun initialize(
        exoPlayerView: StyledPlayerView,
        context: Context,
        msh: MediaSessionHandlerAdapter
    ) {
        mediaSessionHandlerAdapter = msh
        /**
         * 使用官方的CMCD请求改动太大了，考虑到咱们当前gradle是7.4的，升级为8.0+有风险，故放弃此修改，采用拦截器处理

        /** 添加cmcd请求f**/

        // 1. 创建 Media3 专属的 CMCD 配置工厂
        val cmcdConfigurationFactory = CmcdConfiguration.Factory { mediaItem ->
            CmcdConfiguration.Builder(UUID.randomUUID().toString()) // 随机生成 Session ID
                // 🚨 核心魔法：强制使用 Query 模式！这样 Nginx 才能抓到 ?CMCD=...
                .setTransmissionMode(CmcdConfiguration.MODE_QUERY_PARAMETER)

                // 可选：允许上报所有标准的 CMCD 字段
                .setRequestConfig(object : CmcdConfiguration.RequestConfig {
                    override fun isKeyAllowed(key: String): Boolean {
                        return true
                    }
                })
                .build()
        }

        // 2. 将 CMCD 配置绑定到 Media3 的工厂上
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setCmcdConfigurationFactory(cmcdConfigurationFactory)


        /** 添加cmcd请求结束 **/
        */

        // 在拦截器外部生成唯一的 Session ID
        // 保证在当前这个 Player 的生命周期内，sid 是恒定不变的
        val currentSessionId = UUID.randomUUID().toString()

        // 自定义动态拦截器
        val cmcdInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()  // 获取请求对象
            val originalUrl = originalRequest.url
            val urlString = originalUrl.toString()

            // 核心过滤：只拦截流媒体切片和清单文件
            if (urlString.endsWith(".m4s") || urlString.endsWith(".mpd") || urlString.endsWith(".mp4")) {

                // 动态提取 cid：获取 URL 的最后一部分的视频名作为cid
                val dynamicCid = originalUrl.pathSegments.last()

                // 动态拼接 CMCD 字符串
                val cmcdString = "cid=\"$dynamicCid\",sid=\"$currentSessionId\",st=v,sf=d"

                // 将拼接好的字符串塞入 URL 参数
                val newUrl = originalUrl.newBuilder()
                    .addQueryParameter("CMCD", cmcdString)
                    .build()

                // 添加打印日志方便排查
                Log.d(TAG, "initialize-request-CMCD-handler: " + newUrl.toString());
                // 用新的 URL 发起请求
                val newRequest = originalRequest.newBuilder().url(newUrl).build()
                return@Interceptor chain.proceed(newRequest)
            }

            // 普通请求直接放行
            return@Interceptor chain.proceed(originalRequest)
        }

        // 把拦截器装进OkHttpClient对象中
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(cmcdInterceptor)
            .build()

        // 封装 ExoPlayer的请求到数据源对象，修改为当前的添加了自定义拦截器的请求对象
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        playerInstance = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)  // 绑定数据源请求对象到ExoPlayer中
            .build()
        bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        playerView = exoPlayerView
        playerView.player = playerInstance
        playerListener = ExoPlayerListener(mediaSessionHandlerAdapter, playerInstance)
        playerInstance.addListener(playerListener)
    }

    fun attach(url: String) {
        val mediaItem: MediaItem = MediaItem.fromUri(url)
        playerInstance.setMediaItem(mediaItem)
        activeMediaItem = mediaItem
    }

    fun preload() {
        playerInstance.prepare()
    }

    fun play() {
        playerInstance.play()
    }

    fun pause() {
        playerInstance.pause()
    }

    fun seek(time: Long) {
        TODO("Not yet implemented")
    }

    fun stop() {
        playerInstance.stop()
    }

    fun reset() {
        TODO("Not yet implemented")
    }

    fun destroy() {
        playerInstance.release()
    }

    fun getPlayerInstance(): ExoPlayer {
        return playerInstance
    }

    fun getPlaybackState(): Int {
        return playerInstance.playbackState
    }

    private fun getAverageThroughput(): Long {
        return bandwidthMeter.bitrateEstimate
    }

    private fun getBufferLength(): Long {
        return playerInstance.totalBufferedDuration
    }

    private fun getLiveLatency(): Long {
        return playerInstance.currentLiveOffset
    }

    fun getStatusInformation(status: String): Any? {
        when (status) {
            StatusInformation.AVERAGE_THROUGHPUT -> return getAverageThroughput()
            StatusInformation.BUFFER_LENGTH -> return getBufferLength()
            StatusInformation.LIVE_LATENCY -> return getLiveLatency()
            else -> {
                return null
            }
        }
    }

    fun getPlayerState(): String {
        val state: String?
        if (playerInstance.isPlaying) {
            state = PlayerStates.PLAYING
        } else if (playerInstance.playbackState == Player.STATE_READY && !playerInstance.playWhenReady) {
            state = PlayerStates.PAUSED
        } else {
            state = mapStateToConstant(playerInstance.playbackState)
        }

        return state
    }
}