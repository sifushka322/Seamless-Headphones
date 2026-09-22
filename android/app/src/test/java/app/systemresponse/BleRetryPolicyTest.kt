package app.systemresponse

import org.junit.Assert.*
import org.junit.Test

class BleRetryPolicyTest {
    @Test fun transientDisconnectAndSearchTimeoutKeepRetryingWithBoundedBackoff() {
        val delays = (0..7).map { BleRetryPolicy.nextDelaySeconds(BleFailure.TRANSIENT, true, true, it) }
        assertEquals(listOf(5, 10, 20, 40, 60, 60, 60, 60), delays)
        assertNull(BleRetryPolicy.nextDelaySeconds(BleFailure.TRANSIENT, true, true, 8))
    }

    @Test fun authenticationAndPermissionFailuresRequireUserAction() {
        for (failure in listOf(BleFailure.AUTHENTICATION, BleFailure.PERMISSION, BleFailure.INCOMPATIBLE_SERVICE)) {
            assertNull(BleRetryPolicy.nextDelaySeconds(failure, true, true, 0))
        }
    }

    @Test fun stopAndReconnectPreferenceOverrideTransientRecovery() {
        assertNull(BleRetryPolicy.nextDelaySeconds(BleFailure.TRANSIENT, false, true, 0))
        assertNull(BleRetryPolicy.nextDelaySeconds(BleFailure.TRANSIENT, true, false, 0))
    }
}
