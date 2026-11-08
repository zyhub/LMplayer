package com.lm.player.feature.podcast

import com.lm.player.core.podcast.*

/**
 * 播客模块全局预览数据提供器
 *
 * 涵盖全数据态、收听中、已听完、新单集角标、空数据、服务未配置、报错态等多种场景。
 */
object PodcastPreviewData {

    val sampleShow1 = PodcastShowUi(
        id = "show_1",
        title = "故事FM",
        author = "寇爱哲",
        description = "故事FM 是一档由亲历者自述的声音记录节目。我们用你的声音，讲述你的故事。每周一、三、五准时在各大音频平台更新。",
        imageUrl = "https://images.unsplash.com/photo-1478737270239-2f02b77fc618?w=400",
        episodeCount = 820,
        newEpisodeCount = 2,
        updatedText = "昨天更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://feed.gushifm.net/rss"
    )

    val sampleShow2 = PodcastShowUi(
        id = "show_2",
        title = "日谈公园",
        author = "李叔 & 小伙子",
        description = "日谈公园，2016年开播的脱口秀播客，畅聊电影、音乐、生活与八卦，陪伴无数年轻人度过通勤与独处时光。",
        imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400",
        episodeCount = 564,
        newEpisodeCount = 1,
        updatedText = "2天前更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://rss.ritanpark.com/feed"
    )

    val sampleShow3 = PodcastShowUi(
        id = "show_3",
        title = "机核 GADIO 游戏电台",
        author = "机核网",
        description = "机核网官方播客，分享关于游戏开发幕后故事、亚文化、电影与独立游戏的深度漫谈与热烈讨论。",
        imageUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=400",
        episodeCount = 1205,
        newEpisodeCount = 0,
        updatedText = "4天前更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://www.gcores.com/rss"
    )

    val sampleShowWithError = PodcastShowUi(
        id = "show_error",
        title = "海外独立播客源 (失效示例)",
        author = "Independent Host",
        description = "测试抓取失败的节目源展示效果。",
        imageUrl = "",
        episodeCount = 12,
        newEpisodeCount = 0,
        updatedText = "2周前",
        errorText = "服务器抓取 RSS 失败：连接超时 (504 Gateway Timeout)",
        feedUrl = "https://example.com/broken-podcast.xml"
    )

    val sampleEpisode1 = PodcastEpisodeUi(
        id = "ep_1",
        showId = sampleShow1.id,
        showTitle = sampleShow1.title,
        title = "第789期：我在大兴安岭守林三十年",
        description = "【本期讲述者】老张（大兴安岭某林业局巡护员）\n【主播】寇爱哲\n\n在大兴安岭深处，每到冬季零下四十度的大雪封山期，整座山林只剩下风声和踩在积雪上的咯吱声。老张在这里工作了整整三十二年，巡山护林、观测珍稀野生动植物迁徙。\n\n今天，他就为我们揭开那些在大雪深处的神秘见闻……",
        imageUrl = sampleShow1.imageUrl,
        publishedText = "3天前",
        durationText = "42分钟",
        remainingText = "剩余 18 分钟",
        progress = 0.42f,
        playState = EpisodePlayState.IN_PROGRESS,
        isNew = false,
        isCurrent = true,
        isPlaying = true
    )

    val sampleEpisode2 = PodcastEpisodeUi(
        id = "ep_2",
        showId = sampleShow1.id,
        showTitle = sampleShow1.title,
        title = "第790期：我和野生东北虎四目相对的三十秒",
        description = "在林区深处的红外相机布设途中，护林员偶遇成年东北虎巡视领地，那是生死一瞬的三十秒……",
        imageUrl = sampleShow1.imageUrl,
        publishedText = "今天",
        durationText = "38分钟",
        remainingText = "",
        progress = 0f,
        playState = EpisodePlayState.UNPLAYED,
        isNew = true,
        isCurrent = false,
        isPlaying = false
    )

    val sampleEpisode3 = PodcastEpisodeUi(
        id = "ep_3",
        showId = sampleShow2.id,
        showTitle = sampleShow2.title,
        title = "Vol.564 聊聊我们童年那些令人难忘的复古动画",
        description = "从《大闹天宫》到《九色鹿》，从《黑猫警长》到《魔方大厦》，那些定格在童年荧幕上的奇幻梦境。",
        imageUrl = sampleShow2.imageUrl,
        publishedText = "昨天",
        durationText = "1小时15分",
        remainingText = "",
        progress = 1.0f,
        playState = EpisodePlayState.PLAYED,
        isNew = false,
        isCurrent = false,
        isPlaying = false
    )

    val sampleEpisode4 = PodcastEpisodeUi(
        id = "ep_4",
        showId = sampleShow3.id,
        showTitle = sampleShow3.title,
        title = "GADIO 游戏杂谈：今年独立游戏的惊艳之作大盘点",
        description = "盘点过去一年里在美术、叙事与玩法机制上令人拍案叫绝的独立游戏神作。",
        imageUrl = sampleShow3.imageUrl,
        publishedText = "4天前",
        durationText = "58分钟",
        remainingText = "",
        progress = 0f,
        playState = EpisodePlayState.UNPLAYED,
        isNew = false,
        isCurrent = false,
        isPlaying = false
    )

    val sampleHomeUi = PodcastHomeUi(
        state = PodcastLoadState.READY,
        message = "",
        isRefreshing = false,
        refreshProgressText = "",
        continueListening = listOf(sampleEpisode1),
        shows = listOf(sampleShow1, sampleShow2, sampleShow3, sampleShowWithError),
        latestEpisodes = listOf(sampleEpisode2, sampleEpisode1, sampleEpisode3, sampleEpisode4)
    )

    val sampleShowDetailUi = PodcastShowDetailUi(
        show = sampleShow1,
        state = PodcastLoadState.READY,
        message = "",
        episodes = listOf(sampleEpisode2, sampleEpisode1, sampleEpisode3),
        totalEpisodes = 820,
        sort = EpisodeSort.NEWEST_FIRST,
        filter = EpisodeFilter.ALL,
        canLoadMore = true,
        isLoadingMore = false,
        resumeEpisode = sampleEpisode1
    )

    val sampleManageUi = PodcastManageUi(
        state = PodcastLoadState.READY,
        message = "",
        shows = listOf(sampleShow1, sampleShow2, sampleShow3, sampleShowWithError),
        isAdding = false,
        addError = "",
        lastAddedTitle = "",
        deleteError = ""
    )

    val sampleSearchResult1 = PodcastSearchResultUi(
        feedUrl = "https://etw.fm/rss",
        title = "声东击西",
        author = "徐涛",
        imageUrl = "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=400",
        genre = "文化与商业",
        episodeCountText = "共 280 期",
        isSubscribed = false,
        isSubscribing = false
    )

    val sampleSearchResult2 = PodcastSearchResultUi(
        feedUrl = "https://flipradio.net/feed.xml",
        title = "翻转电台 FlipRadio",
        author = "李厚辰",
        imageUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=400",
        genre = "哲学与思辨",
        episodeCountText = "共 340 期",
        isSubscribed = true,
        isSubscribing = false
    )

    val sampleSearchUi = PodcastSearchUi(
        state = PodcastSearchState.RESULTS,
        message = "",
        results = listOf(sampleSearchResult1, sampleSearchResult2),
        isSubscribingUrl = false,
        subscribeError = ""
    )

    val samplePlayerUi = PodcastPlayerUi(
        episode = sampleEpisode1,
        showTitle = sampleShow1.title,
        coverUrl = sampleEpisode1.imageUrl,
        isPlaying = true,
        isBuffering = false,
        durationMs = 2520000L, // 42分钟
        speed = 1.25f,
        sleepTimerMinutes = 30,
        sleepTimerRemainingSec = 1420L,
        upNext = listOf(sampleEpisode2, sampleEpisode3),
        isCasting = false
    )
}
