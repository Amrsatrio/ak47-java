package com.tb24.discordbot

import me.fungames.jfortniteparse.util.toPngArray
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.*
import javax.swing.plaf.FontUIResource

fun main() {
	UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
	val f = Font.createFont(Font.TRUETYPE_FONT, File("C:\\Users\\satri\\AppData\\Local\\Microsoft\\Windows\\Fonts\\zh-cn.ttf")).deriveFont(14f)
	val keys = UIManager.getDefaults().keys()
	while (keys.hasMoreElements()) {
		val key = keys.nextElement()
		val value = UIManager.get(key)
		if (value is FontUIResource)
			UIManager.put(key, f)
	}

	val mainPanel = JPanel()
	mainPanel.border = BorderFactory.createEmptyBorder(40, 40, 40, 40)
	mainPanel.layout = BorderLayout()
	mainPanel.add(JLabel("Quests".toUpperCase(), SwingConstants.CENTER).apply {
		font = font.deriveFont(48f)
	}, BorderLayout.NORTH)
	val container = JPanel().apply {
		layout = GridBagLayout()
		add(JLabel().apply {
			icon = ImageIcon(ImageIcon("C:\\Users\\satri\\Desktop\\ui_timer_64x.png").image.getScaledInstance(48, 48, Image.SCALE_DEFAULT))
		})
		add(JPanel().apply {
			layout = GridBagLayout()
			add(JTextArea("Stage 1 of 4 - Lorem ipsum dolor sit amet, consetectur adipiscing elit").apply {
				border = BorderFactory.createEmptyBorder()
				lineWrap = true
				wrapStyleWord = true
				isEditable = false
			}, GridBagConstraints().apply {
				fill = GridBagConstraints.BOTH
				gridwidth = 2
				weightx = 1.0; weighty = 1.0
			})
			add(JLabel("Time Remaining"), GridBagConstraints().apply {
				anchor = GridBagConstraints.WEST
				gridx = 0; gridy = 1
			})
			add(JLabel("1 / 4"), GridBagConstraints().apply {
				anchor = GridBagConstraints.EAST
				gridx = 1; gridy = 1
			})
		}, GridBagConstraints().apply {
			fill = GridBagConstraints.HORIZONTAL
			weightx = 1.0
		})
		add(JLabel().apply {
			icon = ImageIcon("C:\\Users\\satri\\Desktop\\ui_timer_64x.png")
		})
	}
	mainPanel.add(container, BorderLayout.CENTER)
	val frame = JFrame("h")
//	frame.add(mainPanel)
//	frame.validate()
//	frame.isVisible = true
	mainPanel.validate()
	File("testlayout.png").writeBytes(mainPanel.createImage().toPngArray())
}

fun Component.createImage(): BufferedImage {
	if (!isDisplayable) {
		val d = size
		if (d.width == 0 || d.height == 0) {
			size = preferredSize
		}
		layoutComponent()
	}
	val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
	val g = image.createGraphics()
	paint(g)
	g.dispose()
	return image
}

fun Component.layoutComponent() {
	synchronized(treeLock) {
		if (this is JComponent) {
			isOpaque = false
		}
		doLayout()
		if (this is Container) {
			components.forEach { it.layoutComponent() }
		}
	}
}