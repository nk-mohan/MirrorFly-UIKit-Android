package com.contusfly.call.groupcall

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.contus.call.CallActions
import com.contus.call.CallConstants
import com.contus.call.CallConstants.CALL_UI
import com.contus.call.SpeakingIndicatorListener
import com.contus.flycommons.LogMessage
import com.contus.webrtc.CallStatus
import com.contus.webrtc.api.CallManager
import com.contus.call.utils.GroupCallUtils
import com.contusfly.*
import com.contusfly.adapters.BaseViewHolder
import com.contusfly.call.SetDrawable
import com.contusfly.call.groupcall.utils.CallUtils
import com.contusfly.databinding.CallUserItemBinding
import com.contusfly.utils.Constants
import com.contusfly.utils.SharedPreferenceManager
import com.contusflysdk.api.contacts.ContactManager
import com.contusflysdk.utils.ChatUtils
import com.contusflysdk.utils.MediaUtils
import com.contusflysdk.utils.Utils
import com.jakewharton.rxbinding3.view.clicks
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GroupCallListAdapter(val context: Context) : RecyclerView.Adapter<GroupCallListAdapter.CallUserViewHolder>() {

    /**
     * The call user list to display in the recycler view.
     */
    var callUserList: MutableList<String> = mutableListOf()
    private var actualScreenHeight = 0
    private var actualScreenWidth = 0

    /**
     * Surface views map
     */
    private var callUsersSurfaceViews = ConcurrentHashMap<String, SurfaceViewRenderer>(8)

    /**
     * contains the surface view initialisation status
     */
    private var surfaceViewStatusMap = ConcurrentHashMap<SurfaceViewRenderer, Boolean>(8)

    fun onPinnedListView(pinnedListView : (userJid:String) -> Unit) {
        onPinnedListView = pinnedListView
    }

    fun onTapOnRecyclerView(fn:() -> Unit){
        onTapOnRecyclerView = fn
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallUserViewHolder {
        return CallUserViewHolder(CallUserItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int {
        return callUserList.size
    }

    override fun getItemViewType(position: Int): Int {
        return position
    }

    override fun onBindViewHolder(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI onBindViewHolder position: $position userJid:${callUserList[position]}")
        if (!(surfaceViewStatusMap[holder.binding.viewVideoSurface] != null && surfaceViewStatusMap[holder.binding.viewVideoSurface] == true)) {
            LogMessage.i(TAG, "$CALL_UI #surface initializing surface view: ${holder.binding.viewVideoSurface}")
            holder.binding.viewVideoSurface.init(CallManager.getRootEglBase()?.eglBaseContext, null)
            holder.binding.viewVideoSurface.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            holder.binding.viewVideoSurface.setZOrderMediaOverlay(true)
            surfaceViewStatusMap[holder.binding.viewVideoSurface] = true
        }

        setUserInfo(holder, position)
        setSurfaceViewToVideoSink(holder, position)
        setMirrorView(holder, position)
        setUpVideoMuted(holder, position)
        setUpAudioMuted(holder, position)
        updateViewSize(holder, position)
        updateConnectionStatus(holder, position)
        updateListPinnedPosition(holder, position)
        updateUserSpeaking(holder, position, CallUtils.getUserSpeakingLevel(callUserList[position]))

        holder.binding.rootLayout.clicks().throttleFirst(500, TimeUnit.MILLISECONDS).subscribe {
            onTapOnRecyclerView()
        }
    }

    private fun updateListPinnedPosition(holder: CallUserViewHolder, position: Int) {
        val userJid = callUserList[position]
        holder.binding.imageListPinned.clicks().throttleFirst(500, TimeUnit.MILLISECONDS).subscribe {
            onPinnedListView(userJid)
        }
    }

    private fun setMirrorView(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  setMirrorView position: $position userJid:${callUserList[position]}")
        if (callUserList[position] == GroupCallUtils.getLocalUserJid()) {
            holder.binding.viewVideoSurface.setMirror(!CallUtils.getIsBackCameraCapturing())
        } else {
            holder.binding.viewVideoSurface.setMirror(false)
        }
    }

    private fun setUpAudioMuted(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI #surface setUpAudioMuted position: $position userJid:${callUserList[position]}")
        if ((callUserList[position] == GroupCallUtils.getLocalUserJid() && GroupCallUtils.isAudioMuted()) || CallManager.isRemoteAudioMuted(callUserList[position])) {
            holder.binding.imageAudioMuted.show()
        } else {
            holder.binding.imageAudioMuted.gone()
        }
    }

    private fun setUpVideoMuted(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  setUpVideoMuted position: $position userJid:${callUserList[position]}")
        when (callUserList[position]) {
            GroupCallUtils.getLocalUserJid() -> if (GroupCallUtils.isVideoMuted()) {
                setUserInfo(holder, position)
                hideSurface(holder)
            } else showSurface(holder)
            else -> if (CallManager.isRemoteVideoMuted(callUserList[position])
                || CallManager.isRemoteVideoPaused(callUserList[position])
                || CallManager.getRemoteProxyVideoSink(callUserList[position]).isNull()) {
                setUserInfo(holder, position)
                hideSurface(holder)
            } else {
                showSurface(holder)
            }
        }
    }

    private fun setUserInfo(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  setUserInfo position: $position userJid:${callUserList[position]}")
        holder.binding.textUserName.show()
        if (callUserList[position] == GroupCallUtils.getLocalUserJid()) {
            setLocalUserInfo(holder, position)
        } else {
            setRemoteUserInfo(holder, position)
        }
    }

    private fun setLocalUserInfo(holder: CallUserViewHolder, position: Int) {
        holder.binding.textUserName.text = Constants.YOU
        val image = SharedPreferenceManager.getString(Constants.USER_PROFILE_IMAGE)
        val profileDetails = ContactManager.getProfileDetails(callUserList[position])
        val userName = Utils.returnEmptyStringIfNull(SharedPreferenceManager.getString(Constants.USER_PROFILE_NAME))

        val setDrawable = SetDrawable(context, profileDetails)
        val icon = setDrawable.setDrawableForGroupCall(userName, CallConstants.DRAWABLE_SIZE, true)
        MediaUtils.loadImageWithGlideSecure(context, image, holder.binding.imgProfileImage, icon)
    }

    private fun setRemoteUserInfo(holder: CallUserViewHolder, position: Int) {
        val profileDetails = ContactManager.getProfileDetails(callUserList[position])
        if (profileDetails != null) {
            val name = Utils.returnEmptyStringIfNull(profileDetails.name)
            val image = profileDetails.image
            val setDrawable = SetDrawable(context, profileDetails)
            holder.binding.textUserName.text = name.toString()
            holder.binding.imgProfileImage.scaleType = ImageView.ScaleType.CENTER_CROP
            val icon: Drawable? = setDrawable.setDrawableForGroupCall(name.toString(), CallConstants.DRAWABLE_SIZE, false)
            MediaUtils.loadImageWithGlideSecure(context, image, holder.binding.imgProfileImage, icon)

        } else {
            holder.binding.textUserName.text = Utils.getFormattedPhoneNumber(ChatUtils.getUserFromJid(callUserList[position]))
            holder.binding.imgProfileImage.scaleType = ImageView.ScaleType.CENTER_CROP
            MediaUtils.loadImageWithGlideSecure(context, "", holder.binding.imgProfileImage, ContextCompat.getDrawable(context, R.drawable.ic_group_call_user_default_pic))
        }
    }

    private fun setSurfaceViewToVideoSink(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  setSurfaceViewToVideoSink position: $position userJid:${callUserList[position]}")
        LogMessage.d(TAG, "$CALL_UI setSurfaceViewToVideoSink available surfaces: ${callUsersSurfaceViews.size} unique surfaces:${callUsersSurfaceViews.values.distinct().count()}")

        when {
            callUserList[position] == GroupCallUtils.getLocalUserJid() -> {
                try {
                    CallManager.getLocalProxyVideoSink()?.setTarget(holder.binding.viewVideoSurface)
                } catch (e: Exception) {
                    LogMessage.e(TAG, "$CALL_UI $e")
                }
            }
            CallManager.getRemoteProxyVideoSink(callUserList[position]) != null -> {
                LogMessage.d(TAG, "$CALL_UI #surface setSurfaceViewToVideoSink update remote user view for ${callUserList[position]}")
                CallManager.getRemoteProxyVideoSink(callUserList[position])?.setTarget(holder.binding.viewVideoSurface)
            }
        }
    }

    private fun swapSurfaceViewToVideoSink(holder: CallUserViewHolder) {
        LogMessage.d(TAG, "$CALL_UI  swapSurfaceViewToVideoSink")
        CallManager.getLocalProxyVideoSink()?.setTarget(holder.binding.viewVideoSurface)
    }

    private fun unSwapSurfaceViewToVideoSink(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  unSwapSurfaceViewToVideoSink position: $position userJid:${callUserList[position]}")
        if (CallManager.getRemoteProxyVideoSink(callUserList[position]) != null) {
            CallManager.getRemoteProxyVideoSink(callUserList[position])?.setTarget(holder.binding.viewVideoSurface)
        } else
            hideSurface(holder)
    }

    override fun onBindViewHolder(holder: CallUserViewHolder, position: Int, payloads: MutableList<Any>) {
        LogMessage.d(TAG, "$CALL_UI  onBindViewHolder position: $position userJid:${callUserList[position]}")
        callUsersSurfaceViews[callUserList[position]] = holder.binding.viewVideoSurface
        LogMessage.d(TAG, "$CALL_UI put surface view for : ${callUserList[position]}")
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            LogMessage.d(TAG, "$CALL_UI  onBindViewHolder no payload")

        } else {
            val bundle = payloads[0] as Bundle
            LogMessage.d(TAG, "$CALL_UI  onBindViewHolder has payload")
            for (key in bundle.keySet()) {
                LogMessage.d(TAG, "$CALL_UI  onBindViewHolder key: $key")
                when (key) {
                    CallActions.NOTIFY_REMOTE_VIEW_HIDE -> {
                        hideSurface(holder)
                    }
                    CallActions.NOTIFY_REMOTE_VIEW_SHOW -> {
                        showSurface(holder)
                    }
                    CallActions.NOTIFY_REMOTE_VIEW_RELEASE -> {
                        LogMessage.i(TAG, "$CALL_UI #surface release view: ${holder.binding.viewVideoSurface} #1")
                        holder.binding.viewVideoSurface.release()
                        holder.binding.viewVideoSurface.clearImage()
                        surfaceViewStatusMap[holder.binding.viewVideoSurface] = false
                        surfaceViewStatusMap.remove(holder.binding.viewVideoSurface)
                        callUsersSurfaceViews.remove(callUserList[position])
                    }
                    CallActions.NOTIFY_LOCAL_VIEW_MIRROR -> {
                        holder.binding.viewVideoSurface.setMirror(!CallUtils.getIsBackCameraCapturing())
                    }
                    CallActions.NOTIFY_VIEW_SIZE_UPDATED -> {
                        updateViewSize(holder, position)
                    }
                    CallActions.NOTIFY_VIEW_MUTE_UPDATED -> {
                        setUpAudioMuted(holder, position)
                    }
                    CallActions.NOTIFY_VIEW_VIDEO_MUTE_UPDATED -> {
                        setUpVideoMuted(holder, position)
                    }
                    CallActions.NOTIFY_VIEW_STATUS_UPDATED -> {
                        updateConnectionStatus(holder, position)
                    }
                    CallActions.NOTIFY_CONNECT_TO_SINK -> {
                        setSurfaceViewToVideoSink(holder, position)
                        setUpVideoMuted(holder, position)
                    }
                    CallActions.NOTIFY_SWAP_VIDEO_SINK -> {
                        swapSurfaceViewToVideoSink(holder)
                    }
                    CallActions.NOTIFY_UN_SWAP_VIDEO_SINK -> {
                        unSwapSurfaceViewToVideoSink(holder, position)
                    }
                    CallActions.NOTIFY_PINNED_USER_VIEW -> {
                        updateListPinnedPosition(holder, position)
                        updateUserSpeaking(holder, position, CallUtils.getUserSpeakingLevel(callUserList[position]))
                    }
                    CallActions.NOTIFY_USER_SPEAKING -> {
                        updateUserSpeaking(holder, position, bundle.getInt(key, 0))
                    }
                    CallActions.NOTIFY_USER_STOPPED_SPEAKING -> {
                        updateUserStoppedSpeaking(holder, position)
                    }
                    CallActions.NOTIFY_USER_PROFILE -> {
                        setUserInfo(holder, position)
                    }
                }
            }
        }
    }

    private fun hideSurface(holder: CallUserViewHolder) {
        LogMessage.d(TAG, "$CALL_UI  hideSurface()")
        holder.binding.viewVideoSurface.gone()
        holder.binding.callerNameBgLayout.gone()
        holder.binding.imgProfileImage.show()
    }

    private fun showSurface(holder: CallUserViewHolder) {
        LogMessage.d(TAG, "$CALL_UI  showSurface()")
        holder.binding.viewVideoSurface.show()
        holder.binding.callerNameBgLayout.show()
        holder.binding.imgProfileImage.gone()
    }

    private fun updateViewSize(holder: CallUserViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI  updateViewSize position: $position userJid:${callUserList[position]}")
        if (callUserList[position] == GroupCallUtils.getLocalUserJid()) {
            setUserInfo(holder, position)
        }
        val linearLayoutParams = holder.binding.rootLayout.layoutParams as RecyclerView.LayoutParams
        linearLayoutParams.height = viewHeight
        linearLayoutParams.width = viewWidth
        holder.binding.rootLayout.layoutParams = linearLayoutParams
    }

    private val viewHeight get() = actualScreenHeight / 5
    private val viewWidth get() = (actualScreenWidth / 3.5).toInt()

    fun addUser(userJid: String, addIndex:Int = callUserList.size - 1 ) {
        if (userJid.isBlank())
            return
        LogMessage.d(TAG, "$CALL_UI addUser() userJid:${userJid}")
        if (callUserList.size == 0) {
            callUserList.add(userJid)
            notifyItemInserted(callUserList.indexOf(userJid))
        } else if (!callUserList.contains(userJid)) {
            if (userJid == GroupCallUtils.getLocalUserJid() || !callUserList.contains(
                    GroupCallUtils.getLocalUserJid())) {
                callUserList.add(userJid)
                notifyItemInserted(callUserList.indexOf(userJid))
            } else {
                callUserList.add(addIndex, userJid)
                notifyItemInserted(callUserList.indexOf(userJid))
            }
        } else {
            updateConnectionStatus(callUserList.indexOf(userJid))
        }
    }

    fun addUsers(userList: ArrayList<String>) {
        LogMessage.d(TAG, "$CALL_UI  addUsers() userJid:${userList}")
        if (callUserList.size == 0) {
            callUserList.addAll(userList)
            notifyDataSetChanged()
        } else {
            callUserList.addAll(0, userList)
            notifyDataSetChanged()
        }
    }

    fun swapPinnedUser(newPinnedUserJid: String, oldPinnedUserJid: String, replacePosition: Int) {
        LogMessage.d(TAG, "$CALL_UI swapPinnedUser() newPinnedUserJid:${newPinnedUserJid}, oldPinnedUserJid:${oldPinnedUserJid}, replacePosition:${replacePosition}")
        callUserList.remove(newPinnedUserJid)
        if (!callUserList.contains(oldPinnedUserJid)) {
            callUserList.add(if (replacePosition.isValidIndex()) replacePosition else 0, oldPinnedUserJid)
            notifyItemChanged(if (replacePosition.isValidIndex()) replacePosition else 0)
        }else {
            updateConnectionStatus(callUserList.indexOf(oldPinnedUserJid))
        }
    }

    private fun updateConnectionStatus(index: Int) {
        LogMessage.d(TAG, "$CALL_UI  updateConnectionStatus() position: $index userJid:${callUserList[index]} callStatus:${GroupCallUtils.getCallStatus(callUserList[index])}")
        val bundle = Bundle()
        bundle.putInt(CallActions.NOTIFY_VIEW_STATUS_UPDATED, 1)
        notifyItemChanged(index, bundle)
    }

    private fun updateConnectionStatus(holder: CallUserViewHolder, index: Int) {
        LogMessage.d(TAG, "$CALL_UI  updateConnectionStatus position: $index userJid:${callUserList[index]} callStatus:${GroupCallUtils.getCallStatus(callUserList[index])}")
        when (GroupCallUtils.getCallStatus(callUserList[index])) {
            CallStatus.RINGING, CallStatus.CONNECTING, CallStatus.CALLING, CallStatus.DISCONNECTED -> {
                if (callUserList[index] != GroupCallUtils.getLocalUserJid()) {
                    showStatusInView(holder, index)
                } else
                    holder.binding.callerStatusLayout.gone()
            }
            CallStatus.RECONNECTING, CallStatus.ON_HOLD -> {
                showStatusInView(holder, index)
            }
            else -> {
                setUpVideoMuted(holder, index)
                holder.binding.callerStatusLayout.gone()
            }
        }
    }

    private fun showStatusInView(holder: CallUserViewHolder, index: Int) {
        LogMessage.d(TAG, "$CALL_UI  showStatusInView position: $index userJid:${callUserList[index]}")
        setUpVideoMuted(holder, index)
        val callerStatus = GroupCallUtils.getCallStatus(callUserList[index])
        holder.binding.callerStatusLayout.show()
        holder.binding.callerStatusTextView.text = callerStatus
        if ((CallStatus.CONNECTING == callerStatus || CallStatus.CALLING == callerStatus)
            && GroupCallUtils.isOnGoingVideoCall()) {
            holder.binding.imgProfileImage.show()
        }
    }

    fun removeUser(userJid: String) {
        LogMessage.d(TAG, "$CALL_UI  removeUser() userJid:${userJid}")
        val index = callUserList.indexOf(userJid)
        if (index >= 0) {
            //notify item changed will not work here, since we are immediately removing view
            val surfaceViewRenderer = callUsersSurfaceViews.remove(userJid)
            if (surfaceViewRenderer != null) {
                surfaceViewRenderer.release()
                surfaceViewRenderer.clearImage()
                surfaceViewRenderer.gone()
                surfaceViewStatusMap[surfaceViewRenderer] = false
                surfaceViewStatusMap.remove(surfaceViewRenderer)
                LogMessage.i(TAG, "$CALL_UI #surface release view: $surfaceViewRenderer #2")
            }
            callUserList.remove(userJid)
            notifyItemRemoved(index)
        }
    }

    fun hideUserSurfaceView() {
        for (userJid in callUserList) {
            LogMessage.d(TAG, "$CALL_UI hideUser() userJid:${userJid}")
            val surfaceViewRenderer = callUsersSurfaceViews[userJid]
            surfaceViewRenderer?.gone()
        }
    }

    fun setScreenHeight(actualScreenHeight: Int) {
        this.actualScreenHeight = actualScreenHeight
    }

    fun setScreenWidth(actualScreenWidth: Int) {
        this.actualScreenWidth = actualScreenWidth
    }

    private fun updateUserSpeaking(holder: CallUserViewHolder, position: Int, audioLevel: Int) {
        if (audioLevel == 0 || GroupCallUtils.isUserAudioMuted(callUserList[position]))
            updateUserStoppedSpeaking(holder, position)
        else {
            holder.binding.imageAudioMuted.gone()
            holder.binding.viewSpeakingIndicator.onUserSpeaking(audioLevel)
        }
    }

    private fun updateUserStoppedSpeaking(holder: CallUserViewHolder, position: Int){
        holder.binding.viewSpeakingIndicator.onUserStoppedSpeaking(object : SpeakingIndicatorListener {
            override fun onSpeakingIndicatorHidden() {
                if (position < callUserList.size)
                    CallUtils.clearPeakSpeakingUser(callUserList[position])
            }
        })
        setUpAudioMuted(holder, position)
    }

    inner class CallUserViewHolder(val binding: CallUserItemBinding) : BaseViewHolder(binding.root)

    companion object {
        lateinit var onPinnedListView: (userJid:String) -> Unit

        lateinit var onTapOnRecyclerView: () -> Unit
    }
}