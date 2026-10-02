package com.violinstudio.data.commons.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FirebaseSdkAvailabilityTest {
    @Test
    fun `los SDK de Auth y Firestore estan en el classpath de data`() {
        assertNotNull(FirebaseAuth::class.java)
        assertNotNull(FirebaseFirestore::class.java)
    }
}
