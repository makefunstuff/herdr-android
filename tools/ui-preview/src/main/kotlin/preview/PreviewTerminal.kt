package preview

import com.makefunstuff.herdr.ui.terminal.CellPoint

/**
 * The terminal's selection in a scene: "Select text" starts it here (anchor, cursor), as a drag
 * across the grid would on a phone. Null starts it at the cell tapped.
 */
object PreviewTerminal {
    @Volatile var selection: Pair<CellPoint, CellPoint>? = null
}
