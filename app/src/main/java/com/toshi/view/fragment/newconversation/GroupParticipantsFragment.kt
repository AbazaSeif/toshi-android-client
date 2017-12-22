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

package com.toshi.view.fragment.newconversation

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.isVisible
import com.toshi.model.local.User
import com.toshi.util.SingleClickableSpan
import com.toshi.view.activity.ConversationSetupActivity
import com.toshi.view.adapter.SelectGroupParticipantAdapter
import com.toshi.viewModel.GroupParticipantsViewModel
import kotlinx.android.synthetic.main.view_group_participants.*

class GroupParticipantsFragment : Fragment() {

    private lateinit var viewModel: GroupParticipantsViewModel
    private lateinit var userAdapter: SelectGroupParticipantAdapter

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater?.inflate(R.layout.view_group_participants, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) = init()

    private fun init() {
        initViewModel()
        initRecyclerView()
        initClickListeners()
        initObservers()
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this.activity).get(GroupParticipantsViewModel::class.java)
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { this.activity.onBackPressed() }
        clearButton.setOnClickListener { search.text = null }
        next.setOnClickListener { handleNextClicked() }
    }

    private fun handleNextClicked() = (this.activity as ConversationSetupActivity).openGroupSetupFlow(viewModel.selectedParticipants.value!!)

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
        val selectedUsers = viewModel.selectedParticipants.value ?: listOf<User>()
        userAdapter.setSelectedUsers(selectedUsers)
    }

    private fun handleUserClicked(user: User) {
        userAdapter.addOrRemoveUser(user)
        viewModel.toggleSelectedParticipant(user)
        search.text = null
        search.requestFocus()
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
    }

    private fun initSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) = clearButton.isVisible(s.toString().isNotEmpty())
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = viewModel.queryUpdated(s)
        })
    }

    private fun handleSearchResults(searchResults: List<User>) = userAdapter.setUsers(searchResults)

    private fun handleSelectedParticipants(selectedParticipants: List<User>) {
        next.isVisible(selectedParticipants.isNotEmpty())
        participants.text = getParticipantsAsString(selectedParticipants)
        participants.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun getParticipantsAsString(selectedParticipants: List<User>): SpannableString {
        val spannableString = SpannableString(selectedParticipants.joinToString(
                separator = ", ",
                transform = { it.displayName }
        ))
        var lastEndPos = 0
        selectedParticipants.forEach { lastEndPos = createLinkSpan(spannableString, it, lastEndPos) }
        return spannableString
    }

    private fun createLinkSpan(spannableString: SpannableString, user: User, lastEndPos: Int): Int {
        user.displayName.let {
            val currentStartPos = spannableString.indexOf(it, lastEndPos)
            val currentEndPos = spannableString.indexOf(it, lastEndPos) + it.length
            val clickableSpan = object: SingleClickableSpan() {
                override fun onSingleClick(view: View) { userAdapter.setUsers(listOf(user)) }
            }

            spannableString.setSpan(clickableSpan,
                    currentStartPos,
                    currentEndPos,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

            return currentEndPos
        }
    }
}
