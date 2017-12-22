package com.toshi.viewModel

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.R
import com.toshi.model.local.Group
import com.toshi.model.local.User
import com.toshi.util.LogUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.TimeUnit

class AddGroupParticipantsViewModel(val groupId: String) : ViewModel() {
    private val sofaMessageManager by lazy { BaseApplication.get().sofaMessageManager }
    private val recipientManager by lazy { BaseApplication.get().recipientManager }

    private val subscriptions by lazy { CompositeSubscription() }
    private val querySubject by lazy { PublishSubject.create<String>() }
    private val participants by lazy { mutableListOf<User>() }
    private val defaultResults by lazy { mutableListOf<User>() }

    val searchResults by lazy { SingleLiveEvent<List<User>>() }
    val selectedParticipants by lazy { MutableLiveData<List<User>>() }
    val isUpdatingGroup by lazy { MutableLiveData<Boolean>() }
    val participantsAdded by lazy { SingleLiveEvent<Unit>() }
    val error by lazy { SingleLiveEvent<Int>() }

    init {
        subscribeForQueryChanges()
    }

    private fun subscribeForQueryChanges() {
        val startSearchSub = querySubject.debounce(500, TimeUnit.MILLISECONDS)
                .filter { query -> query.length >= 3 }
                .subscribe(
                        { runSearchQuery(it) },
                        { LogUtil.e(javaClass, "Error while listening for query changes $it") }
                )

        val clearSub = querySubject.filter { query -> query.length < 3 }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { showDefaultResults() },
                        { LogUtil.e(javaClass, "Error while listening for query changes $it") }
                )

        val defaultSub = recipientManager
                .loadAllUserContacts()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { cacheDefaultResults(it) },
                        { LogUtil.e(javaClass, "Error while fetching contacts $it") }
                )
        this.subscriptions.addAll(startSearchSub, clearSub, defaultSub)
    }

    private fun showDefaultResults() {
        if (searchResults.value != defaultResults) searchResults.value = defaultResults
    }

    private fun cacheDefaultResults(contacts: List<User>) {
        defaultResults.clear()
        defaultResults.addAll(contacts)
        if (searchResults.value == null) searchResults.value = defaultResults
    }

    fun queryUpdated(query: CharSequence?) = querySubject.onNext(query.toString())

    private fun runSearchQuery(query: String) {
        val searchSub =
                Single.zip(
                        recipientManager.searchOnlineUsers(query),
                        getGroupMembers(groupId),
                        { searchResult, groupMembers -> Pair(searchResult, groupMembers) }
                )
                .subscribeOn(Schedulers.io())
                .map { filterSearchResult(it.first, it.second) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { searchResults.value = it },
                        { LogUtil.e(javaClass, "Error while search for user $it") }
                )

        this.subscriptions.add(searchSub)
    }

    private fun filterSearchResult(searchResults: List<User>, groupMembers: List<User>): List<User> {
        val filteredList = mutableListOf<User>()
        for (searchUser in searchResults) {
            val contains = groupMembers.any { searchUser.toshiId == it.toshiId }
            if (!contains) filteredList.add(searchUser)
        }
        return filteredList
    }

    private fun getGroupMembers(groupId: String) = Group.fromId(groupId).map { it.members }

    fun toggleSelectedParticipant(user: User) {
        if (participants.contains(user)) participants.remove(user)
        else participants.add(user)
        this.selectedParticipants.value = this.participants
    }

    fun updateGroup(groupId: String) {
        if (isUpdatingGroup.value == true) return
        val subscription =
                Group.fromId(groupId)
                .map { it.addMembers(selectedParticipants.value) }
                .flatMapCompletable { sofaMessageManager.updateConversationFromGroup(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isUpdatingGroup.value = true }
                .doAfterTerminate { isUpdatingGroup.value = false }
                .subscribe(
                        { participantsAdded.value = null },
                        { error.value = R.string.add_participants_error }
                )
        this.subscriptions.add(subscription)
    }

    override fun onCleared() {
        super.onCleared()
        this.subscriptions.clear()
    }
}