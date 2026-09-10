package com.zdredge.consistency

import android.app.Application
import android.content.Context
import com.zdredge.consistency.notify.CheckInAlarmScheduler
import com.zdredge.consistency.work.RolloverScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the object graph for the whole process, not just for an Activity.
 *
 * **This exists because of the rollover.** Until M5, `AppContainer` was built inside `MainActivity`,
 * which was fine while every write started with someone tapping something. The 04:00 job is the
 * first thing that runs with no Activity alive — architecture §6 calls it "the only thing that
 * writes without the user" — and it cannot reach a container that only exists once a screen does.
 *
 * The container is `lazy`, so a process started only to run the worker builds the database and
 * nothing else. Architecture §1.1: the app is not running most of the time, the system wakes it to
 * do one small thing, and it goes away again.
 */
class ConsistencyApp : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Every process start, not just a cold launch from the launcher. Enqueuing is KEEP, so this
        // is a no-op once the job is scheduled -- see RolloverScheduler.
        RolloverScheduler.schedule(this)

        // Check-in alarms are re-set rather than kept, because unlike periodic work they are wiped by
        // a reboot, by an app update and by the app being force-stopped. Doing it on every process
        // start means the schedule repairs itself whenever anything runs -- see CheckInAlarmScheduler.
        //
        // Detached, and safe to be: this used to race the rollover worker in this same process,
        // arming alarms from check-in rows the worker had not created yet. The scheduler now
        // guarantees those rows itself, so the two can no longer finish in the wrong order.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            CheckInAlarmScheduler.reschedule(this@ConsistencyApp)
        }
    }
}

/**
 * The container, from anywhere with a `Context`.
 *
 * `applicationContext` is deliberate: a `Worker` is handed one, and an Activity's own context would
 * be the wrong thing to reach the graph through anyway.
 */
val Context.container: AppContainer
    get() = (applicationContext as ConsistencyApp).container
