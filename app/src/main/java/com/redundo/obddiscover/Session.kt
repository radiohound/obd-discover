package com.redundo.obddiscover

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-scoped holder for the live capture session.
 *
 * WHY THIS EXISTS -- a bug that nearly cost a cold start, 2026-08-23.
 *
 * ElmBle and ScanRunner used to be constructed in MainActivity.onCreate(). Android rebuilds
 * an Activity on ANY configuration change -- rotation is the common one, and a phone lying
 * on a car seat rotates -- so onCreate ran again mid-drive and built a SECOND ScanRunner
 * with running=false and rows=0. The first one's worker thread carried on polling and
 * flushing rows to disk, because it is a plain thread holding its own writer and cares
 * nothing for the Activity lifecycle.
 *
 * The result was the worst kind of wrong: the screen said logging had stopped while logging
 * was in fact still running. The driver could reasonably have ended the drive early, and the
 * cold-start ramp cannot be repeated for six hours.
 *
 * So the session lives here, tied to the process rather than to a window, and is created
 * exactly once. `ident` lives here too -- it was `remember { mutableStateOf(...) }` inside
 * the composable, so it reset on the same event and re-disabled both task buttons.
 *
 * The Activity is additionally declared with android:configChanges for the common cases, so
 * routine rotation does not rebuild the window at all. Belt and braces: that attribute can
 * be defeated by a configuration change it does not list, this holder cannot.
 */
object Session {

    private var _ble: ElmBle? = null
    private var _runner: ScanRunner? = null
    private var _discover: DiscoverRunner? = null
    private var _connector: Connector? = null
    private var _capture: CaptureRunner? = null

    /** Adapter identity from ATZ, or null if Init has not run/succeeded yet. */
    var ident by mutableStateOf<String?>(null)

    /** Advisory read of the last drive log. Never a finding -- see Triage's docstring. */
    var triage by mutableStateOf<Triage.Result?>(null)

    /**
     * Sweep Mode-21 local identifiers on ISO 9141-2 as well as KWP2000. Off by default.
     * See Mode21.appliesToIso for why this one is the owner's call and not the code's.
     */
    var mode21OnIso9141 by mutableStateOf(false)

    /**
     * Look the model up online from an abbreviated VIN. OFF by default.
     *
     * The only network use in this app, and the only reason it holds the INTERNET
     * permission. See VinLookup for exactly which ten characters are sent and why a bundled
     * table was not the answer.
     */
    var onlineVinLookup by mutableStateOf(false)

    /**
     * The (header, requests) actually being logged right now.
     *
     * Set by CaptureRunner whichever way the plan was obtained -- freshly discovered OR
     * restored from a cached map. KEEP DRIVING used to read DiscoverRunner.logPlan, which
     * only a fresh discovery sets, so continuing a drive on a cached map would either do
     * nothing or reuse whatever car was mapped last.
     */
    var activePlan by mutableStateOf<Pair<String, List<String>>?>(null)

    /** The log a further drive should APPEND to, or null to start fresh. */
    var continueFile by mutableStateOf<java.io.File?>(null)

    /** Application context on purpose -- an Activity reference here would leak the window. */
    fun ensure(ctx: Context) {
        if (_ble == null) {
            val app = ctx.applicationContext
            VehicleId.load(app)
            _ble = ElmBle(app)
            _runner = ScanRunner(app, _ble!!)
            _discover = DiscoverRunner(app, _ble!!)
            _connector = Connector(_ble!!)
            _capture = CaptureRunner(app, _ble!!, _discover!!, _runner!!)
            Thread { _capture?.countStoredMaps() }.start()
        }
    }

    val ble: ElmBle get() = _ble!!
    val runner: ScanRunner get() = _runner!!
    val discover: DiscoverRunner get() = _discover!!
    val connector: Connector get() = _connector!!
    val capture: CaptureRunner get() = _capture!!
}
