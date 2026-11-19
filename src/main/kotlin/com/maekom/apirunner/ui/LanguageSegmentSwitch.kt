package com.maekom.apirunner.ui

import com.intellij.ui.JBColor
import com.maekom.apirunner.i18n.PluginLanguage
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

// [v0.2.1] 최상단 타이틀 헤더 바용 2단 알약/세그먼트 스위치(한국어 | English) 커스텀 UI 컴포넌트 구현
// 변경 의도: 셀렉트박스 대신 원클릭으로 즉시 한/영 전환이 가능한 직관적 세그먼트 토글 스위치 제공
class LanguageSegmentSwitch(
    initialLanguage: PluginLanguage = PluginLanguage.KO,
    private val onLanguageSelected: (PluginLanguage) -> Unit
) : JComponent() {

    private var selectedLanguage: PluginLanguage = initialLanguage

    private val trackBg = JBColor(Color(0xEB, 0xEE, 0xF2), Color(0x2B, 0x2D, 0x30))
    private val trackBorder = JBColor(Color(0xC6, 0xCB, 0xD2), Color(0x4E, 0x51, 0x57))
    private val activeBg = JBColor(Color(0x35, 0x74, 0xF0), Color(0x35, 0x74, 0xF0))
    private val activeFg = JBColor(Color.WHITE, Color.WHITE)
    private val inactiveFg = JBColor(Color(0x5A, 0x5E, 0x66), Color(0x9E, 0xA3, 0xAE))

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = Dimension(126, 24)
        preferredSize = size
        minimumSize = size
        maximumSize = size

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                val clickedLang = if (e.x < width / 2) PluginLanguage.KO else PluginLanguage.EN
                if (clickedLang != selectedLanguage) {
                    selectedLanguage = clickedLang
                    repaint()
                    onLanguageSelected(clickedLang)
                }
            }
        })
    }

    fun setSelectedLanguage(language: PluginLanguage) {
        if (selectedLanguage != language) {
            selectedLanguage = language
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = width
            val h = height
            val arc = h - 2
            val halfW = w / 2

            // Outer pill track
            g2.color = trackBg
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc)
            g2.color = trackBorder
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)

            // Active segment pill highlight
            val activeX = if (selectedLanguage == PluginLanguage.KO) 2 else halfW
            val activeW = if (selectedLanguage == PluginLanguage.KO) halfW - 2 else w - halfW - 2
            g2.color = activeBg
            g2.fillRoundRect(activeX, 2, activeW, h - 4, arc - 2, arc - 2)

            // Draw labels
            val baseFont = font ?: Font(Font.SANS_SERIF, Font.PLAIN, 11)
            val boldFont = baseFont.deriveFont(Font.BOLD, 11f)
            val plainFont = baseFont.deriveFont(Font.PLAIN, 11f)

            drawCenteredText(
                g2 = g2,
                text = "한국어",
                x = 0,
                width = halfW,
                height = h,
                font = if (selectedLanguage == PluginLanguage.KO) boldFont else plainFont,
                color = if (selectedLanguage == PluginLanguage.KO) activeFg else inactiveFg
            )
            drawCenteredText(
                g2 = g2,
                text = "English",
                x = halfW,
                width = w - halfW,
                height = h,
                font = if (selectedLanguage == PluginLanguage.EN) boldFont else plainFont,
                color = if (selectedLanguage == PluginLanguage.EN) activeFg else inactiveFg
            )
        } finally {
            g2.dispose()
        }
    }

    private fun drawCenteredText(
        g2: Graphics2D,
        text: String,
        x: Int,
        width: Int,
        height: Int,
        font: Font,
        color: Color
    ) {
        g2.font = font
        g2.color = color
        val fm = g2.fontMetrics
        val tx = x + (width - fm.stringWidth(text)) / 2
        val ty = (height - fm.height) / 2 + fm.ascent
        g2.drawString(text, tx, ty)
    }
}
