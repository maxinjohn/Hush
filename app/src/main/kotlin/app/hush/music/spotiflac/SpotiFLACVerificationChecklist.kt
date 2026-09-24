/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLAutoVerifier.Step

/**
 * How a run's progress reads on the Audio Sources screen.
 *
 * Kept out of the composable as a pure object for the usual reason: the wording is a *decision*
 * ("has this source been solved, is it next, or has it failed?") and a decision buried in Compose
 * can only be checked by looking at a phone. It is also the one place the two vocabularies have to
 * agree - the line under the buttons counts a run, the rows name its members, and a reader who sees
 * "1 needs a check" above a row saying "Verified" will believe neither.
 */
object SpotiFLACVerificationChecklist {

    /** Which of the card's colours a state should borrow. */
    enum class Tone {
        /** Finished and usable. */
        DONE,

        /** Being worked on right now. */
        SOLVING,

        /** Queued behind the one being worked on. */
        WAITING,

        /** Tried, and it needs the user. */
        ATTENTION,
    }

    /**
     * The words for one source's place in the run.
     *
     * Deliberately about *this run*, not about the source's session: "Verified" here means the check
     * just landed, which is the news the user is waiting for. What the session is worth afterwards -
     * how long it has left, when it renews - is the row's own session text, and the row falls back
     * to it as soon as the run is over.
     */
    fun text(state: Step.State): String =
        when (state) {
            Step.State.SOLVING -> "Solving…"
            Step.State.WAITING -> "Waiting"
            Step.State.VERIFIED -> "Verified"
            Step.State.NEEDS_CHECK -> "Needs a check"
        }

    fun tone(state: Step.State): Tone =
        when (state) {
            Step.State.SOLVING -> Tone.SOLVING
            Step.State.WAITING -> Tone.WAITING
            Step.State.VERIFIED -> Tone.DONE
            Step.State.NEEDS_CHECK -> Tone.ATTENTION
        }

    /**
     * Whether the checklist is worth drawing.
     *
     * Only while a run is happening: [SpotiFLAutoVerifier.steps] keeps the last run's marks so they
     * are not lost the moment the queue drains, and showing those afterwards would put "Solving…"
     * over a run that finished ten minutes ago. A run is in flight exactly while a source is being
     * solved, which is what the verifier's own active slot means.
     */
    fun isRunning(active: String?): Boolean = active != null

    /** One source's place in the run, or null when this run never touched it. */
    fun stepFor(steps: List<Step>, sourceId: String): Step? = steps.firstOrNull { it.sourceId == sourceId }

    /**
     * The count under the buttons, or null when there is nothing to count.
     *
     * "2 of 4 checked" is the line that makes a multi-source run legible: the verifier works through
     * sources one at a time and each challenge can take a while, so without a count the screen looks
     * identical before the first one lands and after three have.
     *
     * "Checked" counts the ones the run is *done with* - solved or given up on - because that is what
     * it measures: progress through the list. Counting only the successes would make the line stop
     * moving whenever a source fails, which is exactly the run a user is watching most closely.
     */
    fun progressLine(steps: List<Step>): String? {
        if (steps.isEmpty()) return null
        val checked = steps.count { it.state == Step.State.VERIFIED || it.state == Step.State.NEEDS_CHECK }
        val needsCheck = steps.count { it.state == Step.State.NEEDS_CHECK }
        val parts =
            buildList {
                add("$checked of ${steps.size} checked")
                if (needsCheck > 0) {
                    add(if (needsCheck == 1) "1 needs a check" else "$needsCheck need a check")
                }
            }
        return parts.joinToString(" · ")
    }
}
