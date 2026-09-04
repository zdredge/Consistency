package com.zdredge.consistency.domain.time

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * docs/architecture.md section 5: ":domain has no Android imports. If it ever needs one, something
 * has been put in the wrong place."
 *
 * The real enforcement is structural -- :domain is a plain Kotlin JVM module, so android.jar is not
 * on its classpath and an Android import cannot compile. This is the belt-and-braces check
 * build-order M1 asks for, and it fails loudly if anyone converts :domain to an Android module.
 */
@DisplayName("the :domain module stays Android-free")
class DomainHasNoAndroidTest {

    @Test
    @DisplayName("the Android SDK is not on the :domain classpath")
    fun androidSdkIsNotOnDomainClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("android.content.Context")
        }
    }
}
