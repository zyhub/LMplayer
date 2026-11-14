package com.lm.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * 主标签 Pager 每一页的根部：拦下页面内部发出的「滚到可见」请求，不让它带着 Pager 横向滚动。
 *
 * 输入框获得焦点（例如资料库二级页的搜索框）时，Compose 会沿父级逐层请求把它滚进可见区域；
 * Pager 也会响应，结果被横向拖到下一页（设置页）并停在两页之间，又因为二级页打开时禁止手势翻页而卡住。
 * 这里把请求换成「本页左上角一像素」交给 Pager —— 当前页本来就可见，Pager 不会动；
 * 页面内部的纵向列表仍在这一层之下，照常把输入框滚进可见区域。
 */
@OptIn(ExperimentalFoundationApi::class)
private object PageBringIntoViewBlocker : BringIntoViewResponder {
    @ExperimentalFoundationApi
    override fun calculateRectForParent(localRect: Rect): Rect = Rect(0f, 0f, 1f, 1f)

    @ExperimentalFoundationApi
    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.blockPagerBringIntoView(): Modifier = this.bringIntoViewResponder(PageBringIntoViewBlocker)
