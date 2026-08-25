package com.gyrobridge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyrobridge.app.data.repository.ProfileRepository
import com.gyrobridge.app.domain.model.ControlProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryInstrumentedTest {
    @Test fun profilePersistsInDataStore()=runBlocking { val context=ApplicationProvider.getApplicationContext<android.content.Context>();val repo=ProfileRepository(context);val profile=ControlProfile(name="Instrumented ${System.nanoTime()}");repo.save(profile);assertEquals(profile.name,repo.profiles.first().first{it.id==profile.id}.name);repo.delete(profile.id) }
}
