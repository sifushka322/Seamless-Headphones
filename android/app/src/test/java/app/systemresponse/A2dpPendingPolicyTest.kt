package app.systemresponse

import android.bluetooth.BluetoothProfile
import org.junit.Assert.*
import org.junit.Test

class A2dpPendingPolicyTest {
    @Test fun rejectedDisconnectDoesNotPermanentlyLockTheConnectedHeadset() {
        assertTrue(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_CONNECTED, 1, 1))
        assertTrue(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_DISCONNECTED, 1, 1))
    }

    @Test fun uncertaintyCannotAuthorizeAnotherPhysicalOperation() {
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(false, null, 1, 1))
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_CONNECTING, 1, 1))
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_DISCONNECTING, 1, 1))
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(true, BluetoothProfile.STATE_CONNECTED, 1, 1))
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(true, BluetoothProfile.STATE_DISCONNECTED, 1, 1))
    }

    @Test fun oldCancelledRejectionCannotUnlockANewerOperation() {
        // A broadcast settled attempt 1; attempt 3 began before attempt 1's worker reply.
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_CONNECTED, 1, 3))
        assertFalse(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_DISCONNECTED, 1, 3))
        // Cancellation alone changes the active token, not ownership of the pending attempt.
        // Its known rejection can still settle its own guard if no new attempt replaced it.
        assertTrue(A2dpPendingPolicy.rejectionIsSettled(false, BluetoothProfile.STATE_CONNECTED, 1, 1))
    }
}
