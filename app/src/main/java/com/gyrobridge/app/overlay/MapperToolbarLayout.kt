package com.gyrobridge.app.overlay

data class MapperButtonBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class MapperToolbarGeometry(
    val panelLeft: Float,
    val panelTop: Float,
    val panelRight: Float,
    val panelBottom: Float,
    val buttons: List<MapperButtonBounds>,
)

object MapperToolbarLayout {
    fun calculate(screenWidthPx: Float, density: Float): MapperToolbarGeometry {
        val safeDensity = density.coerceAtLeast(.5f)
        val margin = 8f * safeDensity
        val panelRight = minOf(
            screenWidthPx / 2f - margin,
            margin + 300f * safeDensity,
        ).coerceAtLeast(margin + 1f)
        val panelLeft = margin
        val panelTop = margin
        val inset = 6f * safeDensity
        val gap = 5f * safeDensity
        val rowHeight = 34f * safeDensity
        val contentLeft = panelLeft + inset
        val contentRight = panelRight - inset
        val cellWidth = ((contentRight - contentLeft - gap * 2f) / 3f).coerceAtLeast(1f)
        val buttons = List(6) { index ->
            val row = index / 3
            val column = index % 3
            val left = contentLeft + column * (cellWidth + gap)
            val top = panelTop + inset + row * (rowHeight + gap)
            MapperButtonBounds(left, top, left + cellWidth, top + rowHeight)
        }
        val panelBottom = buttons.last().bottom + 24f * safeDensity
        return MapperToolbarGeometry(panelLeft, panelTop, panelRight, panelBottom, buttons)
    }
}
