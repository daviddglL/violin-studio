package com.violinstudio.ui.commons

import androidx.credentials.CredentialManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CredentialsAvailabilityTest {
    @Test
    fun `Credential Manager y googleid estan en el classpath de ui`() {
        assertNotNull(CredentialManager::class.java)
        assertNotNull(GetGoogleIdOption::class.java)
    }
}
