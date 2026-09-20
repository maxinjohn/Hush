/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.debug

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Keeps the process's resumed activity to hand. **Debug builds only.**
 *
 * A Compose screen's semantics tree can only be read in-process, off the `AndroidComposeView` that
 * hosts it, and the only handle on that view is the activity it belongs to. A broadcast receiver is
 * given a `Context` and nothing else, so [SemanticsProbeReceiver] needs somewhere to ask.
 *
 * A provider is the right place for that ask, and not merely a convenient one: the platform
 * installs a process's providers *before* it can resume any activity in that process, so the
 * lifecycle callbacks registered here are already watching by the time there is anything to watch.
 * Registering them from the receiver instead would only ever see activities that resumed after the
 * broadcast, which is the opposite of what the probe is asked about.
 *
 * Not exported: nothing outside the app drives it, and it holds no data - only a weak reference to
 * a live activity, dropped on destroy so it can never keep one alive.
 */
class SemanticsProbeActivityRegistry : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(Tracker)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private object Tracker : Application.ActivityLifecycleCallbacks {

        override fun onActivityResumed(activity: Activity) = hold(activity)

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityDestroyed(activity: Activity) = release(activity)

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit
    }

    companion object {

        @Volatile
        private var resumed: WeakReference<Activity>? = null

        /** The activity a probe should read, or null when nothing is resumed. */
        fun resumedActivity(): Activity? = resumed?.get()

        private fun hold(activity: Activity) {
            resumed = WeakReference(activity)
        }

        private fun release(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }
    }
}
