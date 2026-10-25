package io.github.lujinxin.nextep.workspace

import android.content.pm.ApplicationInfo

/** Apps suggested for pausing in a side pane: ones whose picture need not move while they wait there. */
object PauseRecommendations {
    // Shopping, local services, travel, reading and documents.
    private val KNOWN = setOf(
        "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.taobao.idlefish", "com.achievo.vipshop", "com.sankuai.meituan", "me.ele", "com.dianping.v1",
        "ctrip.android.view", "com.MobileTicket", "com.eg.android.AlipayGphone", "com.xingin.xhs",
        "com.zhihu.android", "com.sina.weibo", "com.douban.frodo", "com.ss.android.article.news",
        "com.netease.newsreader.activity", "com.tencent.weread", "cn.wps.moffice_eng",
    )

    // Games are killed or dropped from their match when stopped; video, music, maps and chat need a live pane.
    private val LIVE = setOf(
        ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_SOCIAL,
    )

    @Suppress("DEPRECATION")
    fun isGame(info: ApplicationInfo): Boolean =
        info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0

    fun recommended(packageName: String, category: Int, game: Boolean): Boolean =
        !game && category !in LIVE && (packageName in KNOWN ||
            category == ApplicationInfo.CATEGORY_NEWS || category == ApplicationInfo.CATEGORY_PRODUCTIVITY)
}
