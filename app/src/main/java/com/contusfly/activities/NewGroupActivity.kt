package com.contusfly.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.contus.flycommons.*
import com.contus.flycommons.LogMessage
import com.contus.xmpp.chat.models.CreateGroupModel
import com.contusfly.R
import com.contusfly.TAG
import com.contusfly.applySourceColorFilter
import com.contusfly.databinding.ActivityNewGroupBinding
import com.contusfly.showToast
import com.contusfly.utils.*
import com.contusfly.utils.Constants
import com.contusfly.utils.MediaUtils.loadImageWithGlideSecure
import com.contusfly.utils.SharedPreferenceManager
import com.contusfly.views.CommonAlertDialog
import com.contusfly.views.DoProgressDialog
import com.contusflysdk.AppUtils
import com.contusflysdk.api.ChatManager
import com.contusflysdk.api.GroupManager
import com.contusflysdk.api.contacts.ProfileDetails
import com.contusflysdk.api.utils.ImageUtils.takePhotoFromCamera
import com.contusflysdk.api.utils.PickFileUtils
import com.contusflysdk.utils.FilePathUtils
import com.contusflysdk.utils.ImagePopUpUtils
import com.contusflysdk.utils.MediaPaths
import com.contusflysdk.utils.Utils
import io.github.rockerhieu.emojicon.EmojiconGridFragment.OnEmojiconClickedListener
import io.github.rockerhieu.emojicon.EmojiconsFragment
import io.github.rockerhieu.emojicon.EmojiconsFragment.OnEmojiconBackspaceClickedListener
import io.github.rockerhieu.emojicon.emoji.Emojicon
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class NewGroupActivity : AppCompatActivity(), OnEmojiconBackspaceClickedListener, OnEmojiconClickedListener,
    CommonAlertDialog.CommonDialogClosedListener {

    private lateinit var binding: ActivityNewGroupBinding

    private lateinit var toolbar: Toolbar

    private var fileTemp: File? = null

    private var progressDialog: DoProgressDialog? = null

    /**
     * Instance of the EmojiHandler to access the emoji and text keypad
     */
    private lateinit var emojiHandler: EmojiHandler

    private var groupImage = false

    private var encoded: String =""

    /**
     * Temporary camera file for the image view in the group creation.
     */
    private var mFileCameraTemp: File? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: ChatUtils.checkMediaPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: ChatUtils.checkMediaPermission(this, Manifest.permission.CAMERA)

        if(readPermissionGranted && cameraPermissionGranted) {
            takePhotoFromCamera(this, FilePathUtils.getExternalStorage().toString() + "/"
                    + getString(R.string.title_app_name) + "/" + MediaPaths.MEDIA_PATH_PROFILE_PHOTOS, true)
        }
    }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: ChatUtils.checkMediaPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)

        if(readPermissionGranted) {
            PickFileUtils.chooseImageFromGallery(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpToolbar()
        setListeners()
    }


    private fun setUpToolbar() {
        toolbar = binding.toolbarInclude.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.title_new_group)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        UserInterfaceUtils.initializeCustomToolbar(this, toolbar)
        toolbar.navigationIcon?.applySourceColorFilter(ContextCompat.getColor(this, R.color.dashboard_toolbar_text_color))
        toolbar.setNavigationOnClickListener { finish() }
    }


    private fun setListeners() {

        emojiHandler = EmojiHandler(this)
        emojiHandler.setIconImageView(binding.imgSmiley)
        emojiHandler.attachKeyboardListeners(binding.editNewGroupName)
        emojiHandler.setHandledFrom(TAG)

        binding.toolbarInclude.toolbarAction.setOnClickListener {

            if (binding.editNewGroupName.text.toString().trim().isNotEmpty()) {
                if (emojiHandler.isEmojiShowing) emojiHandler.hideEmoji()
                val intent = Intent(this, NewContactsActivity::class.java).apply {
                    putExtra(Constants.ADD_PARTICIAPANTS, true)
                    putExtra(Constants.TITLE, getString(R.string.add_participants))
                }
                startActivityForResult(intent, RequestCode.ADD_PARTICIPANTS)

            } else {
                showToast(getString(R.string.msg_group_name_empty))
            }
        }


        binding.changeProfileImage.setOnClickListener {
            showBottomMenu(it)
        }

        binding.imgGroup.setOnClickListener {
            if (fileTemp == null)
                showBottomMenu(it)
        }

        binding.editNewGroupName.addTextChangedListener(object : TextWatcher {
            var beforeChanged: String? = null

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                beforeChanged = s.toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//               /*Not Needed*/
            }

            override fun afterTextChanged(s: Editable?) {
                val length = 25 - EmojiUtils.getGraphemeLength(binding.editNewGroupName.text.toString())
                binding.txtSize.text = length.toString()
                if (EmojiUtils.getGraphemeLength(binding.editNewGroupName.text.toString()) > 25) {
                    binding.editNewGroupName.setText(beforeChanged)
                    binding.editNewGroupName.setSelection(binding.editNewGroupName.text.toString().length)
                }
            }

        })

        binding.imgSmiley.setOnClickListener {
            emojiHandler.setKeypad(binding.editNewGroupName)
        }
    }

    /**
     * Show bottom menu select image for the group
     *
     * @param view View of an instance
     */
    private fun showBottomMenu(view: View) {
        Utils.hideSoftInput(applicationContext, view)
        val commonAlertDialog = CommonAlertDialog(this)
        commonAlertDialog.setOnDialogCloseListener(this)
        if (!groupImage) {
            commonAlertDialog.showListDialog(
                com.contus.flycommons.Constants.EMPTY_STRING,
                arrayOf(
                    getString(R.string.choose_gallery_label),
                    getString(R.string.take_photo_label)
                )
            )
        } else {
            commonAlertDialog.showListDialog(
                com.contus.flycommons.Constants.EMPTY_STRING,
                arrayOf(
                    getString(R.string.choose_gallery_label),
                    getString(R.string.take_photo_label),
                    getString(R.string.remove_photo_label)
                )
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ChatManager.isActivityStartedForResult = false
        when (requestCode) {
            RequestCode.ADD_PARTICIPANTS -> {
                if (resultCode == Activity.RESULT_OK) {
                    handleAddParticipant(data)
                }
            }
            RequestCode.TAKE_PHOTO ->{
                if (resultCode == Activity.RESULT_OK) {
                    mFileCameraTemp = File(
                        FilePathUtils.getExternalStorage(),
                        com.contus.flycommons.Constants.TEMP_PHOTO_FILE_NAME
                    )
                    mFileCameraTemp?.let { handleCamera(it) }
                }
            }
            RequestCode.FROM_GALLERY -> {
                if (resultCode == Activity.RESULT_OK) {
                    handleGallery(data?.data!!)
                    CommonUtils.cropImage(this, fileTemp!!)
                }
            }
            RequestCode.CROP_IMAGE -> {
                if (resultCode == Activity.RESULT_OK) setOrUploadImage()
            }
        }
    }

    private fun handleAddParticipant(data: Intent?) {
        progressDialog = DoProgressDialog(this)
        progressDialog!!.showProgress()
        GroupManager.createGroup(binding.editNewGroupName.text.toString(), data!!.getStringArrayListExtra(Constants.USERS_JID)!!,
            fileTemp, { isSuccess, throwable, hashmap ->
            progressDialog!!.dismiss()
            if (isSuccess) {
                val groupData = hashmap.getData() as CreateGroupModel
                GroupManager.getGroupProfile(FlyUtils.getGroupJid(groupData.groupId), AppUtils.isNetConnected(this)) { success, _, data ->
                    if (success) {
                        val profileDetails = data[Constants.SDK_DATA] as ProfileDetails
                        SharedPreferenceManager.setBoolean(Constants.NEWLY_CREATED_GROUP, true)
                        SharedPreferenceManager.setString(Constants.NEW_GROUP_JID, profileDetails.jid)
                        SharedPreferenceManager.setString(Constants.NEW_GROUP_IMAGE, "")
                        if (encoded.isNotEmpty() && profileDetails.image.isEmpty())
                            showToast("Error uploading image")
                        else
                            SharedPreferenceManager.setString(Constants.NEW_GROUP_IMAGE, encoded)
                    }
                }
                showToast(hashmap.getMessage())
            } else {
                showToast(throwable.toString() + "***")
            }
            finish()
        })
    }


    private fun setOrUploadImage() {
        try {
            groupImage = true
            val photo = BitmapFactory.decodeFile(fileTemp!!.path)
            binding.imgGroup.setImageBitmap(photo)
            val baos = ByteArrayOutputStream()
            photo.compress(Bitmap.CompressFormat.PNG, 100, baos) //bm is the bitmap object
            val b: ByteArray = baos.toByteArray()
            encoded = Base64.encodeToString(b, Base64.DEFAULT)
        }catch (e: Exception){
            groupImage = false
            LogMessage.e(TAG, e)
        }

    }

    private fun initFileObject() {
        fileTemp = File(FilePathUtils.getExternalStorage(), Constants.TEMP_PHOTO_FILE_NAME + "_${System.currentTimeMillis()}")
    }

    private fun handleGallery(uri: Uri) {
        try {
            initFileObject()
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val fileOutputStream = FileOutputStream(fileTemp)
                ImagePopUpUtils.copyStream(inputStream, fileOutputStream)
                fileOutputStream.close()
                inputStream.close()
            }
        } catch (e: Exception) {
            LogMessage.e(e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            RequestCode.STORAGE_PERMISSION_CODE -> {
                if (MediaPermissions.isReadFilePermissionAllowed(this) &&
                        MediaPermissions.isWriteFilePermissionAllowed(this))
                    PickFileUtils.chooseImageFromGallery(this)
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // Hide emoji keyboard if showing
        if (emojiHandler.isEmojiShowing) emojiHandler.hideEmoji() else super.onBackPressed()
    }
    override fun onResume() {
        super.onResume()
        /*
         * Hide emoji keyboard if showing
         */
        if (emojiHandler.isEmojiShowing)
            emojiHandler.hideEmoji()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) emojiHandler.setKeypad(binding.editNewGroupName)
    }

    override fun onEmojiconBackspaceClicked(v: View?) {
        EmojiconsFragment.backspace(binding.editNewGroupName)
    }

    override fun onEmojiconClicked(emojicon: Emojicon?) {
        EmojiconsFragment.input(binding.editNewGroupName, emojicon)
    }

    override fun onDialogClosed(dialogType: CommonAlertDialog.DIALOGTYPE?, isSuccess: Boolean) {
        TODO("Not yet implemented")
    }

    override fun listOptionSelected(position: Int) {
        when (position) {
            0 -> {
                openGallery()
            }
            1 -> {
                takePhoto()
            }
            2 -> {
                groupImage = false
                fileTemp = null
                loadImageWithGlideSecure(this, com.contus.flycommons.Constants.EMPTY_STRING, binding.imgGroup,
                    ContextCompat.getDrawable(this, R.drawable.ic_grp_bg))
            }
        }
    }

    private fun openGallery() {

        if (MediaPermissions.isReadFilePermissionAllowed(this) &&
            MediaPermissions.isWriteFilePermissionAllowed(this))
            PickFileUtils.chooseImageFromGallery(this)
        else MediaPermissions.requestStorageAccess(this, galleryPermissionLauncher)

    }

    /**
     * Take a photo to update group image.
     */
    private fun takePhoto() {
        if (MediaPermissions.isPermissionAllowed(this, Manifest.permission.CAMERA)
            && MediaPermissions.isWriteFilePermissionAllowed(this)) {
            takePhotoFromCamera(this, FilePathUtils.getExternalStorage().toString() + "/"
                    + getString(R.string.title_app_name) + "/" + MediaPaths.MEDIA_PATH_PROFILE_PHOTOS, true)
        } else {
            MediaPermissions.requestCameraStoragePermissions(this, cameraPermissionLauncher)
        }
    }

    /**
     * Handle the camera from the Group image selection.
     *
     * @param file The File of the Group image
     */
    private fun handleCamera(file: File) {
        try {
            initFileObject()
            val inputStream = contentResolver.openInputStream(Uri.fromFile(file))
            if (inputStream != null) {
                val fileOutputStream = FileOutputStream(fileTemp)
                ImagePopUpUtils.copyStream(inputStream, fileOutputStream)
                fileOutputStream.close()
                inputStream.close()
                CommonUtils.cropImage(this, fileTemp!!)
            }
        } catch (e: java.lang.Exception) {
            LogMessage.e(e)
        }
    }

}