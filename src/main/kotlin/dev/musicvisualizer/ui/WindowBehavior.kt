package dev.musicvisualizer.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JComponent
import javax.swing.SwingUtilities

object WindowBehavior {
    fun install(window: Window, onImageDropped: (Path) -> Unit): AutoCloseable {
        val pointerInputStyle = enablePointerInput(window)
        val mouse = BorderlessWindowMouseHandler(window)
        window.addMouseListener(mouse)
        window.addMouseMotionListener(mouse)
        val oldDropTarget = window.dropTarget
        val dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                runCatching {
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                    val image = files.firstOrNull { it.extension.lowercase() in IMAGE_EXTENSIONS }
                    if (image != null) {
                        onImageDropped(image.toPath())
                        event.dropComplete(true)
                    } else event.dropComplete(false)
                }.onFailure { event.rejectDrop() }
            }
        }, true)
        return AutoCloseable {
            pointerInputStyle.close()
            window.removeMouseListener(mouse)
            window.removeMouseMotionListener(mouse)
            dropTarget.isActive = false
            window.dropTarget = oldDropTarget
        }
    }

    private fun enablePointerInput(window: Window): AutoCloseable {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return AutoCloseable {}
        val contentPane = (window as? JFrame)?.contentPane as? JComponent
        val originalBackground = contentPane?.background
        val originalOpaque = contentPane?.isOpaque
        contentPane?.apply {
            background = Color(0, 0, 0, POINTER_INPUT_BACKGROUND_ALPHA)
            isOpaque = true
        }
        return AutoCloseable {
            if (contentPane != null && originalBackground != null && originalOpaque != null) {
                contentPane.background = originalBackground
                contentPane.isOpaque = originalOpaque
            }
        }
    }

    private class BorderlessWindowMouseHandler(private val window: Window) : MouseAdapter() {
        private var screenStart: Point? = null
        private var boundsStart: java.awt.Rectangle? = null
        private var resizeEdges = 0
        private var moving = false

        override fun mouseMoved(event: MouseEvent) {
            if (screenStart != null) return
            resizeEdges = edges(event)
            window.cursor = Cursor.getPredefinedCursor(cursorFor(resizeEdges))
        }

        override fun mousePressed(event: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(event)) return
            resizeEdges = edges(event)
            moving = event.isAltDown && resizeEdges == 0
            if (moving || resizeEdges != 0) {
                screenStart = event.locationOnScreen
                boundsStart = window.bounds
            }
        }

        override fun mouseDragged(event: MouseEvent) {
            val origin = screenStart ?: return
            val initial = boundsStart ?: return
            val dx = event.xOnScreen - origin.x
            val dy = event.yOnScreen - origin.y
            if (moving) {
                window.setLocation(initial.x + dx, initial.y + dy)
                return
            }
            var x = initial.x
            var y = initial.y
            var width = initial.width
            var height = initial.height
            if (resizeEdges and LEFT != 0) { x += dx; width -= dx }
            if (resizeEdges and RIGHT != 0) width += dx
            if (resizeEdges and TOP != 0) { y += dy; height -= dy }
            if (resizeEdges and BOTTOM != 0) height += dy
            if (width >= 480 && height >= 320) window.setBounds(x, y, width, height)
        }

        override fun mouseReleased(event: MouseEvent) {
            screenStart = null
            boundsStart = null
            moving = false
        }

        private fun edges(event: MouseEvent): Int {
            var value = 0
            if (event.x <= EDGE) value = value or LEFT
            if (event.x >= window.width - EDGE) value = value or RIGHT
            if (event.y <= EDGE) value = value or TOP
            if (event.y >= window.height - EDGE) value = value or BOTTOM
            return value
        }

        private fun cursorFor(edges: Int): Int = when (edges) {
            LEFT, RIGHT -> Cursor.E_RESIZE_CURSOR
            TOP, BOTTOM -> Cursor.N_RESIZE_CURSOR
            LEFT or TOP, RIGHT or BOTTOM -> Cursor.NW_RESIZE_CURSOR
            RIGHT or TOP, LEFT or BOTTOM -> Cursor.NE_RESIZE_CURSOR
            else -> Cursor.DEFAULT_CURSOR
        }
    }

    private const val EDGE = 7
    private const val LEFT = 1
    private const val RIGHT = 2
    private const val TOP = 4
    private const val BOTTOM = 8
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
}

internal const val POINTER_INPUT_BACKGROUND_ALPHA = 1
