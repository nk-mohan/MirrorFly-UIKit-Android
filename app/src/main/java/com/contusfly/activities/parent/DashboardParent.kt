package com.contusfly.activities.parent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProviders
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager.widget.ViewPager
import com.contus.flycommons.ChatType
import com.contus.flycommons.FlyCallback
import com.contus.xmpp.chat.models.Profile
import com.contusfly.*
import com.contusfly.activities.*
import com.contusfly.adapters.ViewPagerAdapter
import com.contusfly.di.factory.AppViewModelFactory
import com.contusfly.call.calllog.CallHistoryFragment
import com.contusfly.call.calllog.CallLogViewModel
import com.contusfly.databinding.ActivityDashboardBinding
import com.contusfly.fragments.RecentChatListFragment
import com.contusfly.interfaces.RecentChatEvent
import com.contusfly.utils.*
import com.contusfly.utils.Constants.Companion.CALLS_TAB_INDEX
import com.contusfly.viewmodels.DashboardViewModel
import com.contusfly.views.CommonAlertDialog
import com.contusflysdk.AppUtils
import com.contusflysdk.api.*
import com.contusflysdk.api.contacts.ContactManager
import com.contusflysdk.api.models.RecentChat
import com.contusflysdk.models.RecentSearch
import com.contusflysdk.utils.Utils
import com.contusflysdk.views.CustomToast
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 *
 * @author ContusTeam <developers@contus.in>
 * @version 1.0
 */
@SuppressLint("Registered")
open class DashboardParent : BaseActivity(), CoroutineScope {

    protected val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        println("Coroutine Exception ${TAG}:  ${exception.printStackTrace()}")
    }

    lateinit var dashboardBinding: ActivityDashboardBinding
    open var actionMode: ActionMode? = null
    open lateinit var mSearchView: SearchView
    open lateinit var tabLayout: TabLayout
    open lateinit var mViewPager: ViewPager
    open lateinit var swipeRefreshLayout: SwipeRefreshLayout
    lateinit var mAdapter: ViewPagerAdapter
    open lateinit var actionModeMenu: Menu

    open val commonAlertDialog: CommonAlertDialog by lazy { CommonAlertDialog(this) }

    open var isPageChanged = false
    open var searchItemClickedPosition = -1
    var mRecentChatListType = RecentChatListType.RECENT
    open var searchKey = Constants.EMPTY_STRING

    // View
    lateinit var recentChatFragment: RecentChatListFragment
    lateinit var callHistoryFragment: CallHistoryFragment

    @Inject
    open lateinit var dashboardViewModelFactory: AppViewModelFactory
    open val viewModel by lazy {
        ViewModelProviders.of(this, dashboardViewModelFactory).get(DashboardViewModel::class.java)
    }

    val callLogviewModel: CallLogViewModel by viewModels { dashboardViewModelFactory }

    fun openOption(menu: Menu): Boolean {
        with(menu) {
            showMenu(get(R.id.action_settings), get(R.id.action_group_chat), get(R.id.action_web_settings))
        }
        super.onPrepareOptionsMenu(menu)
        return true
    }

    fun closeOption(menu: Menu): Boolean {
        with(menu) {
            hideMenu(get(R.id.action_settings), get(R.id.action_new), get(R.id.action_group_chat), get(R.id.action_web_settings))
        }
        super.onPrepareOptionsMenu(menu)
        return true
    }


    /**
     * Sets the typing status in the recent chat window. It shows the user who is composing the message..
     *
     * @param singleOrGroupJid     Jabber id of the user/group
     * @param userId           Jabber id of the user, if user typing in group; otherwise empty string
     * @param typingStatus   Composing - if currently typing; Gone - if idle
     */
    override fun setTypingStatus(singleOrGroupJid: String, userId: String, typingStatus: String) {
        if (mRecentChatListType == RecentChatListType.RECENT) {
            if (typingStatus.equals(Constants.COMPOSING, ignoreCase = true))
                viewModel.setTypingStatus(Triple(singleOrGroupJid, userId, true))
            else viewModel.setTypingStatus(Triple(singleOrGroupJid, userId, false))
            recentChatFragment.onTypingAndGoneStatusUpdate(singleOrGroupJid)
        }
    }

    /**
     * update chat when new group created
     *
     * @param groupJid Group jid of the Mix group chat
     */
    override fun onNewGroupCreated(groupJid: String) {
        super.onNewGroupCreated(groupJid)
        viewModel.groupCreatedLiveData.value = groupJid
    }

    /**
     * getting the group participants response
     *
     * @param isError True if the participants got successfully
     * @param groupId Group Jid of the Mix group chat.
     */
    override fun onGetGroupParticipants(isError: Boolean, groupId: String) {
        viewModel.getRecentChatOfUser(groupId, RecentChatEvent.GROUP_EVENT)
    }

    /**
     * Handle the new user added in the group.
     *
     * @param groupJid Group jid
     * @param newMemberJid Jabber id of the User
     * @param addedByMemberJid Jid of user who adds the member
     */
    override fun onNewMemberAddedToGroup(groupJid: String, newMemberJid: String, addedByMemberJid: String) {
        super.onNewMemberAddedToGroup(groupJid, newMemberJid, addedByMemberJid)
        viewModel.groupNewUserAddedLiveData.value = groupJid
        viewModel.unreadChatCountLiveData.value = FlyMessenger.getUnreadMessagesCount()
    }

    /**
     * Handle the user removed from the group
     *
     * @param groupJid Group id
     * @param removedMemberJid Removed member Jid
     * @param removedByMemberJid Made remove member jid
     */
    override fun onMemberRemovedFromGroup(groupJid: String, removedMemberJid: String, removedByMemberJid: String) {
        super.onMemberRemovedFromGroup(groupJid, removedMemberJid, removedByMemberJid)
        viewModel.groupUserRemovedLiveData.value = groupJid
    }

    override fun onLeftFromGroup(groupJid: String, leftUserJid: String) {
        super.onLeftFromGroup(groupJid, leftUserJid)
        viewModel.groupUserRemovedLiveData.value = groupJid
    }

    override fun onDeleteGroup(groupJid: String) {
        super.onDeleteGroup(groupJid)
        viewModel.getRecentChatOfUser(groupJid, RecentChatEvent.GROUP_EVENT)
    }

    /**
     * Called when the group is updated
     *
     * @param groupJid Group jid
     */
    override fun onGroupProfileUpdated(groupJid: String) {
        super.onGroupProfileUpdated(groupJid)
        viewModel.groupUpdatedLiveData.value = groupJid
    }

    /**
     * Called when the group admin changed the affiliation
     *
     * @param groupJid Group jid
     * @param newAdminMemberJid New admin jid
     * @param madeByMemberJid Made new admin jid
     */
    override fun onMemberMadeAsAdmin(groupJid: String, newAdminMemberJid: String, madeByMemberJid: String) {
        super.onMemberMadeAsAdmin(groupJid, newAdminMemberJid, madeByMemberJid)
        viewModel.groupAdminChangedLiveData.value = groupJid
    }

    protected fun filterResult(searchKey: String) {
        this.searchKey = searchKey
        if (mViewPager.currentItem == CALLS_TAB_INDEX)
            viewModel.callsSearchKey.value = this.searchKey
        else
            viewModel.searchKeyLiveData.value = searchKey
        mRecentChatListType = if (searchKey.isEmpty()) RecentChatListType.RECENT else RecentChatListType.SEARCH
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + Job()

    override fun onConnectionNotAuthorized() {
       showToast("Already logged in another device")
    }

    /**
     * Called when Xmpp tcp connections successfully initiated.
     */
    override fun connectionSuccess() {
        dashboardBinding.dashboardXmppConnectionStatusLayout.gone()
    }

    override fun reconnecting() {
        if (SharedPreferenceManager.getBoolean(Constants.SHOW_LABEL))
            dashboardBinding.dashboardXmppConnectionStatusLayout.show()
        if (AppUtils.isNetConnected(this)) {
            dashboardBinding.dashboardXmppConnectionText.setBackgroundResource(R.color.connection_status)
            dashboardBinding.dashboardXmppConnectionText.text = "Connecting...."
        }
    }

    /**
     * Called when the connection has been failed from the server
     *
     * @param message Connection failed message
     */
    override fun connectionFailed(message: String) {
        // Code will added on Overridden Place
        if (SharedPreferenceManager.getBoolean(Constants.SHOW_LABEL))
            dashboardBinding.dashboardXmppConnectionStatusLayout.show()
        dashboardBinding.dashboardXmppConnectionText.text = if (AppUtils.isNetConnected(this))
            resources.getString(R.string.connection_lost)
        else resources.getString(R.string.msg_no_internet)
    }

    override fun onBackPressed() {
        /*
         * Check the current fragment position to redirect to recent chat/background.
         */
        if (mViewPager.currentItem == 1)
            mViewPager.setCurrentItem(0, true)
        else {
            super.onBackPressed()
            finishAffinity()
        }
    }

    /**
     * enum constants for List type, used to differentiate the list in the recent chat view
     */
    enum class RecentChatListType {
        RECENT,
        SEARCH;
    }

    /**
     * Called when the profile update of the logged in user has been completed successfully.
     */
    override fun userUpdatedHisProfile(jid: String) {
        super.userUpdatedHisProfile(jid)
        viewModel.profileUpdatedLiveData.value = jid
    }

    /**
     * Called when the user blocked me
     */
    override fun userBlockedMe(jid: String) {
        super.userBlockedMe(jid)
        viewModel.profileUpdatedLiveData.value = jid
    }

    /**
     * Called when the user unblocked me
     */
    override fun userUnBlockedMe(jid: String) {
        super.userUnBlockedMe(jid)
        viewModel.profileUpdatedLiveData.value = jid
    }

    /**
     * Removes the selected chat from the recent chat history.
     *
     * @param selectedJids the recent chat jid list.
     */
    open fun deleteSelectedRecent(selectedJids: List<String>) {
        ChatManager.deleteRecentChats(selectedJids, object : ChatActionListener {
            override fun onResponse(isSuccess: Boolean, message: String) {
                /*
                * No Implementation needed
                */
            }
        })
        recentChatFragment.updateAdapter()
        viewModel.updateUnReadChatCount()
        actionMode?.finish()
    }

    /**
     * List of Jids
     *
     * @param recentObjects add jids recentsearch
     * @return jids
     */
    open fun getJidFromList(recentObjects: List<Any>): List<String> {
        val jids = ArrayList<String>()
        var i = 0
        val size = recentObjects.size
        while (i < size) {
            when (val recentObject = recentObjects[i]) {
                is RecentChat -> jids.add(recentObject.jid)
                is RecentSearch -> jids.add(recentObject.jid)
                else -> throw ClassCastException("Unsupported object type")
            }
            i++
        }
        return jids
    }

    override fun profileCallback(profile: Profile) {
        super.profileCallback(profile)
        viewModel.profileUpdatedLiveData.value = profile.jid
    }

    /**
     * Set the action menu for the long press menu.
     *
     * @param mode Action mode
     * @param menu Long press menu
     */
    open fun configureActionMode(mode: ActionMode, menu: Menu) {
        val inflater = mode.menuInflater
        inflater.inflate(R.menu.menu_context_mode, menu)
        actionModeMenu = menu

        with(menu) {
            hideMenu(get(R.id.action_reply), get(R.id.action_forward), get(R.id.action_favourite), get(R.id.action_unfavourite),
                    get(R.id.action_copy), get(R.id.action_pin), get(R.id.action_un_pin),
                    get(R.id.action_mute), get(R.id.action_unmute), get(R.id.action_archive_chat))

            menu.get(R.id.action_mute).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_unmute).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_pin).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_un_pin).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_delete).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_info).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.get(R.id.action_archive_chat).action(MenuItem.SHOW_AS_ACTION_ALWAYS)
            get(R.id.action_delete).show()
            get(R.id.action_info).show()
        }
    }

    /**
     * On click action on the menu when the recent chat long pressed it.
     *
     * @param itemId The item id
     * @return boolean True if the item has click handled successfully.
     */
    open fun onClickAction(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_delete -> {
                if (mViewPager.currentItem == 1) {
                    callHistoryFragment.showClearAlertDialog(false)
                } else deleteChatAlert()
                return true
            }
            R.id.action_info -> {
                openChatInfo()
                actionMode?.finish()
                return true
            }
            R.id.action_pin -> {
                return if (viewModel.updatePinnedRecentChats()) {
                    actionMode?.finish()
                    true
                } else {
                    false
                }
            }
            R.id.action_un_pin -> {
                viewModel.updateUnPinnedRecentChats()
                actionMode?.finish()
                return true
            }
            R.id.action_mute -> {
                viewModel.updateMuteNotification(Constants.MUTE_NOTIFY)
                recentChatFragment.updateRecentItem()
                actionMode?.finish()
                return true
            }
            R.id.action_unmute -> {
                viewModel.updateMuteNotification(Constants.UN_MUTE_NOTIFY)
                recentChatFragment.updateRecentItem()
                actionMode?.finish()
                return true
            }
            R.id.action_mark_as_unread -> {
                viewModel.markAsUnreadRecentChats()
                actionMode?.finish()
                return true
            }
            R.id.action_mark_as_read -> {
                netConditionalCall({ viewModel.markAsReadRecentChats() }, { showErrorMessage() })
                actionMode?.finish()
                return true
            }
            R.id.action_archive_chat -> {
                archiveChats()
                viewModel.updateUnReadChatCount()
                actionMode?.finish()
                return true
            }
            R.id.action_add_chat_shortcuts -> {
                viewModel.createPinShortcutForRecentChat(this)
                actionMode?.finish()
                return true
            }
            else -> return false
        }
    }

    /**
     * Alert dialog while the user wants to delete chats
     */
    private fun deleteChatAlert() {
        var deleteAlertMessage = getString(R.string.msg_are_you_sure_you_want_to_clear)

        // Delete the recent chat list
        if (mRecentChatListType == RecentChatListType.RECENT) {
            // Delete the one chat
            deleteAlertMessage = if (viewModel.selectedRecentChats.size == 1) {
                val userNickName = ContactManager.getDisplayName(viewModel.selectedRecentChats[0].jid)
                String.format(getString(R.string.msg_delete_chat_single_conversation), userNickName)
            } else {
                // Delete the multiple chats
                String.format(getString(R.string.msg_delete_chat_multiple_conversation), viewModel.selectedRecentChats.size)
            }
        }

        commonAlertDialog.showAlertDialog(deleteAlertMessage, getString(R.string.action_yes), com.contus.flycommons.getString(R.string.action_no),
                CommonAlertDialog.DIALOGTYPE.DIALOG_DUAL, false)
    }

    /**
     * Open user profile view from the recent chats.
     */
    private fun openChatInfo() {
        var jid = Constants.EMPTY_STRING
        var chatType = Constants.EMPTY_STRING

        // Check if the user selecting from recent or searched list
        if (mRecentChatListType == RecentChatListType.RECENT && Utils.isListExist<RecentChat>(viewModel.selectedRecentChats)) {
            jid = viewModel.selectedRecentChats[0].jid
            chatType = viewModel.selectedRecentChats[0].getChatType()
        }
        if (SharedPreferenceManager.getString(Constants.ADMIN_USER) != Utils.getTrimmedPhoneNumber(jid))
            startInfoActivity(jid, chatType)
    }

    /**
     * Redirect to user show user info activity if single chat else redirect to group info activity
     *
     * @param jid      Jabber id
     * @param chatType Type of the chat
     */
    private fun startInfoActivity(jid: String, chatType: String) {
        if (ChatType.TYPE_CHAT == chatType) {
            launchActivity<UserInfoActivity> {
                putExtra(AppConstants.PROFILE_DATA, ContactManager.getProfileDetails(jid))
            }
        } else {
            launchActivity<GroupInfoActivity> {
                putExtra(AppConstants.PROFILE_DATA, ContactManager.getProfileDetails(jid))
            }
        }
    }

    private fun showErrorMessage() {
        showToast(if (AppUtils.isNetConnected(this))
            getString(R.string.api_call_error) else getString(R.string.msg_no_internet))
    }

    @Synchronized
    private fun archiveChats() {
        if (!AppUtils.isNetConnected(this)) {
            CustomToast.show(this, getString(R.string.error_check_internet))
            return
        }
        val selectedJids: MutableList<String> = mutableListOf()
        var failedCount = 0
        val selectedCount = viewModel.selectedRecentChats.size
        for (recent in viewModel.selectedRecentChats) {
            FlyCore.updateArchiveUnArchiveChat(recent.jid, true, FlyCallback { isSuccess, _, _ ->
                if (isSuccess) selectedJids.add(recent.jid)
                else failedCount++
                val isAdapterNeedSync = (selectedJids.size > 0 && selectedCount == (selectedJids.size + failedCount))
                if (isAdapterNeedSync) updateArchiveChatsData(selectedJids, failedCount)
            })
        }
    }

    private fun updateArchiveChatsData(selectedJids: MutableList<String>, failedCount: Int) {
        recentChatFragment.updateArchiveChatsList(selectedJids)
        val chatsSize = selectedJids.size
        if (chatsSize == 1)
            CustomToast.showShortToast(context, String.format(context!!.getString(R.string.msg_chat_archived), chatsSize))
        else if (chatsSize > 0)
            CustomToast.showShortToast(context, String.format(context!!.getString(R.string.msg_multiple_chats_archived), chatsSize))
        LogMessage.e(TAG, "Archive Chats failed: $failedCount")
    }

    override fun updateArchiveUnArchiveChats(toUser: String?, archiveStatus: Boolean) {
        super.updateArchiveUnArchiveChats(toUser, archiveStatus)
        toUser?.let {
            recentChatFragment.updateArchiveChatsStatus(toUser, archiveStatus)
            viewModel.getArchivedChatStatus()
            viewModel.unreadChatCountLiveData.value = FlyMessenger.getUnreadMessagesCount()
        }
    }

    override fun updateArchivedSettings(archivedSettingsStatus: Boolean) {
        super.updateArchivedSettings(archivedSettingsStatus)
        viewModel.getArchivedChatStatus()
    }

    /**
     * This method checks whether the web chat is logged in or not and redirects accordingly...
     */
    open fun webConnect() {
        checkLogin()
        if (!SharedPreferenceManager.getBoolean(Constants.IS_WEBCHAT_LOGGED_IN)) {
            if (MediaPermissions.isPermissionAllowed(this, Manifest.permission.CAMERA))
                startActivity(Intent(this, QrCodeScannerActivity::class.java))
            else
                MediaPermissions.requestCameraPermission(this, RequestCode.CAMERA_PERMISSION_CODE)
        } else
            startActivity(Intent(this, QrResultActivity::class.java))
    }

    /**
     * This method is check weather the browser already logged in
     * if it's already stored in the database set the boolean is true
     */
    fun checkLogin() {
        if (WebLoginDataManager.getWebLoginDetails().isNotEmpty())
            SharedPreferenceManager.setBoolean(Constants.IS_WEBCHAT_LOGGED_IN, true)
    }
}