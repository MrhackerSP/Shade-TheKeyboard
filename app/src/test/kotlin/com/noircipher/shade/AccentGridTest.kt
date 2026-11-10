package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentGridTest {

    @Test fun gridShapeFollowsCharacterCount() {
        fun shape(n: Int) = AccentGrid.columns(n) to AccentGrid.rows(n)
        assertEquals(1 to 1, shape(1))
        assertEquals(2 to 1, shape(2))
        assertEquals(3 to 1, shape(3))
        assertEquals(2 to 2, shape(4))
        assertEquals(3 to 2, shape(5))
        assertEquals(3 to 2, shape(6))
        assertEquals(4 to 2, shape(7))
        assertEquals(4 to 2, shape(8))
        assertEquals(5 to 2, shape(9))
        assertEquals(5 to 2, shape(10))
    }

    @Test fun everyItemIsPlacedExactlyOnce() {
        for (n in 1..AccentGrid.MAX_ITEMS) for (anchor in 0..5) for (def in 0 until n) {
            val l = AccentGrid.layout(n, anchor, def)
            val placed = l.itemOfCell.filter { it >= 0 }.sorted()
            assertEquals("n=$n anchor=$anchor def=$def", (0 until n).toList(), placed)
            for (item in 0 until n) assertEquals(item, l.itemOfCell[l.cellOfItem[item]])
            assertEquals(l.cols * l.rows - n, l.itemOfCell.count { it < 0 })
        }
    }

    @Test fun defaultItemSitsOnBottomRowAboveTheKey() {
        for (n in 1..AccentGrid.MAX_ITEMS) for (anchor in 0 until AccentGrid.columns(n)) {
            val l = AccentGrid.layout(n, anchor, 0)
            assertEquals(l.rows - 1, l.row(l.anchorCell))
            assertEquals(anchor, l.col(l.anchorCell))
            assertEquals(0, l.itemOfCell[l.anchorCell])
        }
    }

    @Test fun anchorColumnIsClampedIntoTheGrid() {
        val l = AccentGrid.layout(6, 9, 0)
        assertEquals(2, l.col(l.anchorCell))
        assertEquals(0, AccentGrid.layout(6, -3, 0).col(AccentGrid.layout(6, -3, 0).anchorCell))
    }

    @Test fun nearbyItemsComeFirstRightNeighbourBeforeLeft() {
        // 6 items, anchor in the middle column: items 1 and 2 are the bottom-row neighbours.
        val l = AccentGrid.layout(6, 1, 0)
        assertEquals(1, l.itemOfCell[1 * 3 + 2]) // bottom right
        assertEquals(2, l.itemOfCell[1 * 3 + 0]) // bottom left
    }

    @Test fun defaultItemCanBeAnyIndex() {
        val l = AccentGrid.layout(8, 2, 3)
        assertEquals(3, l.itemOfCell[l.anchorCell])
    }

    @Test fun panelStaysInsideTheViewHorizontally() {
        val g1 = AccentGrid.place(8, 5f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        assertEquals(8f, g1.left, 0.001f)
        val g2 = AccentGrid.place(8, 995f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        assertEquals(992f, g2.right, 0.001f)
    }

    @Test fun panelIsCentredOnTheKeyWhenThereIsRoom() {
        val g = AccentGrid.place(6, 500f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        assertEquals(500f, (g.left + g.right) / 2f, 0.001f)
        assertEquals(294f, g.bottom, 0.001f)
        assertEquals(2 * 48f + 12f, g.height, 0.001f)
    }

    @Test fun cellShrinksOnNarrowViews() {
        val g = AccentGrid.place(10, 100f, 300f, 48f, 6f, 200f, 8f, 6f, 0f)
        assertTrue(g.cell < 48f)
        assertTrue(g.width <= 200f - 16f + 0.001f)
    }

    @Test fun panelIsPushedDownWhenItDoesNotFitAbove() {
        val g = AccentGrid.place(8, 500f, 40f, 48f, 6f, 1000f, 8f, 6f, 2f)
        assertEquals(2f, g.top, 0.001f)
    }

    @Test fun anchorColumnFollowsTheKey() {
        val g = AccentGrid.place(8, 500f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        val col = AccentGrid.anchorColumn(500f, g.left, g.pad, g.cell, g.cols)
        assertEquals(500f, g.cellCenterX(col), g.cell / 2f + 0.001f)
        val edge = AccentGrid.place(8, 5f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        assertEquals(0, AccentGrid.anchorColumn(5f, edge.left, edge.pad, edge.cell, edge.cols))
    }

    @Test fun hitClampsOutsideTheGridAndSkipsEmptyCells() {
        val l = AccentGrid.layout(5, 1, 0)           // 3x2 with one empty cell
        val g = AccentGrid.place(5, 500f, 300f, 48f, 6f, 1000f, 8f, 6f, 0f)
        // far below the panel -> bottom row, column under the finger
        val below = AccentGrid.hit(l, g, g.cellCenterX(1), g.bottom + 200f)
        assertEquals(0, below)
        // far left -> column 0 of the bottom row
        val left = AccentGrid.hit(l, g, -500f, g.bottom + 10f)
        assertEquals(l.itemOfCell[1 * 3 + 0], left)
        // the empty cell reports -1
        val emptyCell = l.itemOfCell.indexOf(-1)
        val x = g.cellCenterX(l.col(emptyCell))
        val y = g.cellCenterY(l.row(emptyCell))
        assertEquals(-1, AccentGrid.hit(l, g, x, y))
    }
}
