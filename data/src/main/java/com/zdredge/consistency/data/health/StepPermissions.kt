package com.zdredge.consistency.data.health

import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord

/**
 * Asking for step access, without `:app` knowing what Health Connect is.
 *
 * **Health Connect's permission flow is not the ordinary runtime one** — it has its own
 * `ActivityResultContract`, and that contract is as much a Health Connect API as the reader is. If
 * `MainActivity` called it directly, an API change would land in two modules instead of one, which is
 * precisely what architecture §5 put the interface here to prevent. So the contract is built here and
 * handed out as a plain `ActivityResultContract`.
 *
 * Nothing caches the answer. `StepSource.status()` is asked at the moment it matters, because the
 * user can revoke this in system settings and the app is told nothing — the same reasoning M6
 * applied to notifications.
 */
object StepPermissions {

    /** The only health permission this app asks for, and the only one it should ever ask for. */
    val required: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    /** The contract to register with `registerForActivityResult`. */
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
}
