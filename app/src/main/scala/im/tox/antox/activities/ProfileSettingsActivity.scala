package im.tox.antox.activities

import java.io.{File, FileNotFoundException, FileOutputStream, IOException}

import android.app.AlertDialog
import android.content.{Context, DialogInterface, Intent, SharedPreferences}
import android.graphics.{Bitmap, BitmapFactory}
import android.net.Uri
import android.os.{Build, Bundle, Environment}
import android.preference.Preference.OnPreferenceClickListener
import android.preference.{ListPreference, Preference, PreferenceActivity, PreferenceManager}
import android.view.{MenuItem, View}
import android.widget.{ImageButton, Toast}
import com.google.zxing.{BarcodeFormat, WriterException}
import im.tox.QR.{Contents, QRCodeEncode}
import im.tox.antox.activities.ProfileSettingsActivity._
import im.tox.antox.data.UserDB
import im.tox.antox.tox.{ToxDoService, ToxSingleton}
import im.tox.antox.transfer.FileDialog
import im.tox.antox.transfer.FileDialog.DirectorySelectedListener
import im.tox.antox.wrapper.UserStatus
import im.tox.antoxnightly.R
import im.tox.tox4j.exceptions.ToxException

object ProfileSettingsActivity {

  private val sBindPreferenceSummaryToValueListener: Preference.OnPreferenceChangeListener = new Preference.OnPreferenceChangeListener() {

    override def onPreferenceChange(preference: Preference, value: AnyRef): Boolean = {
      val stringValue = value.toString
      if (preference.isInstanceOf[ListPreference]) {
        val listPreference = preference.asInstanceOf[ListPreference]
        val index = listPreference.findIndexOfValue(stringValue)
        preference.setSummary(if (index >= 0) listPreference.getEntries()(index) else null)
      } else {
        preference.setSummary(stringValue)
      }
      true
    }
  }

  private def bindPreferenceSummaryToValue(preference: Preference) {
    preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener)
    sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, PreferenceManager.getDefaultSharedPreferences(preference.getContext)
      .getString(preference.getKey, ""))
  }
}

class ProfileSettingsActivity extends PreferenceActivity with SharedPreferences.OnSharedPreferenceChangeListener {

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    addPreferencesFromResource(R.xml.pref_profile)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB &&
      getActionBar != null) {
      getActionBar.setDisplayHomeAsUpEnabled(true)
    }
    bindPreferenceSummaryToValue(findPreference("nickname"))
    val passwordPreference = findPreference("password")
    if (PreferenceManager.getDefaultSharedPreferences(passwordPreference.getContext)
        .getString(passwordPreference.getKey, "").isEmpty) {
      getPreferenceScreen.removePreference(passwordPreference)
    } else {
      bindPreferenceSummaryToValue(passwordPreference)
    }
    bindPreferenceSummaryToValue(findPreference("status"))

    if (false) {
      //TODO: Finish avatars
      bindPreferenceSummaryToValue(findPreference("avatar"))
    } else {
      getPreferenceScreen.removePreference(findPreference("avatar"))
    }

    bindPreferenceSummaryToValue(findPreference("status_message"))
    bindPreferenceSummaryToValue(findPreference("tox_id"))
    bindPreferenceSummaryToValue(findPreference("active_account"))
    val toxIDPreference = findPreference("tox_id")
    toxIDPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

      override def onPreferenceClick(preference: Preference): Boolean = {
        createToxIDDialog()
        true
      }
    })

   /* val avatarPreference = findPreference("avatar")
    avatarPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

      override def onPreferenceClick(preference: Preference): Boolean = {
        createAvatarDialog()
        true
      }
    }) */

    val exportProfile = findPreference("export")
    val thisActivity = this
    exportProfile.setOnPreferenceClickListener(new OnPreferenceClickListener {
      override def onPreferenceClick(preference: Preference): Boolean = {
        val fileDialog = new FileDialog(thisActivity, Environment.getExternalStorageDirectory, true)
        fileDialog.addDirectoryListener(new DirectorySelectedListener {
          override def directorySelected(directory: File): Unit = {
            onExportDataFileSelected(directory)
          }
        })
        fileDialog.showDialog()
        true
      }
    })
    val logoutPreference = findPreference("logout")
    logoutPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

      override def onPreferenceClick(preference: Preference): Boolean = {
        val preferences = PreferenceManager.getDefaultSharedPreferences(ProfileSettingsActivity.this)
        val editor = preferences.edit()
        editor.putBoolean("loggedin", false)
        editor.apply()
        val startTox = new Intent(ProfileSettingsActivity.this.getApplicationContext, classOf[ToxDoService])
        ProfileSettingsActivity.this.getApplicationContext.stopService(startTox)
        val login = new Intent(ProfileSettingsActivity.this.getApplicationContext, classOf[LoginActivity])
        ProfileSettingsActivity.this.startActivity(login)
        ProfileSettingsActivity.this.finish()
        true
      }
    })
  }

  def createToxIDDialog() {
    val builder = new AlertDialog.Builder(ProfileSettingsActivity.this)
    val inflater = ProfileSettingsActivity.this.getLayoutInflater
    val view = inflater.inflate(R.layout.dialog_tox_id, null)
    builder.setView(view)
    builder.setPositiveButton(getString(R.string.button_ok), null)
    builder.setNeutralButton(getString(R.string.dialog_tox_id), new DialogInterface.OnClickListener() {

      def onClick(dialogInterface: DialogInterface, ID: Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ProfileSettingsActivity.this)
        val clipboard = ProfileSettingsActivity.this.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[android.text.ClipboardManager]
        clipboard.setText(sharedPreferences.getString("tox_id", ""))
      }
    })
    var file = new File(Environment.getExternalStorageDirectory.getPath + "/Antox/")
    if (!file.exists()) {
      file.mkdirs()
    }
    val noMedia = new File(Environment.getExternalStorageDirectory.getPath + "/Antox/", ".nomedia")
    if (!noMedia.exists()) {
      try {
        noMedia.createNewFile()
      } catch {
        case e: IOException => e.printStackTrace()
      }
    }

    file = new File(Environment.getExternalStorageDirectory.getPath + "/Antox/userkey_qr.png")
    val pref = PreferenceManager.getDefaultSharedPreferences(ProfileSettingsActivity.this.getApplicationContext)
    generateQR(pref.getString("tox_id", ""))
    val bmp = BitmapFactory.decodeFile(file.getAbsolutePath)
    val qrCode = view.findViewById(R.id.qr_image).asInstanceOf[ImageButton]
    qrCode.setImageBitmap(bmp)
    qrCode.setOnClickListener(new View.OnClickListener() {

      override def onClick(v: View) {
        val shareIntent = new Intent()
        shareIntent.setAction(Intent.ACTION_SEND)
        shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(Environment.getExternalStorageDirectory.getPath + "/Antox/userkey_qr.png")))
        shareIntent.setType("image/jpeg")
        view.getContext.startActivity(Intent.createChooser(shareIntent, getResources.getString(R.string.share_with)))
      }
    })
    builder.create().show()
  }

  def createAvatarDialog(): Unit = {
    val context: Context = ProfileSettingsActivity.this
    /* val dialog = new MaterialDialog.Builder(context)
      .customView(R.layout.dialog_avatar, false)
      .build()

    val avatarView = dialog.getCustomView.findViewById(R.id.avatar_image).asInstanceOf[ImageButton]
    val photoButton = dialog.getCustomView.findViewById(R.id.avatar_takephoto)
    val fileButton = dialog.getCustomView.findViewById(R.id.avatar_pickfile)
    val pref = PreferenceManager.getDefaultSharedPreferences(ProfileSettingsActivity.this.getApplicationContext)

    val avatar = AVATAR.getAvatarFile(pref.getString("avatar", ""), context)
    if (avatar.isDefined && avatar.get.exists()) {
      avatarView.setImageURI(Uri.fromFile(avatar.get))
    } else {
      avatarView.setImageResource(R.drawable.ic_action_contact)
    }


    dialog.show() */
  }

  def onExportDataFileSelected(dest: File): Unit = {
    try {
      ToxSingleton.exportDataFile(dest)
      Toast.makeText(getApplicationContext, "Exported data file to " + dest.getPath, Toast.LENGTH_LONG)
        .show()
    } catch {
      case e: Exception => {
        e.printStackTrace()
        Toast.makeText(getApplicationContext, "Error: Could not export data file.", Toast.LENGTH_LONG).show()
      }
    }
  }

  private def generateQR(userKey: String) {
    val qrData = "tox:" + userKey
    val qrCodeSize = 400
    val qrCodeEncoder = new QRCodeEncode(qrData, null, Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString,
      qrCodeSize)
    var out: FileOutputStream = null
    try {
      val bitmap = qrCodeEncoder.encodeAsBitmap()
      out = new FileOutputStream(Environment.getExternalStorageDirectory.getPath + "/Antox/userkey_qr.png")
      bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
      out.close()
    } catch {
      case e: WriterException => e.printStackTrace()
      case e: FileNotFoundException => e.printStackTrace()
      case e: IOException => e.printStackTrace()
    }
  }

  override def onResume() {
    super.onResume()
    getPreferenceScreen.getSharedPreferences.registerOnSharedPreferenceChangeListener(this)
  }

  override def onPause() {
    super.onPause()
    getPreferenceScreen.getSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
  }

  def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, preferenceKey: String) {
    val db = new UserDB(this)
    val activeAccount = sharedPreferences.getString("active_account", "")
    val keyString = sharedPreferences.getString(preferenceKey, "")

    preferenceKey match {
      case "nickname" =>
        try {
          val name = sharedPreferences.getString(keyString, "")
          ToxSingleton.tox.setName(name)
          ToxSingleton.tox.setGroupSelfNameAll(name)
        } catch {
          case e: ToxException => e.printStackTrace()
        }
        db.updateUserDetail(activeAccount, "nickname", keyString)

      case "password" =>
        db.updateUserDetail(activeAccount, "password", keyString)

      case "status" =>
        val newStatusString = sharedPreferences.getString(keyString, "")
        val newStatus = UserStatus.getToxUserStatusFromString(newStatusString)
        try {
          ToxSingleton.tox.setStatus(newStatus)
        } catch {
          case e: ToxException => e.printStackTrace()
        }
        db.updateUserDetail(activeAccount, "status", keyString)

      case "status_message" =>
        try {
          ToxSingleton.tox.setStatusMessage(sharedPreferences.getString(keyString, ""))
        } catch {
          case e: ToxException => e.printStackTrace()
        }
        db.updateUserDetail(activeAccount, "status_message", keyString)

      case "avatar" =>
        db.updateUserDetail(activeAccount, "avatar", keyString)

      case _ =>
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = item.getItemId match {
    case android.R.id.home =>
      finish()
      true

  }
}
