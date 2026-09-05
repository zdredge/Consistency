package com.zdredge.consistency.data.db

/**
 * Spec constraint 15: every table carries a `user_id` from the first schema, unused in v1.
 *
 * It is here rather than absent because retrofitting a column onto eleven tables is a migration
 * nobody wants to write, and it is a constant rather than a setting because there is exactly one
 * user and pretending otherwise would invite code that branches on it.
 */
const val LOCAL_USER_ID: String = "local"
