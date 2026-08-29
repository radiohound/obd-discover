package com.redundo.obddiscover

/**
 * Work the car's own controls on a stationary car, and see which readings respond.
 *
 * NAMED FOR WHAT IT DOES. This was "Provoke", which is the standard diagnostic term but
 * reads as doing something aggressive TO the vehicle -- a poor label for a button somebody
 * might press in a car that is not theirs. Nothing here is done to the car: the operator
 * turns on the air conditioning and the headlights, holds some revs, and works the steering
 * and brakes, exactly as a driver would. The app only watches and writes down when.
 *
 * WHY IT IS NEEDED AT ALL. `correlate` ranks a candidate against rpm, speed, load, coolant,
 * MAF, baro and ambient. A field with no relationship to any of those is invisible to it, no
 * matter how long the drive: air-conditioning state, electrical load, power steering
 * pressure, brake vacuum. Those only move when somebody moves them. Provocation supplies the
 * missing axis by making the operator the independent variable and writing what they did
 * into the log beside the readings.
 *
 * THE SEQUENCE IS GENERIC, and deliberately so. It was written for a BMW F10 but contains
 * nothing BMW-specific: every step is a control that exists on any car with a heater and a
 * steering wheel. What differs per vehicle is only WHICH DIDs get logged, and that comes
 * from discovery.
 *
 * EVERY LOAD IS PAIRED WITH ITS RELEASE, which is the part that makes it evidence rather
 * than coincidence. A column that rises when the AC goes on and RETURNS when it goes off is
 * responding; one that merely drifts upward across the session is not, and without the
 * off-step the two look identical.
 */
object ControlsTest {

    /** One line the operator can read at a glance before starting. */
    const val SUMMARY =
        "You switch things on and off while it logs. Nothing is sent to the car beyond the " +
        "same read requests it already uses — fields that ignore speed and temperature " +
        "(air conditioning, electrical load, steering, brakes) can only be identified this way."

    val STEPS = listOf(
        "baseline"          to "engine idling, everything off — the reference for every step",
        "AC on"             to "compressor load, refrigerant pressure, blower current",
        "AC off"            to "back to baseline: a real signal returns, a drifting one does not",
        "lights+demist on"  to "electrical load, alternator field duty, battery current",
        "lights+demist off" to "the control for the electrical step",
        "hold 2500 rpm"     to "rpm-dependent WITHOUT road load — separates two families at once",
        "back to idle"      to "the control for the rpm step",
        "steering L-R"      to "power steering load, stationary",
        "brake pumped"      to "vacuum in the brake booster",
        "done"              to "stop the log",
    )

    val labels: List<String> get() = STEPS.map { it.first }

    /**
     * Steps that RELEASE the load applied by the step before them.
     *
     * These are the controls, and the first run on a car skipped every one of them. Without
     * the release there is no way to tell a response from a drift: a column that rises when
     * the air conditioning goes on and RETURNS when it goes off is responding, while one
     * that merely climbs across the session looks identical up to that point.
     *
     * Marked so the screen can say so, rather than presenting them as more of the same.
     */
    fun isRelease(label: String) = label in setOf(
        "AC off", "lights+demist off", "back to idle",
    )

    fun next(current: String): String {
        val i = labels.indexOf(current)
        return labels.getOrElse(i + 1) { labels.last() }
    }

    fun hint(label: String): String = STEPS.firstOrNull { it.first == label }?.second ?: ""
}
