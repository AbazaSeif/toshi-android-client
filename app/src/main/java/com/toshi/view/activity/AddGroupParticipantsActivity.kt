/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.activity

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.isVisible
import com.toshi.extensions.toast
import com.toshi.model.local.User
import com.toshi.view.adapter.SelectGroupParticipantAdapter
import com.toshi.view.adapter.listeners.TextChangedListener
import com.toshi.viewModel.AddGroupParticipantsViewModel
import com.toshi.viewModel.ViewModelFactory.AddGroupParticipantsViewModelFactory
import kotlinx.android.synthetic.main.view_group_participants.*

class AddGroupParticipantsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA__GROUP_ID = "extra_group_id"
    }

    private lateinit var viewModel: AddGroupParticipantsViewModel
    private lateinit var userAdapter: SelectGroupParticipantAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_group_participants)
        init()
    }

    private fun init() {
        initView()
        initViewModel()
        initRecyclerView()
        initClickListeners()
        initObservers()
    }

    private fun initView() {
        toolbarTitle.setText(R.string.add_participants)
        next.setText(R.string.done)
    }

    private fun initViewModel() {
        val groupId = getGroupIdFromIntent()
        if (groupId == null) {
            toast(R.string.invalid_group)
            finish()
            return
        }

        viewModel = ViewModelProviders.of(
                this,
                AddGroupParticipantsViewModelFactory(groupId)
        ).get(AddGroupParticipantsViewModel::class.java)
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { finish() }
        clearButton.setOnClickListener { search.text = null }
        next.setOnClickListener { handleDoneClicked() }
    }

    private fun handleDoneClicked() {
        val groupId = getGroupIdFromIntent()
        groupId?.let { viewModel.updateGroup(it) } ?: toast(R.string.invalid_group)
    }

    private fun initRecyclerView() {
        initAdapter()
        searchResults.apply {
            layoutManager = LinearLayoutManager(this.context)
            itemAnimator = DefaultItemAnimator()
            adapter = userAdapter
            addHorizontalLineDivider()
        }
    }

    private fun initAdapter() {
        userAdapter = SelectGroupParticipantAdapter().setOnItemClickListener(this::handleUserClicked)
        val selectedUsers = viewModel.selectedParticipants.value ?: emptyList()
        userAdapter.setSelectedUsers(selectedUsers)
    }

    private fun handleUserClicked(user: User) {
        userAdapter.addOrRemoveUser(user)
        viewModel.toggleSelectedParticipant(user)
    }

    private fun initObservers() {
        initSearch()
        viewModel.searchResults.value.let { handleSearchResults(it.orEmpty()) }
        viewModel.searchResults.observe(this, Observer { searchResults ->
            searchResults?.let { handleSearchResults(it) }
        })
        viewModel.selectedParticipants.observe(this, Observer { selectedParticipants ->
            selectedParticipants?.let { handleSelectedParticipants(it) }
        })
        viewModel.isUpdatingGroup.observe(this, Observer {
            isUpdatingGroup -> isUpdatingGroup?.let { loadingSpinner.isVisible(it) }
        })
        viewModel.participantsAdded.observe(this, Observer { finish() })
        viewModel.error.observe(this, Observer {
            errorMessage -> errorMessage?.let { toast(it) }
        })
    }

    private fun initSearch() {
        search.addTextChangedListener(object : TextChangedListener() {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.isVisible(s.toString().isNotEmpty())
                viewModel.queryUpdated(s)
            }
        })
    }

    private fun handleSearchResults(searchResults: List<User>) = userAdapter.setUsers(searchResults)

    private fun handleSelectedParticipants(selectedParticipants: List<User>) {
        next.isVisible(selectedParticipants.isNotEmpty())
        participants.text = getParticipantsAsString(selectedParticipants)
    }

    private fun getParticipantsAsString(selectedParticipants: List<User>): String {
        return selectedParticipants.joinToString(
                separator = ", ",
                transform = { it.displayName }
        )
    }

    private fun getGroupIdFromIntent(): String? = intent.getStringExtra(GroupInfoActivity.EXTRA__GROUP_ID)
}