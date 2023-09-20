package app.wristkey

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Point
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Base64.DEFAULT
import android.util.Base64.encodeToString
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidmads.library.qrgenearator.QRGContents
import androidmads.library.qrgenearator.QRGEncoder
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs


@RequiresApi(Build.VERSION_CODES.M)
class Utilities (context: Context) {

    val FILES_REQUEST_CODE = 69
    val CAMERA_REQUEST_CODE = 420
    val EXPORT_RESPONSE_CODE = 69420

    val JSON_MIME_TYPE = "application/json"
    val JPG_MIME_TYPE = "image/jpeg"
    val PNG_MIME_TYPE = "image/png"

    val OTPAUTH_SCAN_CODE = "OTPAUTH_SCAN_CODE"
    val AUTHENTICATOR_EXPORT_SCAN_CODE = "AUTHENTICATOR_EXPORT_SCAN_CODE"
    val QR_CODE_SCAN_REQUEST = "QR_CODE_SCAN_REQUEST"

    val INTENT_WIFI_IP = "INTENT_WIFI_IP"

    val context = context

    val QR_TIMER_DURATION = 5

    val INTENT_QR_DATA = "INTENT_QR_DATA"
    val INTENT_QR_METADATA = "INTENT_QR_METADATA"
    val INTENT_WIPE = "INTENT_WIPE"
    val INTENT_DELETE = "INTENT_DELETE"
    val INTENT_DELETE_MODE = "INTENT_DELETE_MODE"

    val SETTINGS_BACKGROUND_COLOR = "SETTINGS_BACKGROUND_COLOR"
    val SETTINGS_ACCENT_COLOR = "SETTINGS_ACCENT_COLOR"

    val SETTINGS_SEARCH_ENABLED = "SETTINGS_SEARCH_ENABLED"
    val SETTINGS_CLOCK_ENABLED = "SETTINGS_CLOCK_ENABLED"
    val SETTINGS_24H_CLOCK_ENABLED = "SETTINGS_24H_CLOCK_ENABLED"
    val SETTINGS_HAPTICS_ENABLED = "SETTINGS_HAPTICS_ENABLED"
    val SETTINGS_BEEP_ENABLED = "SETTINGS_BEEP_ENABLED"
    val CONFIG_SCREEN_ROUND = "CONFIG_SCREEN_ROUND"
    val SETTINGS_LOCK_ENABLED = "SETTINGS_LOCK_ENABLED"

    val DATA_STORE = "DATA_STORE"

    val MFA_TIME_MODE = "totp"
    val MFA_COUNTER_MODE = "hotp"

    val ALGO_SHA1 = "SHA1"
    val ALGO_SHA256 = "SHA256"
    val ALGO_SHA512 = "SHA512"

    var masterKey: MasterKey
    private val accountsFilename: String = "vault.wfs" // WristkeyFS
    var db: SharedPreferences
    private val objectMapper: ObjectMapper

    init {
        masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder (
                    MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(MasterKey.DEFAULT_AES_GCM_MASTER_KEY_SIZE)
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_SHA512)
                    .build()
            )
            .setRequestStrongBoxBacked(true)
            .build()

        db = EncryptedSharedPreferences.create (
            context,
            accountsFilename,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
    }

    data class MfaCode (
        val mode: String,
        val issuer: String,
        val account: String,
        val secret: String,
        val algorithm: String,
        val digits: Int,
        val period: Int,
        val lock: Boolean,
        val counter: Long,
        val label: String,
    )

    fun isIp (string: String): Boolean {
        if (string.contains("0.0.") || string.contains("10.0.")) return false
        return """^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d{1,5}$""".toRegex().matches(string)
    }
    fun hasCamera(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    fun generateQrCode (qrData: String, windowManager: WindowManager): Bitmap? {
        val display = windowManager.defaultDisplay
        val point = Point()
        display.getSize(point)
        val width: Int = point.x + 150
        val height: Int = point.y + 150
        val dimensions = if (width < height) width else height

        val qrEncoder = QRGEncoder(qrData, null, QRGContents.Type.TEXT, dimensions)
        return qrEncoder.bitmap
    }
    @Suppress("DEPRECATION")
    fun wiFiExists(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    fun scanQRImage(bMap: Bitmap): String {
        var contents: String
        val intArray = IntArray(bMap.width * bMap.height)
        //copy pixel data from the Bitmap into the 'intArray' array
        bMap.getPixels(intArray, 0, bMap.width, 0, 0, bMap.width, bMap.height)
        val source: LuminanceSource = RGBLuminanceSource(bMap.width, bMap.height, intArray)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader: Reader = MultiFormatReader()

        contents = try {
            val result = reader.decode(bitmap)
            result.text
        } catch (e: Exception) {
            "No data found"
        }

        return contents
    }

    fun authenticatorToWristkey (decodedQRCodeData: String): MutableList<MfaCode> {

        fun decodeAuthenticatorData (authenticatorJsonString: String): MutableList<MfaCode> {
            val logins = mutableListOf<MfaCode>()

            val items = JSONObject(authenticatorJsonString)
            for (key in items.keys()) {
                val itemData = JSONObject(items[key].toString())

                var mode: String = if (itemData["type"].toString() == "1") "hotp" else "totp"

                var issuer: String = key

                var account: String = if (itemData["username"].toString().isNotEmpty()
                    && itemData["username"].toString() != issuer)
                    itemData["username"].toString()
                else ""

                var secret: String = itemData["secret"].toString()

                logins.add(
                    MfaCode (
                        mode = mode,
                        issuer = issuer,
                        account = account,
                        secret = secret,
                        algorithm = ALGO_SHA1,
                        digits = 6,
                        period = 30,
                        lock = false,
                        counter = 0,
                        label = ""
                    )
                )
            }

            return logins
        }

        fun scanAndDecodeQrCode(decodedQRCodeData: String): String {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }

            val pythonRuntime = Python
                .getInstance()
                .getModule("extract_otp_secret_keys")
                .callAttr("decode", decodedQRCodeData)

            val timeStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

            val logcat: Process
            val log = StringBuilder()
            try {
                logcat = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
                val br =
                    BufferedReader(InputStreamReader(logcat.inputStream), 4 * 1024)
                var line: String?
                val separator = System.getProperty("line.separator")
                while (br.readLine().also { line = it } != null) {
                    log.append(line)
                    log.append(separator)
                }
                Runtime.getRuntime().exec("clear")
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }

            // pythonRuntime.close()

            return log.toString()
                .substringAfter(timeStamp)  // get most recent occurrence of data
                .substringAfter("python.stdout")
                .substringAfter("<wristkey>")
                .substringBefore("<\\wristkey>")
        }

        // put data in Python script and extract Authenticator data
        var logins: MutableList<MfaCode> = mutableListOf()
        if (decodedQRCodeData.contains("otpauth-migration://")) {
            val logExtractedString = scanAndDecodeQrCode(decodedQRCodeData)
            logins = decodeAuthenticatorData(logExtractedString)
        }

        return logins

    }

    fun wfsToHashmap(jsonObject: JSONObject): Map<String, Any> {
        val map: MutableMap<String, String> = HashMap()
        for (key in jsonObject.keys()) {
            map[key] = jsonObject[key] as String
        }
        return map
    }

    fun bitwardenToWristkey (jsonObject: JSONObject): MutableList<MfaCode> {

        val logins = mutableListOf<MfaCode>()

        val items = jsonObject["items"].toString()
        val itemsArray = JSONArray(items)

        val accounts = JSONArray(itemsArray.toString())

        val accountList = mutableListOf<JSONObject>()

        for (index in 0 until accounts.length()) {
            accountList.add(JSONObject(accounts[index].toString()))
        }

        for (account in accountList) {

            try {
                val issuer = account["name"].toString().trim()
                val totp = JSONObject(account["login"].toString())["totp"].toString().trim()
                val username = JSONObject(account["login"].toString())["username"].toString().trim()

                if (!totp.contains("null") && !totp.isNullOrBlank()) {

                    if (totp.startsWith("otpauth://")) {
                        val type: String = if (totp.substringAfter("://").substringBefore("/").contains("totp")) "totp" else "hotp"
                        val issuer: String = totp.substringAfterLast("otp/").substringBefore(":")
                        val account: String = totp.substringAfterLast(":").substringBefore("?")
                        val secret: String = totp.substringAfter("secret=").substringBefore("&")
                        val algorithm: String = if (totp.contains("algorithm")) totp.substringAfter("algorithm=").substringBefore("&") else ALGO_SHA1
                        val digits: Int = if (totp.contains("digits")) totp.substringAfter("digits=").substringBefore("&").toInt() else 6
                        val period: Int = if (totp.contains("period")) totp.substringAfter("period=").substringBefore("&").toInt() else 30
                        val lock: Boolean = if (totp.contains("lock")) totp.substringAfter("lock=").substringBefore("&").toBoolean() else false
                        val counter: Long = if (totp.contains("counter")) totp.substringAfter("counter=").substringBefore("&").toLong() else 0
                        val label: String = if (totp.contains("label")) totp.substringAfter("label=").substringBefore("&") else account
                        logins.add (
                            MfaCode(
                                mode = type,
                                issuer = issuer,
                                account = account,
                                secret = secret,
                                algorithm = algorithm,
                                digits = digits,
                                period = period,
                                lock = false,
                                counter = counter,
                                label = label
                            )
                        )

                    } else if (!totp.startsWith("otpauth://")) { // Google Authenticator
                        logins.add (
                            MfaCode(
                                mode = "totp",
                                issuer = issuer.ifBlank { "Unknown issuer" },
                                account = username.ifBlank { "" },
                                secret = totp,
                                algorithm = ALGO_SHA1,
                                digits = 6,
                                period = 30,
                                lock = false,
                                counter = 0,
                                label = ""
                            )
                        )
                    }
                }

            } catch (_: JSONException) { }

        }

        return logins

    }

    fun andOtpToWristkey (jsonArray: JSONArray): MutableList<MfaCode> {

        val logins = mutableListOf<MfaCode>()

        for (itemIndex in 0 until jsonArray.length()) {

            val login = jsonArray[itemIndex].toString()
            val secret = JSONObject(login)["secret"].toString().replace("=", "")
            val issuer = JSONObject(login)["issuer"].toString()
            val counter = try { JSONObject(login)["used_frequency"].toString().toLong() } catch (_: JSONException) { 0 }
            val algorithm = JSONObject(login)["algorithm"].toString()
            val digits = JSONObject(login)["digits"].toString().toInt()
            val period = JSONObject(login)["period"].toString().toInt()
            var type = JSONObject(login)["type"].toString().lowercase()
            if (type == "STEAM") type = "totp"
            val label = try { JSONObject(login)["label"].toString() } catch (_: JSONException) { "" }

            logins.add (
                MfaCode(
                    mode = type,
                    issuer = issuer,
                    account = label,
                    secret = secret,
                    algorithm = algorithm,
                    digits = digits,
                    period = period,
                    lock = false,
                    counter = counter,
                    label = label
                )
            )

        }

        return logins

    }

    fun searchLogins (searchTerms: String, logins: MutableList<MfaCode>): MutableList<MfaCode> {
        val results = mutableListOf<MfaCode>()
        for (_login in logins)
            if (_login
                    .toString()
                    .lowercase()
                    .replace("mfacode", "")
                    .replace("issuer", "")
                    .replace("secret", "")
                    .replace("lock", "")
                    .replace("counter", "")
                    .replace("period", "")
                    .replace("digits", "")
                    .replace("mode", "")
                    .replace("algorithm", "")
                    .replace("label", "")
                    .replace(Regex("""[^a-zA-Z\\d]"""), "")
                    .contains(searchTerms.lowercase())
            ) results.add(_login)
        return results
    }

    fun aegisToWristkey (unencryptedAegisJsonString: String): MutableList<MfaCode> {

        val logins = mutableListOf<MfaCode>()

        val db = JSONObject(unencryptedAegisJsonString)["db"].toString()
        val entries = JSONObject(db)["entries"].toString()

        val itemsArray = JSONArray(entries)

        for (itemIndex in 0 until itemsArray.length()) {
            try {

                val accountData = JSONObject(itemsArray[itemIndex].toString())
                var type = accountData["type"]
                val uuid = accountData["uuid"].toString()
                val issuer = accountData["issuer"].toString()
                var username = accountData["name"].toString()

                if (username == issuer || username == "null" || username.isNullOrEmpty()) {
                    username = ""
                }

                var totpSecret = JSONObject(accountData["info"].toString())["secret"].toString()
                val digits = JSONObject(accountData["info"].toString())["digits"].toString().toInt()
                var algorithm = JSONObject(accountData["info"].toString())["algo"].toString()
                var period = JSONObject(accountData["info"].toString())["period"].toString().toInt()
                var counter = try { JSONObject(accountData["info"].toString())["counter"].toString().toLong() } catch (_: JSONException) { 0L }

                type = if (type.equals("totp")) "totp" else "hotp"

                if (totpSecret.isNotEmpty() && totpSecret != "null") {
                    logins.add (
                        MfaCode(
                            mode = type,
                            issuer = issuer,
                            account = username,
                            secret = totpSecret,
                            algorithm = algorithm,
                            digits = digits,
                            period = period,
                            lock = false,
                            counter = counter,
                            label = ""
                        )
                    )
                }

            } catch (noData: JSONException) {
                noData.printStackTrace()
            }
        }

        return logins

    }

    fun decodeOtpAuthURL(otpAuthURL: String): MfaCode? {
        return try {
            val decodedURL = URLDecoder.decode(otpAuthURL, "UTF-8")

            val parts = decodedURL.split("?")
            if (parts.size != 2) return null

            val params = parts[1].split("&")
            val paramMap = mutableMapOf<String, String>()

            for (param in params) {
                val keyValue = param.split("=")
                if (keyValue.size == 2) paramMap[keyValue[0]] = keyValue[1]
            }

            val mode = if (parts[0].substringAfterLast("/") == "hotp") MFA_COUNTER_MODE else MFA_TIME_MODE
            val account = parts[0].substringBefore(":")
            val issuer = paramMap["issuer"] ?: account
            val secret = paramMap["secret"]
            val algorithm = paramMap["algorithm"] ?: ALGO_SHA1
            val digits = paramMap["digits"]?.toIntOrNull() ?: 6 // Default to 6 digits
            val period = paramMap["period"]?.toIntOrNull() ?: 30 // Default to 30 seconds
            val lock = paramMap["lock"]?.toBoolean() ?: false // Default to false
            val counter = paramMap["counter"]?.toLongOrNull()
            val label = paramMap["label"]

            // Create and return the MfaCode object
            MfaCode(mode, issuer, account, secret ?: "", algorithm, digits, period, lock, counter ?: 0, label ?: "")
        } catch (e: Exception) { null }
    }

    fun encodeOtpAuthURL (mfaCodeObject: MfaCode): String {
        // TOTPs: otpauth://totp/Google%20LLC%2E:me%400x4f.in?secret=ASDFGHJKL&issuer=Google&algorithm=SHA1&digits=6&period=30&counter=0&label=Work
        // HOTPs: otpauth://hotp/GitHub%20Inc%2E:me%400x4f.in?secret=QWERTYUIOP&issuer=GitHub&algorithm=SHA1&digits=6&counter=10

        val issuer: String = URLEncoder.encode(mfaCodeObject.issuer)
        val account: String = URLEncoder.encode(mfaCodeObject.account)
        val secret: String = mfaCodeObject.secret.replace(" ", "")
        val digits: String = mfaCodeObject.digits.toString()
        val period: String = mfaCodeObject.period.toString()
        val algorithm: String = mfaCodeObject.algorithm.replace(" ", "").replace("-", "").uppercase()
        val lock: String = mfaCodeObject.lock.toString()
        val counter: String = mfaCodeObject.counter.toString()
        val label: String = URLEncoder.encode(mfaCodeObject.label)

        return if (mfaCodeObject.mode.lowercase().contains(MFA_TIME_MODE)) "otpauth://${mfaCodeObject.mode}/$issuer:$account?secret=$secret&algorithm=$algorithm&digits=$digits&period=$period&lock=$lock&label=$label"
        else "otpauth://${MFA_COUNTER_MODE}/$issuer:$account?secret=$secret&algorithm=$algorithm&digits=$digits&counter=$counter&lock=$lock&label=$label"
    }

    fun deleteFromVault (uuid4: String): Boolean {
        val items  = db.all

        for (item in items) {
            if (item.key.contains(uuid4)) {
                db.edit().remove(item.key).apply()
            }
        }

        return true
    }

    fun getLogin (uuid: String): Utilities.MfaCode? {
        val items  = db.all
        var value: Utilities.MfaCode? = null

        for (item in items) {
            try {
                value = decodeOtpAuthURL(item.value as String) as Utilities.MfaCode
                if (item.key == uuid) return value
            } catch (_: Exception) { }
        }

        return value
    }

    class WristkeyFileSystem (
        @JsonProperty("otpauth") val otpauth: MutableList<String>
    )

    fun overwriteLogin (otpAuthURL: String): Boolean {  // Overwrites an otpAuth String if it already exists
        var data = objectMapper.writeValueAsString (
            WristkeyFileSystem(
                mutableListOf()
            )
        )

        data = db.getString(DATA_STORE, data)

        val dataStore =
            objectMapper.readValue (
                data,
                WristkeyFileSystem::class.java
            )

        val iterator = dataStore.otpauth.iterator()
        while (iterator.hasNext()) {
            val login = iterator.next()
            val loginSecret = decodeOtpAuthURL(login)!!.secret.lowercase().replace(" ", "")
            val secretToWrite = decodeOtpAuthURL(otpAuthURL)!!.secret.lowercase().replace(" ", "")
            if (loginSecret.contains(secretToWrite)) iterator.remove()
        }

        dataStore.otpauth.add(otpAuthURL)
        data = objectMapper.writeValueAsString(dataStore)
        db.edit().putString(DATA_STORE, data).apply()

        return true
    }

    fun getData (): WristkeyFileSystem {
        val data = db.getString (DATA_STORE, null)
        return objectMapper.readValue(
            data,
            WristkeyFileSystem::class.java
        )
    }

    fun beep () {
        try {
            val toneGen1 = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen1.startTone(ToneGenerator.TONE_SUP_INTERCEPT, 150)
        } catch (_: Exception) { }
    }

    fun getLocalIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = wifiManager.connectionInfo

            if (wifiInfo != null) {
                val ipAddress = wifiInfo.ipAddress
                val ipByteArray = byteArrayOf(
                    (ipAddress and 0xFF).toByte(),
                    (ipAddress shr 8 and 0xFF).toByte(),
                    (ipAddress shr 16 and 0xFF).toByte(),
                    (ipAddress shr 24 and 0xFF).toByte()
                )

                val inetAddress = InetAddress.getByAddress(ipByteArray)
                return inetAddress.hostAddress
            }
        } catch (e: Exception) {
            Log.e("IP Address", "Error getting IP address: ${e.message}")
        }
        return null
    }

    fun encrypt(data: String, passphrase: String): String? {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val sha256 = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8)) // base64-ing the payload first lets you encode things like emoji properly

        val hashedPassphrase: ByteArray = sha256

        val secretKey: SecretKey = SecretKeySpec(hashedPassphrase, "AES")

        val iv = ByteArray(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

        val encryptedData = cipher.doFinal(encodeToString(data.toByteArray(charset("UTF-8")), DEFAULT).toByteArray(charset("UTF-8")))
        return encodeToString(encryptedData, DEFAULT)
    }

    fun decrypt(encryptedData: String, passphrase: String): String? {
        try {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            val sha256 = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8))

            val secretKey: SecretKey = SecretKeySpec(sha256, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(16)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val encryptedBytes = Base64.decode(encryptedData, DEFAULT)
            val decryptedBytes = cipher.doFinal(encryptedBytes)

            return String(
                Base64.decode( // decoding again because we base64-ed the payload as we to let you encode things like emoji properly
                    String(
                        decryptedBytes,
                        StandardCharsets.UTF_8
                    ), DEFAULT
                ),
                StandardCharsets.UTF_8
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

}

// UI stuff
open class OnSwipeTouchListener(c: Context?) : View.OnTouchListener {
    private val gestureDetector: GestureDetector
    override fun onTouch(view: View?, motionEvent: MotionEvent?): Boolean {
        return gestureDetector.onTouchEvent(motionEvent!!)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClick()
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            onLongClick()
            super.onLongPress(e)
        }

        // Determines the fling velocity and then fires the appropriate swipe event accordingly
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val result = false
            try {
                val diffY = e2.y - e1!!.y
                val diffX = e2.x - e1.x
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    }
                } else {
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY > 0) onSwipeDown() else onSwipeUp()
                    }
                }
            } catch (exception: java.lang.Exception) { exception.printStackTrace() }
            return result
        }

    }

    open fun onSwipeRight() { }
    open fun onSwipeLeft() { }
    open fun onSwipeUp() { }
    open fun onSwipeDown() { }
    open fun onClick() { }
    fun onDoubleClick() { }
    open fun onLongClick() { }

    init { gestureDetector = GestureDetector(c, GestureListener()) }

}
