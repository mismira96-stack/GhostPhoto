package com.example.cloudwaste.fixture

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var selectionCount: TextView
    private lateinit var deleteButton: Button
    private val tiles = FixtureData.tiles.map { it.copy() }.toMutableList()
    private val tileViews = mutableMapOf<String, android.view.View>()

    var deleteClicked: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectionCount = findViewById(R.id.selection_count)
        deleteButton = findViewById(R.id.btn_delete)
        deleteButton.setOnClickListener {
            deleteClicked = true
            deleteButton.contentDescription = "delete_button_clicked"
        }

        val grid = findViewById<GridLayout>(R.id.photos_grid)
        val inflater = LayoutInflater.from(this)
        tiles.forEachIndexed { index, tile ->
            val item = inflater.inflate(R.layout.item_tile, grid, false)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = (110 * resources.displayMetrics.density).toInt()
                columnSpec = GridLayout.spec(index % 3, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(4, 4, 4, 4)
            }
            item.layoutParams = lp
            bindTile(item, tile)
            item.setOnClickListener {
                tile.selected = !tile.selected
                bindTile(item, tile)
                refreshChrome()
            }
            tileViews[tile.id] = item
            grid.addView(item)
        }

        refreshChrome()
    }

    private fun bindTile(item: android.view.View, tile: MediaTile) {
        val swatch = item.findViewById<android.view.View>(R.id.tile_swatch)
        val check = item.findViewById<TextView>(R.id.tile_check)
        val label = item.findViewById<TextView>(R.id.tile_label)
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f
            setColor(tile.color)
        }
        label.text = tile.label
        check.text = if (tile.selected) "✓" else ""
        check.setBackgroundColor(if (tile.selected) 0xFF1A73E8.toInt() else 0x40000000)
        item.contentDescription =
            if (tile.selected) "${tile.id}:selected" else "${tile.id}:unselected"
    }

    private fun refreshChrome() {
        val count = tiles.count { it.selected }
        selectionCount.text = getString(R.string.selection_count_fmt, count)
        selectionCount.contentDescription = "selection_count:$count"
        deleteButton.isEnabled = count > 0
    }
}
