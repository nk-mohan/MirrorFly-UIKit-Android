package com.contusfly.activities

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.contus.flycommons.ChatType
import com.contus.flycommons.emptyStringFE
import com.contus.webrtc.CallType
import com.contus.webrtc.api.CallManager
import com.contus.xmpp.chat.utils.LibConstants
import com.contusfly.*
import com.contusfly.adapters.ContactsAdapter
import com.contusfly.databinding.ActivityNewContactsBinding
import com.contusfly.fragments.ProfileDialogFragment
import com.contusfly.utils.Constants
import com.contusfly.utils.UserInterfaceUtils
import com.contusfly.viewmodels.ContactViewModel
import com.contusfly.views.CommonAlertDialog
import com.contusflysdk.AppUtils
import com.contusflysdk.api.FlyCore
import com.contusflysdk.api.contacts.ContactManager
import com.contusflysdk.api.contacts.ProfileDetails
import com.contusflysdk.views.CustomToast

/**
 * @author ContusTeam <developers@contus.in>
 * @version 1.0
 */
class NewContactsActivity : BaseActivity(), CommonAlertDialog.CommonDialogClosedListener {

    var users: ArrayList<ProfileDetails>? = null

    private lateinit var newContactsBinding: ActivityNewContactsBinding

    private lateinit var mAdapter: ContactsAdapter

    private var multiSelection = false

    private var isMakeCall = false

    private var callType: String? = null

    private var addParticipants = false

    private var fromGroupInfo = false

    private var screenTitle: String = emptyStringFE()

    private var selectedUsersJid = ArrayList<String>()

    /**
     * Used for search
     */
    private var searchKey: String = emptyString()

    /**
     * Store onclick time to avoid double click
     */
    private var lastClickTime: Long = 0

    private var groupId:String? = null

    /**
     * The common alert dialog to display the alert dialogs in the alert view
     */
    private var commonAlertDialog: CommonAlertDialog? = null

    private val viewModel by lazy {
        ViewModelProvider(this).get(ContactViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        newContactsBinding = ActivityNewContactsBinding.inflate(layoutInflater)
        setContentView(newContactsBinding.root)
        screenTitle = if (intent.hasExtra(Constants.TITLE)) intent.getStringExtra(Constants.TITLE)!! else getString(R.string.title_contacts)
        addParticipants = intent.getBooleanExtra(Constants.ADD_PARTICIAPANTS, false)
        fromGroupInfo = intent.getBooleanExtra(Constants.FROM_GROUP_INFO, false)
        groupId = intent.getStringExtra(Constants.GROUP_ID)
        multiSelection = intent.getBooleanExtra(Constants.MULTI_SELECTION, addParticipants)
        isMakeCall = intent.getBooleanExtra(Constants.IS_MAKE_CALL, false)
        callType = intent.getStringExtra(Constants.CALL_TYPE)
        setUpToolBar()
        initViews()
        setObservers()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.contact_menu, menu)

        val menuItem = menu?.findItem(R.id.action_search)
        val mSearchView = menu?.findItem(R.id.action_search)?.actionView as SearchView

        val actionItem = menu.get(R.id.action_done)
        val settingsItem = menu.get(R.id.action_settings)

        when {
            addParticipants -> {
                if(fromGroupInfo){
                    actionItem.title = getString(R.string.label_next)
                }else{
                    actionItem.title = getString(R.string.label_create)
                }
                settingsItem.show()
            }
            isMakeCall -> {
                actionItem.hide()
                settingsItem.hide()
            }
            else -> {
                actionItem.hide()
            }
        }

        mSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(s: String): Boolean {
                return false
            }

            override fun onQueryTextChange(searchString: String): Boolean {
                searchKey = searchString.trim()
                mAdapter.filter.filter(searchKey)
                return true
            }
        })

        menuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                if (!addParticipants) mSearchView.maxWidth = Integer.MAX_VALUE
                closeMenuOption(menu)
                setEmptyView(true)
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                showMenuOption(menu)
                setEmptyView(false)
                return true
            }
        })
        return true

    }

    private fun setEmptyView(b: Boolean) {
        searchKey = emptyString()
        if (b) {
            val emptyView: TextView = newContactsBinding.emptyList.textEmptyView
            emptyView.text = getString(R.string.msg_no_results)
            emptyView.setTextColor(ResourcesCompat.getColor(resources, R.color.color_text_no_list, null))
            newContactsBinding.viewListContacts.setEmptyView(emptyView)

        } else {
            newContactsBinding.viewListContacts.setEmptyView(newContactsBinding.noContactsView.root)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        updateContactsList()
    }

    private fun showMenuOption(menu: Menu): Boolean {
        with(menu) {
            get(R.id.action_done).isVisible = addParticipants
            get(R.id.action_settings).isVisible = !addParticipants && !isMakeCall
        }
        super.onPrepareOptionsMenu(menu)
        return true
    }

    private fun closeMenuOption(menu: Menu): Boolean {
        with(menu) {
            get(R.id.action_settings).hide()
            if (!addParticipants) get(R.id.action_done).hide()
        }
        super.onPrepareOptionsMenu(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_done -> {
                if (addParticipants) {
                    if (selectedUsersJid.size < 2 && !fromGroupInfo) {
                        showToast("Add at least two contacts")
                    } else {
                        val output = Intent().apply {
                            putStringArrayListExtra(Constants.USERS_JID, selectedUsersJid)
                        }
                        setResult(RESULT_OK, output)
                        finish()
                    }
                }

                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setUpToolBar() {
        setSupportActionBar(newContactsBinding.toolbar)
        supportActionBar?.title = screenTitle
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        UserInterfaceUtils.initializeCustomToolbar(this, newContactsBinding.toolbar)
        newContactsBinding.toolbar.navigationIcon?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(ContextCompat.getColor(this, R.color.dashboard_toolbar_text_color), BlendModeCompat.SRC_ATOP)
    }

    private fun initViews() {
        commonAlertDialog = CommonAlertDialog(this)
        commonAlertDialog!!.setOnDialogCloseListener(this)
        mAdapter = ContactsAdapter(
            this, commonAlertDialog!!,
            multiSelection,
            viewModel.contactListAdapter,
            isMakeCall) { _: Int, profileClicked: Boolean, profile: ProfileDetails ->
            listItemClicked(profileClicked, profile)
        }
        mAdapter.setHasStableIds(true)

        newContactsBinding.viewListContacts.apply {
            layoutManager = LinearLayoutManager(context)
            setItemViewCacheSize(10)
            setHasFixedSize(true)
            setEmptyView(newContactsBinding.noContactsView.root)
            itemAnimator = null
            adapter = mAdapter
        }
        if (isMakeCall && !callType.isNullOrEmpty()) {
            newContactsBinding.buttonMakeCall.gone()
            newContactsBinding.buttonMakeCall.text = String.format(getString(R.string.action_call_now), selectedUsersJid.size)
            if (callType.equals(CallType.VIDEO_CALL)) {
                newContactsBinding.buttonMakeCall.icon = ContextCompat.getDrawable(this, R.drawable.ic_fab_video_call)
            } else {
                newContactsBinding.buttonMakeCall.icon = ContextCompat.getDrawable(this, R.drawable.ic_fab_voice_call)
            }
            newContactsBinding.buttonMakeCall.setOnClickListener(1000) {
                checkInternetAndExecute {
                    val output = Intent().apply {
                        putStringArrayListExtra(Constants.USERS_JID, selectedUsersJid)
                        putExtra(Constants.CALL_TYPE, callType)
                    }
                    setResult(RESULT_OK, output)
                    finish()
                }
            }
        } else {
            newContactsBinding.buttonMakeCall.gone()
        }
    }

    private fun setObservers() {
        viewModel.contactDiffResult.observe(this, {
            // Save Current Scroll state to retain scroll position after DiffUtils Applied
            val previousState = newContactsBinding.viewListContacts.layoutManager?.onSaveInstanceState() as Parcelable
            it.dispatchUpdatesTo(mAdapter)
            newContactsBinding.viewListContacts.layoutManager?.onRestoreInstanceState(previousState)
        })
    }

    private fun updateContactsList() {
        viewModel.getContactList(fromGroupInfo, groupId)
        checkInternetAndExecute(false) {
            viewModel.getUpdatedContactList(fromGroupInfo, groupId)
        }
    }

    private fun listItemClicked(profileClicked : Boolean, profile: ProfileDetails) {
        if (multiSelection) {
            handleMultiSelection(profile)
        } else {
            if(profileClicked){
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000)
                    return
                lastClickTime = SystemClock.elapsedRealtime()
                val dialogFragment = ProfileDialogFragment.newInstance(ContactManager.getProfileDetails(profile.jid)!!)
                val ft = supportFragmentManager.beginTransaction()
                val prev = supportFragmentManager.findFragmentByTag("dialog")
                if (prev != null) {
                    ft.remove(prev)
                }
                ft.addToBackStack(null)
                dialogFragment.show(ft, "dialog")
            } else{
                startActivity(Intent(context, ChatActivity::class.java)
                    .putExtra(LibConstants.JID, profile.jid)
                    .putExtra(Constants.CHAT_TYPE, ChatType.TYPE_CHAT))
            }
        }
    }

    private fun handleMultiSelection(profile: ProfileDetails) {
        if (profile.isBlocked)
            return
        if (selectedUsersJid.contains(profile.jid)) {
            selectedUsersJid.remove(profile.jid)
        } else {
            selectedUsersJid.add(profile.jid)
        }
        if (isMakeCall) {
            newContactsBinding.buttonMakeCall.show()
            newContactsBinding.buttonMakeCall.isEnabled = selectedUsersJid.size > 0
            if ((selectedUsersJid.size + 1) > CallManager.getMaxCallUsersCount()) {
                selectedUsersJid.remove(profile.jid)
                newContactsBinding.buttonMakeCall.text =
                    String.format(getString(R.string.action_call_now), selectedUsersJid.size)
                newContactsBinding.buttonMakeCall.isEnabled = false
                val message = String.format(
                    getString(com.contus.call.R.string.info_call_members_limit),
                    CallManager.getMaxCallUsersCount()
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                if (selectedUsersJid.size == 0) newContactsBinding.buttonMakeCall.gone() else newContactsBinding.buttonMakeCall.show()
                newContactsBinding.buttonMakeCall.text = String.format(getString(R.string.action_call_now), selectedUsersJid.size)
            }
        } else newContactsBinding.buttonMakeCall.gone()
    }


    override fun userUpdatedHisProfile(jid: String) {
        super.userUpdatedHisProfile(jid)
        updateUserProfileInfo(jid)
    }
    override fun userDetailsUpdated() {
        super.userDetailsUpdated()
        updateContactsList()
    }

    override fun userBlockedMe(jid: String) {
        super.userBlockedMe(jid)
        updateUserProfileInfo(jid)
    }

    override fun userUnBlockedMe(jid: String) {
        super.userUnBlockedMe(jid)
        updateUserProfileInfo(jid)
    }

    private fun updateUserProfileInfo(jid: String) {
        val userIndex = viewModel.contactListAdapter.indexOfFirst { profile -> profile.jid == jid }
        if (userIndex.isValidIndex()) {
            val oldProfile = viewModel.contactListAdapter[userIndex]
            val updatedProfile = ContactManager.getProfileDetails(jid)!!
            updatedProfile.isSelected = oldProfile.isSelected
            viewModel.contactListAdapter[userIndex] = updatedProfile
            mAdapter.notifyItemChanged(userIndex)
            mAdapter.filter.filter(searchKey)

        }
    }

    override fun onDialogClosed(dialogType: CommonAlertDialog.DIALOGTYPE?, isSuccess: Boolean) {
        if (isSuccess) {
            if (isSuccess && mAdapter.blockedUser.isNotEmpty()) {
                if (AppUtils.isNetConnected(this)) {
                    FlyCore.unblockUser(mAdapter.blockedUser) { success, _, _ ->
                        if (success) {
                            updateUserProfileInfo(mAdapter.blockedUser)
                            runOnUiThread { mAdapter.filter.filter(searchKey) }
                            mAdapter.blockedUser = emptyString()
                        } else {
                            CustomToast.show(this, Constants.ERROR_SERVER)
                            mAdapter.blockedUser = emptyString()
                        }
                    }
                } else {
                    CustomToast.show(this, getString(R.string.msg_no_internet))
                    mAdapter.blockedUser = emptyString()
                }
            } else {
                mAdapter.blockedUser = emptyString()
            }
        }
    }

    override fun listOptionSelected(position: Int) {
        //Do nothing
    }
}