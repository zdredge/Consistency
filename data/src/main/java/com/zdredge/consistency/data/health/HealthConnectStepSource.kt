package com.zdredge.consistency.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * The only Health Connect implementation there is or should be.
 *
 * M0 proved the shape this uses: `getSdkStatus` returned `SDK_AVAILABLE` with **no separate install**
 * (framework-provided from Android 14, which is why `minSdk` is 34), and after walking, records
 * appeared attributed to `com.android.healthconnect.phone.jf9fc…` — the device-specific synthetic
 * package, not the generic `android` one and not a source app.
 */
class HealthConnectStepSource(
    private val context: Context,
    private val dayResolver: DayResolver,
) : StepSource {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun status(): StepSourceStatus {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return StepSourceStatus.Unavailable
        }

        // Asked every time rather than cached. The user can revoke this in system settings without
        // the app being told, exactly as with notifications in M6 -- a remembered "yes" would leave
        // steps on screen showing a number that had stopped updating.
        return runCatching {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(StepPermissions.required)) {
                StepSourceStatus.Available
            } else {
                StepSourceStatus.PermissionMissing
            }
        }.getOrElse {
            Log.e(TAG, "could not read Health Connect permissions", it)
            StepSourceStatus.Unavailable
        }
    }

    /**
     * **The day is 04:00 to 04:00, not midnight to midnight.**
     *
     * The M0 spike read `LocalDate.now().atStartOfDay()`, which was right for a spike and wrong here:
     * every step walked between midnight and 04:00 would be filed under the wrong day, and this app's
     * whole point is that the day ends when the user goes to bed. `DayResolver` owns that boundary
     * (spec constraint 1) and its half-open range is exactly what `TimeRangeFilter.between` wants.
     */
    override suspend fun readDay(day: LocalDate): List<MeasuredOrigin> {
        if (status() != StepSourceStatus.Available) return emptyList()

        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    dayResolver.startOfDay(day),
                    dayResolver.endOfDayExclusive(day),
                ),
            ),
        ).records

        // Grouped, never summed across origins. One entry per source is what makes a second source
        // detectable at all -- see StepMapper for what is done about it.
        return records
            .groupBy { it.metadata.dataOrigin.packageName }
            .map { (origin, rows) -> MeasuredOrigin(origin, rows.sumOf { it.count }.toDouble()) }
            .sortedByDescending { it.value }
    }

    private companion object {
        const val TAG = "StepSource"
    }
}
