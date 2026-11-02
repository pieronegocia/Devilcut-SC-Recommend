package com.devilcut.pinsaver.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The two icons we need that are not in material-icons-core. */
object AppIcons {

    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "Download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // Arrow pointing down.
                moveTo(11f, 3f)
                horizontalLineTo(13f)
                verticalLineTo(12.2f)
                lineTo(16.6f, 8.6f)
                lineTo(18f, 10f)
                lineTo(12f, 16f)
                lineTo(6f, 10f)
                lineTo(7.4f, 8.6f)
                lineTo(11f, 12.2f)
                close()
                // Tray.
                moveTo(5f, 18f)
                horizontalLineTo(19f)
                verticalLineTo(20f)
                horizontalLineTo(5f)
                close()
            }
        }.build()
    }

    val Paste: ImageVector by lazy {
        ImageVector.Builder(
            name = "Paste",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Board outline.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                curveTo(18.55f, 4f, 19f, 4.45f, 19f, 5f)
                verticalLineTo(21f)
                curveTo(19f, 21.55f, 18.55f, 22f, 18f, 22f)
                horizontalLineTo(6f)
                curveTo(5.45f, 22f, 5f, 21.55f, 5f, 21f)
                verticalLineTo(5f)
                curveTo(5f, 4.45f, 5.45f, 4f, 6f, 4f)
                close()
                moveTo(7f, 6f)
                verticalLineTo(20f)
                horizontalLineTo(17f)
                verticalLineTo(6f)
                close()
            }
            // Clip at the top.
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 2f)
                horizontalLineTo(15f)
                verticalLineTo(7f)
                horizontalLineTo(9f)
                close()
            }
            // Text lines.
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 10f)
                horizontalLineTo(15f)
                verticalLineTo(11.5f)
                horizontalLineTo(9f)
                close()
                moveTo(9f, 13.5f)
                horizontalLineTo(15f)
                verticalLineTo(15f)
                horizontalLineTo(9f)
                close()
            }
        }.build()
    }
}
