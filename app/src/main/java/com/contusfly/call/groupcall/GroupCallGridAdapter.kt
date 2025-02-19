package com.contusfly.call.groupcall

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.contus.call.CallActions
import com.contus.call.CallConstants
import com.contus.call.CallConstants.CALL_UI
import com.contus.call.SpeakingIndicatorListener
import com.contus.flycommons.LogMessage
import com.contus.webrtc.CallStatus
import com.contus.webrtc.api.CallManager
import com.contus.call.utils.GroupCallUtils
import com.contusfly.R
import com.contusfly.adapters.BaseViewHolder
import com.contusfly.call.SetDrawable
import com.contusfly.call.groupcall.utils.CallUtils
import com.contusfly.databinding.CallGridUserItemBinding
import com.contusfly.gone
import com.contusfly.show
import com.contusfly.utils.Constants
import com.contusfly.utils.SharedPreferenceManager
import com.contusflysdk.api.contacts.ContactManager
import com.contusflysdk.utils.ChatUtils
import com.contusflysdk.utils.MediaUtils
import com.contusflysdk.utils.Utils
import com.jakewharton.rxbinding3.view.clicks
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class GroupCallGridAdapter(val context: Context) : RecyclerView.Adapter<GroupCallGridAdapter.CallUserGridViewHolder>() {

    /**
     * The call user list to display in the recycler view.
     */
    var gridCallUserList: MutableList<String> = mutableListOf()

    private var actualScreenHeight = 0
    private var actualScreenWidth = 0

    /**
     * Surface views map
     */
    private var callUsersGridSurfaceViews = ConcurrentHashMap<String, SurfaceViewRenderer>(8)

    /**
     * contains the surface view initialisation status
     */
    private var surfaceViewGridStatusMap = ConcurrentHashMap<SurfaceViewRenderer, Boolean>(8)

    fun onUserPinnedGridView(fn:(String) -> Unit) {
        onPinnedGridView = fn
    }

    fun onTapOnRecyclerView(fn:() -> Unit){
        onTapOnRecyclerView = fn
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallUserGridViewHolder {
        val binding = CallGridUserItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CallUserGridViewHolder(binding)
    }

    @SuppressLint("CheckResult")
    override fun onBindViewHolder(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI onBindViewHolder position: $position userJid:${gridCallUserList[position]}")
        if (!(surfaceViewGridStatusMap[holder.binding.viewVideoSurface] != null && surfaceViewGridStatusMap[holder.binding.viewVideoSurface] == true)) {
            LogMessage.i(TAG, "$CALL_UI #surface initializing surface view: ${holder.binding.viewVideoSurface}")
            holder.binding.viewVideoSurface.init(CallManager.getRootEglBase()?.eglBaseContext, null)
            holder.binding.viewVideoSurface.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            surfaceViewGridStatusMap[holder.binding.viewVideoSurface] = true
        }

        setUserInfo(holder, position)
        setSurfaceViewToVideoSink(holder, position)
        setMirrorView(holder, position)
        setUpVideoMuted(holder, position)
        setUpAudioMuted(holder, position)
        updateViewSize(holder, position)
        updateGridConnectionStatus(holder, position)
        updateGridPinnedPosition(holder, position)
        updateUserSpeaking(holder, position, CallUtils.getUserSpeakingLevel(gridCallUserList[position]))

        holder.binding.rootLayout.clicks().throttleFirst(500, TimeUnit.MILLISECONDS).subscribe {
            onTapOnRecyclerView()
        }
    }

    @SuppressLint("CheckResult")
    private fun updateGridPinnedPosition(holder: CallUserGridViewHolder, position: Int) {
        val userJid = gridCallUserList[position]
        holder.binding.imageGridPinned.show()
        setPinnedUserView(holder.binding.imageGridPinned, userJid)
        holder.binding.imageGridPinned.clicks().throttleFirst(1000, TimeUnit.MILLISECONDS).subscribe {
            onPinnedGridView(userJid)
            setPinnedUserView(holder.binding.imageGridPinned, userJid)
        }
    }

    private fun setPinnedUserView(imageGridPinned: ImageView, userJid: String) {
        if (CallUtils.getIsUserTilePinned() && userJid == CallUtils.getPinnedUserJid())
            imageGridPinned.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_grid_pinned_icon))
        else imageGridPinned.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_pin_tile))
    }

    private fun setMirrorView(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI setMirrorView position: $position userJid:${gridCallUserList[position]}")
        if (gridCallUserList[position] == GroupCallUtils.getLocalUserJid()) {
            holder.binding.viewVideoSurface.setMirror(!CallUtils.getIsBackCameraCapturing())
        } else {
            holder.binding.viewVideoSurface.setMirror(false)
        }
    }

    private fun setUpAudioMuted(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI #surface setUpAudioMuted position: $position userJid:${gridCallUserList[position]}")
        if (((gridCallUserList[position] == GroupCallUtils.getLocalUserJid() && GroupCallUtils.isAudioMuted()) || CallManager.isRemoteAudioMuted(gridCallUserList[position]))) {
            holder.binding.imageAudioMuted.show()
        } else {
            holder.binding.imageAudioMuted.gone()
        }
    }

    private fun setUpVideoMuted(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI setUpVideoMuted position: $position userJid:${gridCallUserList[position]}")
        when (gridCallUserList[position]) {
            GroupCallUtils.getLocalUserJid() -> if (GroupCallUtils.isVideoMuted()) {
                LogMessage.d(TAG, "$CALL_UI setUpVideoMuted userJid:${gridCallUserList[position]} is muted: true")
                hideSurface(holder, gridCallUserList[position])
                setUserInfo(holder, position)
            } else showSurface(holder, gridCallUserList[position])
            else -> if (CallManager.isRemoteVideoMuted(gridCallUserList[position])
                || CallManager.isRemoteVideoPaused(gridCallUserList[position])
                || CallManager.getRemoteProxyVideoSink(gridCallUserList[position]).isNull()) {
                LogMessage.d(TAG, "$CALL_UI setUpVideoMuted userJid:${gridCallUserList[position]} is muted: ${CallManager.isRemoteVideoMuted(gridCallUserList[position])}" +
                        "is video paused: ${CallManager.isRemoteVideoPaused(gridCallUserList[position])}" +
                        "is remote proxy null: ${CallManager.getRemoteProxyVideoSink(gridCallUserList[position]).isNull()}")
                hideSurface(holder, gridCallUserList[position])
                setUserInfo(holder, position)
            } else {
                showSurface(holder, gridCallUserList[position])
            }
        }
    }

    private fun setUserInfo(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI setUserInfo position: $position userJid:${gridCallUserList[position]}")
        holder.binding.textUserName.show()
        if (gridCallUserList[position] == GroupCallUtils.getLocalUserJid()) {
            setLocalUserInfo(holder, position)
        } else {
            setRemoteUserInfo(holder, position)
        }
    }

    private fun setLocalUserInfo(holder: CallUserGridViewHolder, position: Int) {
        holder.binding.textUserName.text = Constants.YOU
        val image = SharedPreferenceManager.getString(Constants.USER_PROFILE_IMAGE)
        val profileDetails = ContactManager.getProfileDetails(gridCallUserList[position])
        val userName = Utils.returnEmptyStringIfNull(SharedPreferenceManager.getString(Constants.USER_PROFILE_NAME))

        val setDrawable = SetDrawable(context, profileDetails)
        val icon = setDrawable.setDrawableForGroupCall(userName, CallConstants.DRAWABLE_SIZE, true)
        MediaUtils.loadImageWithGlideSecure(context, image, holder.binding.imgProfileImage, icon)
    }

    private fun setRemoteUserInfo(holder: CallUserGridViewHolder, position: Int) {
        val profileDetails = ContactManager.getProfileDetails(gridCallUserList[position])
        if (profileDetails != null) {
            val name =  Utils.returnEmptyStringIfNull(profileDetails.name)
            val image = profileDetails.image
            val setDrawable = SetDrawable(context, profileDetails)
            holder.binding.textUserName.text = name.toString()
            holder.binding.imgProfileImage.scaleType = ImageView.ScaleType.CENTER_CROP
            val icon: Drawable? = setDrawable.setDrawableForGroupCall(name.toString(),
                CallConstants.DRAWABLE_SIZE, false)
            MediaUtils.loadImageWithGlideSecure(context, image, holder.binding.imgProfileImage, icon)

        } else {
            holder.binding.textUserName.text = Utils.getFormattedPhoneNumber(ChatUtils.getUserFromJid(gridCallUserList[position]))
            holder.binding.imgProfileImage.scaleType = ImageView.ScaleType.CENTER_CROP
            MediaUtils.loadImageWithGlideSecure(context, "", holder.binding.imgProfileImage, ContextCompat.getDrawable(context, R.drawable.ic_group_call_user_default_pic))
        }
    }

    private fun setSurfaceViewToVideoSink(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI setSurfaceViewToVideoSink position: $position userJid:${gridCallUserList[position]}")
        when {
            gridCallUserList[position] == GroupCallUtils.getLocalUserJid() -> {
                try {
                    CallManager.getLocalProxyVideoSink()?.setTarget(holder.binding.viewVideoSurface)
                } catch (e: Exception) {
                    LogMessage.e(TAG, "$CALL_UI $e")
                }
            }
            CallManager.getRemoteProxyVideoSink(gridCallUserList[position]) != null -> {
                LogMessage.d(TAG, "$CALL_UI #surface setSurfaceViewToVideoSink update remote user view for ${gridCallUserList[position]}")
                CallManager.getRemoteProxyVideoSink(gridCallUserList[position])!!
                    .setTarget(holder.binding.viewVideoSurface)
            }
        }
    }

    private fun swapSurfaceViewToVideoSink(holder: CallUserGridViewHolder) {
        LogMessage.d(TAG, "$CALL_UI swapSurfaceViewToVideoSink")
        CallManager.getLocalProxyVideoSink()!!.setTarget(holder.binding.viewVideoSurface)
    }

    private fun unSwapSurfaceViewToVideoSink(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI unSwapSurfaceViewToVideoSink position: $position userJid:${gridCallUserList[position]}")
        if (CallManager.getRemoteProxyVideoSink(gridCallUserList[position]) != null)
            CallManager.getRemoteProxyVideoSink(gridCallUserList[position])?.setTarget(holder.binding.viewVideoSurface)
        else
            hideSurface(holder, gridCallUserList[position])
    }

    override fun onBindViewHolder(holder: CallUserGridViewHolder, position: Int, payloads: MutableList<Any>) {
        LogMessage.d(TAG, "$CALL_UI onBindViewHolder position: $position userJid:${gridCallUserList[position]}")
        callUsersGridSurfaceViews[gridCallUserList[position]] = holder.binding.viewVideoSurface
        LogMessage.d(TAG, "$CALL_UI put surface view for : ${gridCallUserList[position]}")
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            LogMessage.d(TAG, "$CALL_UI onBindViewHolder no payload")

        } else {
            val bundle = payloads[0] as Bundle
            LogMessage.d(TAG, "$CALL_UI onBindViewHolder has payload")
            handlePayload(bundle, holder, position)
        }
    }

    private fun handlePayload(bundle: Bundle, holder: CallUserGridViewHolder, position: Int) {
        for (key in bundle.keySet()) {
            LogMessage.d(TAG, "$CALL_UI onBindViewHolder key: $key")
            when (key) {
                CallActions.NOTIFY_REMOTE_VIEW_HIDE -> {
                    hideSurface(holder, gridCallUserList[position])
                }
                CallActions.NOTIFY_REMOTE_VIEW_SHOW -> {
                    showSurface(holder, gridCallUserList[position])
                }
                CallActions.NOTIFY_REMOTE_VIEW_RELEASE -> {
                    LogMessage.i(TAG, "$CALL_UI #surface release view: ${holder.binding.viewVideoSurface} #1")
                    holder.binding.viewVideoSurface.release()
                    holder.binding.viewVideoSurface.clearImage()
                    surfaceViewGridStatusMap[holder.binding.viewVideoSurface] = false
                    surfaceViewGridStatusMap.remove(holder.binding.viewVideoSurface)
                    callUsersGridSurfaceViews.remove(gridCallUserList[position])
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
                    updateGridConnectionStatus(holder, position)
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
                    updateGridPinnedPosition(holder, position)
                    updateUserSpeaking(holder, position, CallUtils.getUserSpeakingLevel(gridCallUserList[position]))
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

    private fun hideSurface(holder: CallUserGridViewHolder, userJid: String) {
        LogMessage.d(TAG, "$CALL_UI hideSurface() userJid: $userJid")
        holder.binding.viewVideoSurface.gone()
        holder.binding.imgProfileImage.show()
        holder.binding.callerNameBgLayout.gone()
    }

    private fun showSurface(holder: CallUserGridViewHolder, userJid: String) {
        LogMessage.d(TAG, "$CALL_UI showSurface() userJid: $userJid")
        holder.binding.viewVideoSurface.show()
        holder.binding.callerNameBgLayout.show()
        holder.binding.imgProfileImage.gone()
    }

    private fun updateViewSize(holder: CallUserGridViewHolder, position: Int) {
        LogMessage.d(TAG, "$CALL_UI updateViewSize position: $position userJid:${gridCallUserList[position]}")
        if (gridCallUserList[position] == GroupCallUtils.getLocalUserJid()) {
            setUserInfo(holder, position)
        }
        val gridLayoutParams = holder.binding.rootLayout.layoutParams as GridLayoutManager.LayoutParams
        gridLayoutParams.height = viewHeight
        gridLayoutParams.width = GridLayoutManager.LayoutParams.MATCH_PARENT
        holder.binding.rootLayout.layoutParams = gridLayoutParams
    }

    private val viewHeight: Int
        get() = when {
            gridCallUserList.size < 2 -> GridLayoutManager.LayoutParams.MATCH_PARENT
            gridCallUserList.size < 3 -> (actualScreenHeight * 0.4).roundToInt()
            else -> actualScreenWidth / 2
        }

    fun addUser(userJid: String) {
        if (userJid.isBlank())
            return
        LogMessage.d(TAG, "$CALL_UI addUser() userJid:${userJid}")
        if (gridCallUserList.size == 0) {
            gridCallUserList.add(userJid)
            notifyItemInserted(gridCallUserList.indexOf(userJid))
        } else if (!gridCallUserList.contains(userJid)) {
            if (userJid == GroupCallUtils.getLocalUserJid() || !gridCallUserList.contains(
                    GroupCallUtils.getLocalUserJid())) {
                gridCallUserList.add(userJid)
                notifyItemInserted(gridCallUserList.indexOf(userJid))
            } else {
                gridCallUserList.add(gridCallUserList.size - 1, userJid)
                notifyItemInserted(gridCallUserList.indexOf(userJid))
            }
        } else {
            updateGridConnectionStatus(gridCallUserList.indexOf(userJid))
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun addUsers(userList: ArrayList<String>) {
        LogMessage.d(TAG, "$CALL_UI addUsers() userJid:${userList}")
        if (gridCallUserList.size == 0) {
            gridCallUserList.addAll(userList)
            notifyDataSetChanged()
        } else {
            gridCallUserList.addAll(0, userList)
            notifyDataSetChanged()
        }
    }

    private fun updateGridConnectionStatus(index: Int) {
        LogMessage.d(TAG, "$CALL_UI updateConnectionStatus() position: $index userJid:${gridCallUserList[index]} callStatus:${GroupCallUtils.getCallStatus(gridCallUserList[index])}")
        val bundle = Bundle()
        bundle.putInt(CallActions.NOTIFY_VIEW_STATUS_UPDATED, 1)
        notifyItemChanged(index, bundle)
    }

    private fun updateGridConnectionStatus(holder: CallUserGridViewHolder, index: Int) {
        LogMessage.d(TAG, "$CALL_UI updateConnectionStatus position: $index userJid:${gridCallUserList[index]} callStatus:${GroupCallUtils.getCallStatus(gridCallUserList[index])}")
        when (GroupCallUtils.getCallStatus(gridCallUserList[index])) {
            CallStatus.RINGING, CallStatus.CONNECTING, CallStatus.CALLING, CallStatus.DISCONNECTED -> {
                if (gridCallUserList[index] != GroupCallUtils.getLocalUserJid()) {
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

    private fun showStatusInView(holder: CallUserGridViewHolder, index: Int) {
        LogMessage.d(TAG, "$CALL_UI showStatusInView position: $index userJid:${gridCallUserList[index]}")
        setUpVideoMuted(holder, index)
        val callerStatus = GroupCallUtils.getCallStatus(gridCallUserList[index])
        holder.binding.callerStatusLayout.show()
        holder.binding.callerStatusTextView.text = callerStatus
        holder.binding.callerNameBgLayout.gone()
        if ((CallStatus.CONNECTING == callerStatus || CallStatus.CALLING == callerStatus)
            && GroupCallUtils.isOnGoingVideoCall()) {
            holder.binding.imgProfileImage.show()
        }
    }

    fun removeUser(userJid: String) {
        LogMessage.d(TAG, "$CALL_UI removeUser() userJid:${userJid}")
        val index = gridCallUserList.indexOf(userJid)
        if (index >= 0) {
            //notify item changed will not work here, since we are immediately removing view
            val surfaceViewRenderer = callUsersGridSurfaceViews.remove(userJid)
            if (surfaceViewRenderer != null) {
                surfaceViewRenderer.release()
                surfaceViewRenderer.clearImage()
                surfaceViewGridStatusMap[surfaceViewRenderer] = false
                surfaceViewGridStatusMap.remove(surfaceViewRenderer)
                LogMessage.i(TAG, "$CALL_UI #surface release view: $surfaceViewRenderer #2")
            }
            gridCallUserList.remove(userJid)
            notifyItemRemoved(index)
        }
    }

    fun hideUserSurfaceView() {
        for (userJid in gridCallUserList) {
            LogMessage.d(TAG, "$CALL_UI hideUser() userJid:${userJid}")
            val surfaceViewRenderer = callUsersGridSurfaceViews[userJid]
            surfaceViewRenderer?.gone()
        }
    }

    private fun updateUserSpeaking(holder: CallUserGridViewHolder, position: Int, audioLevel: Int) {
        if (audioLevel == 0 || GroupCallUtils.isUserAudioMuted(gridCallUserList[position]))
            updateUserStoppedSpeaking(holder, position)
        else {
            holder.binding.imageAudioMuted.gone()
            holder.binding.viewSpeakingIndicator.onUserSpeaking(audioLevel)
        }
    }

    private fun updateUserStoppedSpeaking(holder: CallUserGridViewHolder, position: Int){
        holder.binding.viewSpeakingIndicator.onUserStoppedSpeaking(object : SpeakingIndicatorListener {
            override fun onSpeakingIndicatorHidden() {
                if (position < gridCallUserList.size)
                    CallUtils.clearPeakSpeakingUser(gridCallUserList[position])
            }
        })
        setUpAudioMuted(holder, position)
    }

    fun setScreenHeight(actualScreenHeight: Int) {
        this.actualScreenHeight = actualScreenHeight
    }

    fun setScreenWidth(actualScreenWidth: Int) {
        this.actualScreenWidth = actualScreenWidth
    }

    override fun getItemCount(): Int {
        return gridCallUserList.size
    }

    inner class CallUserGridViewHolder(val binding: CallGridUserItemBinding) : BaseViewHolder(binding.root)

    companion object {
        private val TAG = GroupCallGridAdapter::class.java.simpleName

        lateinit var onPinnedGridView: (String) -> Unit

        lateinit var onTapOnRecyclerView: () -> Unit
    }

}