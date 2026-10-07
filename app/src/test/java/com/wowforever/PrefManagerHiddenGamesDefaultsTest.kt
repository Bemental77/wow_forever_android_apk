package com.wowforever

import com.wowforever.utils.FakeDataStore
import com.wowforever.utils.installFakePrefManager
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefManagerHiddenGamesDefaultsTest {

    @Test
    fun showHiddenGamesByDefaultDefaultsToTrue() {
        installFakePrefManager(FakeDataStore()).use {
            assertTrue(PrefManager.showHiddenGamesByDefault)
        }
    }
}
