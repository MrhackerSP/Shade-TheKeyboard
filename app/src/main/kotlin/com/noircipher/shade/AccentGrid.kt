package com.noircipher.shade

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Pure geometry for the long-press accent panel: a rounded panel holding a grid of
 * cells above the pressed key.
 *
 *  - Grid shape depends on how many characters there are: up to 3 characters form a single row,
 *    anything more is split into two rows of `ceil(n / 2)` columns (4 -> 2x2, 6 -> 3x2, 8 -> 4x2,
 *    10 -> 5x2). Odd counts leave one empty cell in the top row.
 *  - The default character (the number hint on top-row keys, otherwise the first accent) sits in
 *    the bottom row directly above the key, so releasing without sliding commits it and the
 *    thumb barely has to move. The remaining characters are placed by closeness to that cell
 *    (right neighbour before left neighbour), so the most common accents are the easiest to reach.
 *
 * Nothing here touches Android classes, so it is unit-testable on the JVM. All coordinates are in
 * the keyboard view's coordinate space.
 */
object AccentGrid {

    const val MAX_ITEMS = 10

    /** Extra "cost" of being one row above the bottom row, in columns. */
    private const val ROW_COST = 1.5f

    fun columns(n: Int): Int = when {
        n <= 3 -> max(n, 1)
        else -> (n + 1) / 2
    }

    fun rows(n: Int): Int = if (n <= 3) 1 else 2

    /**
     * @param cols/rows grid size.
     * @param itemOfCell item index per cell (row-major, row 0 = top), -1 for an empty cell.
     * @param cellOfItem cell index (row * cols + col) per item.
     * @param anchorCell the cell holding the default item; always on the bottom row.
     */
    class Layout(
        val cols: Int,
        val rows: Int,
        val itemOfCell: IntArray,
        val cellOfItem: IntArray,
        val anchorCell: Int
    ) {
        fun row(cell: Int) = cell / cols
        fun col(cell: Int) = cell % cols
    }

    /**
     * @param n number of items (1..[MAX_ITEMS]).
     * @param anchorCol bottom-row column that sits above the pressed key (clamped into range).
     * @param defaultItem index of the item that goes into the anchor cell.
     */
    fun layout(n: Int, anchorCol: Int, defaultItem: Int): Layout {
        val cols = columns(n)
        val rows = rows(n)
        val ac = anchorCol.coerceIn(0, cols - 1)
        val def = defaultItem.coerceIn(0, max(n - 1, 0))

        // Every cell, nearest to the anchor first.
        val cells = (0 until rows * cols).sortedWith(
            compareBy<Int>(
                { cell ->
                    val dc = cell % cols - ac
                    val dy = rows - 1 - cell / cols
                    abs(dc) + ROW_COST * dy
                },
                { cell -> if (cell % cols - ac >= 0) 0 else 1 }, // right of the anchor first
                { cell -> -(cell / cols) }                        // then bottom row first
            )
        )

        val itemOfCell = IntArray(rows * cols) { -1 }
        val cellOfItem = IntArray(n)
        var next = 0
        for (item in sequenceOf(def) + (0 until n).asSequence().filter { it != def }) {
            val cell = cells[next++]
            itemOfCell[cell] = item
            cellOfItem[item] = cell
        }
        return Layout(cols, rows, itemOfCell, cellOfItem, (rows - 1) * cols + ac)
    }

    /** Placement of the panel and its cells, in the keyboard view's coordinates. */
    class Geometry(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cell: Float,
        val pad: Float,
        val cols: Int,
        val rows: Int
    ) {
        val width get() = right - left
        val height get() = bottom - top

        fun cellLeft(col: Int) = left + pad + col * cell
        fun cellTop(row: Int) = top + pad + row * cell
        fun cellCenterX(col: Int) = cellLeft(col) + cell / 2f
        fun cellCenterY(row: Int) = cellTop(row) + cell / 2f
    }

    /** Column of the cell that sits above [keyCenterX] for a panel whose left edge is [left]. */
    fun anchorColumn(keyCenterX: Float, left: Float, pad: Float, cell: Float, cols: Int): Int =
        floor((keyCenterX - left - pad) / cell).toInt().coerceIn(0, cols - 1)

    /**
     * Places a panel for [n] items above a key.
     *
     * @param keyCenterX horizontal centre of the pressed key.
     * @param keyTop top edge of the pressed key.
     * @param preferredCell wanted cell size; shrunk if the grid would not fit the width.
     * @param viewWidth width of the area the panel must stay inside (with [margin] on both sides).
     * @param gap space between the panel's bottom edge and the key.
     * @param minTop the highest the panel may reach; if the panel does not fit above the key it is
     *               pushed down to this edge (and then overlaps the key row).
     */
    fun place(
        n: Int,
        keyCenterX: Float,
        keyTop: Float,
        preferredCell: Float,
        pad: Float,
        viewWidth: Float,
        margin: Float,
        gap: Float,
        minTop: Float
    ): Geometry {
        val cols = columns(n)
        val rows = rows(n)
        val cell = minOf(preferredCell, (viewWidth - 2 * margin - 2 * pad) / cols)
        val w = cols * cell + 2 * pad
        val h = rows * cell + 2 * pad
        val left = (keyCenterX - w / 2f).coerceIn(margin, max(margin, viewWidth - margin - w))
        var bottom = keyTop - gap
        var top = bottom - h
        if (top < minTop) {
            top = minTop
            bottom = top + h
        }
        return Geometry(left, top, left + w, bottom, cell, pad, cols, rows)
    }

    /**
     * Cell under (x, y). The pointer may be anywhere: positions outside the panel clamp to the
     * nearest row/column, so dragging down onto the keyboard selects the bottom row and dragging
     * far sideways selects the edge column. Returns the item index, or -1 for an empty cell.
     */
    fun hit(layout: Layout, geo: Geometry, x: Float, y: Float): Int {
        val col = floor((x - geo.left - geo.pad) / geo.cell).toInt().coerceIn(0, layout.cols - 1)
        val row = floor((y - geo.top - geo.pad) / geo.cell).toInt().coerceIn(0, layout.rows - 1)
        return layout.itemOfCell[row * layout.cols + col]
    }
}
