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

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.toshi.R
import com.toshi.extensions.startActivity
import com.toshi.util.ImageUtil
import kotlinx.android.synthetic.main.activity_view_dapp.*

class ViewDappActivity : AppCompatActivity() {

    companion object {
        const val EXTRA__DAPP_ADDRESS = "extra_dapp_address"
        const val EXTRA__DAPP_NAME = "extra_dapp_name"
        const val EXTRA__DAPP_AVATAR = "extra_dapp_avatar"
        const val EXTRA__DAPP_ABOUT = "extra_dapp_about"
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_dapp)
        init()
    }

    private fun init() {
        initToolbar()
        initClickListeners()
        initUi()
    }

    private fun initToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { onBackPressed() }
        enter.setOnClickListener { startWebViewActivity() }
    }

    private fun startWebViewActivity() {
        startActivity<WebViewActivity> {
            putExtra(WebViewActivity.EXTRA__ADDRESS, getAddressFromIntent())
        }
    }

    private fun initUi() {
        toolbarTitle.text = getNameFromIntent()
        name.text = getNameFromIntent()
        about.text = getAboutFromIntent()
        address.text = getAddressFromIntent()
        ImageUtil.load(getAvatarFromIntent(), avatar)
    }

    private fun getAddressFromIntent() = intent.getStringExtra(EXTRA__DAPP_ADDRESS)

    private fun getNameFromIntent() = intent.getStringExtra(ViewDappActivity.EXTRA__DAPP_NAME)

    private fun getAboutFromIntent() = intent.getStringExtra(ViewDappActivity.EXTRA__DAPP_ABOUT)

    private fun getAvatarFromIntent() = intent.getStringExtra(ViewDappActivity.EXTRA__DAPP_AVATAR)
}
