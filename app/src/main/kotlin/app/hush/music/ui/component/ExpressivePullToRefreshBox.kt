/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hush.music.LocalPlayerAwareWindowInsets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressivePullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val indicatorPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            // `IndicatorBox` is Material's own container: it owns the size, the drag position and
            // the enter/leave scale, so the surrounding behaviour is unchanged. Only the spinner
            // inside it is replaced - Material's `LoadingIndicator` rotates with
            // `Animatable.animateTo(infiniteRepeatable(tween))`, which a device with its animator
            // duration scale at 0 pins to a single angle, leaving a refresh that looked like a
            // dead dot. The ring below turns on frame time, which no setting can silence, and
            // still tracks the finger while the user is pulling.
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = isRefreshing,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(indicatorPadding),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                HushRefreshRing(
                    distanceFraction = state.distanceFraction,
                    isRefreshing = isRefreshing,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        content = content,
    )
}

/**
 * The ring inside the pull-to-refresh container: the pull distance while the user is dragging,
 * a turning arc while the refresh runs.
 *
 * Split out (and taking plain values rather than the state object) so both halves can be checked
 * without a gesture: a ring that has not been pulled shows nothing, one pulled half way shows half
 * a ring, and a refreshing one is a turning arc rather than a fixed one.
 */
@Composable
fun HushRefreshRing(
    distanceFraction: Float,
    isRefreshing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp,
) {
    val phase = rememberFramePhase(IndeterminateMotion.RING_PERIOD_MILLIS)
    val sweep = IndeterminateMotion.refreshRingSweepDegrees(distanceFraction, isRefreshing)
    val start = IndeterminateMotion.refreshRingStartDegrees(phase, isRefreshing)
    Canvas(modifier = modifier) {
        if (size.minDimension <= 0f) return@Canvas
        if (sweep == null) return@Canvas
        val stroke = strokeWidth.toPx()
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
        )
    }
}
