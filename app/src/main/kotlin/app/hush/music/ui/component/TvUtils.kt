package app.hush.music.ui.component

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun rememberTvDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        val isTelevisionUiMode =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        isTelevisionUiMode ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }
}

fun Modifier.tvDpadScroll(
    listState: LazyListState,
    scope: CoroutineScope,
): Modifier {
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key(KEYCODE_DPAD_DOWN.toLong()) -> {
                if (listState.canScrollForward) {
                    scope.launch {
                        val targetIndex = (listState.firstVisibleItemIndex + 2)
                            .coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
                        listState.animateScrollToItem(targetIndex)
                    }
                    true
                } else {
                    false
                }
            }
            Key(KEYCODE_DPAD_UP.toLong()) -> {
                if (listState.canScrollBackward) {
                    scope.launch {
                        val targetIndex = (listState.firstVisibleItemIndex - 2)
                            .coerceAtLeast(0)
                        listState.animateScrollToItem(targetIndex)
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
}

@Composable
fun Modifier.tvFocusBorder(
    shape: Shape = androidx.compose.material3.MaterialTheme.shapes.medium,
    width: androidx.compose.ui.unit.Dp = 3.dp,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (isFocused) {
                Modifier.border(
                    width = width,
                    color = color,
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
}
