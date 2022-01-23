/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.drakeapk.sleeptracker.sleeptracker

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.drakeapk.sleeptracker.database.SleepDatabaseDao
import com.drakeapk.sleeptracker.database.SleepNight
import com.drakeapk.sleeptracker.formatNights
import kotlinx.coroutines.*

/**
 * ViewModel for SleepTrackerFragment.
 */
class SleepTrackerViewModel(
        val database: SleepDatabaseDao,
        application: Application
) : AndroidViewModel(application) {

    // To manage all our coroutines. We need a job. This viewModelJob allows us to cancel all
    // coroutines started by this viewModel the ViewModel is no longer used and destroyed so
    // that we don't end up with coroutines that have nowhere to return to.
    private var viewModelJob = Job()

    // When a ViewModel is destroyed, onCleared is called. We override this method to cancel all
    // coroutines started from the ViewModel.
    override fun onCleared() {
        super.onCleared()

        // we are telling the job to cancel all the coroutines
        viewModelJob.cancel()
    }

    // We need a scope for our coroutines to run in. The scope determines what thread the coroutine
    // will run on and it also needs to know about the job.
    // Our dispatchers here is Dispatchers.Main. This means coroutines launched in the UI scope will
    // run on the main thread.
    val uiScope = CoroutineScope(Dispatchers.Main + viewModelJob)

    // We create variable to hold the current night. We make this LiveData because we want to be
    // able to observe it, MutableLiveData, so that we can change it.
    private var tonight = MutableLiveData<SleepNight?>()

    // we create a variable to hold all the night data.
    private var nights = database.getAllNights()

    // we create liveData to observer when to navigate to sleepQuality screen
    private val _navigateToSleepQuality = MutableLiveData<SleepNight>()
    val navigateToSleepQuality = _navigateToSleepQuality

    val startButtonVisible = Transformations.map(tonight) {
        it == null
    }

    val stopButtonVisible = Transformations.map(tonight) {
        it != null
    }

    val clearButtonVisible = Transformations.map(nights) {
        it?.isNotEmpty()
    }

    private val _showSnackBarEvent = MutableLiveData<Boolean>()
    val showSnackBarEvent = _showSnackBarEvent

    val nightString = Transformations.map(nights) { nights ->
        formatNights(nights, application.resources)
    }

    init {
        // we need tonight set as soon as possible so we can work with it, so we do that in an init
        // block, where we call a function initializeTonight.
        initializeTonight()
    }

    //
    private fun initializeTonight() {
        // Here, we are using a coroutine to get tonight from the database so that we are not
        // not blocking the UI while waiting for the result.

        // Our code does the following we need to specify the scope, uiScope, and in that scope, we
        // launch a coroutine. Launching a coroutine creates the coroutine without blocking the
        // current thread into context defined by the scope.

        uiScope.launch {

            // We want to make sure getTonightFromDatabase does not block, meaning is not affecting
            // the main thread. and we want to return a sleepNight or null.
            tonight.value = getTonightFromDatabase()
        }
    }

    // we mark it as suspend because we want to call it from inside the coroutine and not block, and
    // we want to return a sleepNight or null.
    private suspend fun getTonightFromDatabase(): SleepNight? {

        // We create another coroutine in the IO contexts using the IO dispatcher, and call
        // database.getTonight() from the DAO. This returns the latest night saved in the DB
        return withContext(Dispatchers.IO) {
            var night = database.getTonight()
            if (night?.endTimeMilli != night?.startTimeMilli) {
                night = null
            }
            night
        }
    }

    // NOTE: We launch a coroutine that runs on the main UI thread because the result affects the UI
    // Inside, we call a suspendFunction to do the long running work so that we don't block the UI
    // thread while waiting for the result. We then define the suspendFunction. The longrunningwork
    // has nothing to do with the UI, so we switch to the IO context so that we can run in a thread
    // pool that is optimized and set aside for these operations.

    fun onStartTracking() {

        // We launch a coroutine because everything that happens from here on will be time consuming
        // especially the database operation. Again, we do this in the UI scope because we need this
        // result to continue and update the UI.
        uiScope.launch {
            val newNight = SleepNight()
            insert(newNight)
            tonight.value = getTonightFromDatabase()
        }
    }

    private suspend fun insert(newNight: SleepNight) {
        withContext(Dispatchers.IO) {
            database.insert(newNight)
        }
    }

    fun onStopTracking() {
        uiScope.launch {
            // In Kotlin, the return@label syntax is used for specifying which function among
            // several nested ones this statement returns from. In this case, we are specifying to
            // return from launch, not the Lambda.
            val oldNight = tonight.value ?: return@launch
            oldNight.endTimeMilli = System.currentTimeMillis()
            update(oldNight)
            _navigateToSleepQuality.value = oldNight
        }
    }

    private suspend fun update(oldNight: SleepNight) {
        withContext(Dispatchers.IO) {
            database.update(oldNight)
        }
    }

    fun onClear() {
        uiScope.launch {
            clear()
            tonight.value = null
            _showSnackBarEvent.value = true
        }
    }

    private suspend fun clear() {
        withContext(Dispatchers.IO) {
            database.clear()
        }
    }

    fun doneNavigating() {
        _navigateToSleepQuality.value = null
    }

    fun doneShowingSnackBar() {
        _showSnackBarEvent.value = false
    }
}
