package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vault.VaultManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TubeAIO NextGen", appName)
  }

  @Test
  fun `vault pin configuration and verification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val vaultManager = VaultManager.getInstance(context)

    vaultManager.setPin("1234")
    assertTrue(vaultManager.isPinConfigured())
    assertTrue(vaultManager.verifyPin("1234"))
    assertFalse(vaultManager.verifyPin("9999"))

    vaultManager.lockVault()
    assertFalse(vaultManager.isUnlocked.value)

    assertTrue(vaultManager.verifyPin("1234"))
    assertTrue(vaultManager.isUnlocked.value)
  }
}
