/*
 * Copyright (c) 2012-2023 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package custom.mohekkus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.blinkt.openvpn.R
import de.blinkt.openvpn.api.APIVpnProfile
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Text
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

class MyActivity: AppCompatActivity() {

    private val ICS_OPENVPN_PERMISSION = 7
    private var mService: IOpenVPNAPIService? = null
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  We are communicating with our
            // service through an IDL interface, so get a client-side
            // representation of that from the raw service object.
            mService = IOpenVPNAPIService.Stub.asInterface(service)
            try {
                // Request permission to use the API
                val i: Intent? = mService?.prepare(this@MyActivity.packageName)
                if (i != null) {
                    startActivityForResult(i, ICS_OPENVPN_PERMISSION)
                } else {
                    onActivityResult(ICS_OPENVPN_PERMISSION, RESULT_OK, null)
                }
            } catch (e: RemoteException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null
        }
    }


    private val MSG_UPDATE_STATE = 0
    private var mHandler: Handler? = null
    private val mCallback: IOpenVPNStatusCallback = object : IOpenVPNStatusCallback.Stub() {
        /**
         * This is called by the remote service regularly to tell us about
         * new values.  Note that IPC calls are dispatched through a thread
         * pool running in each process, so the code executing here will
         * NOT be running in our main thread like most other things -- so,
         * to update the UI, we need to use a Handler to hop over there.
         */
        @Throws(RemoteException::class)
        override fun newStatus(uuid: String, state: String, message: String, level: String) {
            this@MyActivity.runOnUiThread {
                findViewById<TextView>(R.id.tv_status).text = state
                findViewById<TextView>(R.id.ipHolder).text =
                    "Updating Please Wait"
                when (state) {
                    "NOPROCESS" -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            var ip: String? = null
                            // Perform some long-running operation asynchronously
                            val result = withContext(Dispatchers.IO) {
                                // Simulate a network request or other async operation
                                delay(2000)
                                ip = getMyOwnIP() // Delay for 2 seconds
                                "Async operation completed"
                            }

                            // Update UI or perform any other operation with the result
                            this@MyActivity.runOnUiThread {
                                findViewById<TextView>(R.id.ipHolder).text = ip
                            }
                        }
                        findViewById<Button>(R.id.b_intent).apply {
                            text = "CONNECT"
                            setOnClickListener {
                                checkForProfiles()
                            }
                        }
                    }
                    "CONNECTED" -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            var ip: String? = null
                            // Perform some long-running operation asynchronously
                            val result = withContext(Dispatchers.IO) {
                                // Simulate a network request or other async operation
                                delay(2000)
                                ip = getMyOwnIP() // Delay for 2 seconds
                                "Async operation completed"
                            }

                            // Update UI or perform any other operation with the result
                            this@MyActivity.runOnUiThread {
                                findViewById<TextView>(R.id.ipHolder).text = ip
                            }
                        }
                        findViewById<Button>(R.id.b_intent).apply {
                            text = "DISCONNECT"
                            setOnClickListener {
                                mService?.disconnect()
                            }
                        }
                    }
                    else -> {
                        findViewById<TextView>(R.id.ipHolder).text = "Updating Please Wait"
                        findViewById<Button>(R.id.b_intent).setOnClickListener {  }
                    }
                }
//                Toast.makeText(this@MyActivity, state, Toast.LENGTH_LONG).show()
            }
            val msg = Message.obtain(
                mHandler, MSG_UPDATE_STATE,
                "$state|$message"
            )
            msg.sendToTarget()
        }
    }

    override fun onStart() {
        super.onStart()
        mHandler = Handler(this.mainLooper)
        bindService()
    }

    private fun bindService() {
        val icsopenvpnService = Intent(IOpenVPNAPIService::class.java.name)
        icsopenvpnService.setPackage("de.blinkt.openvpn")
        this.bindService(icsopenvpnService, mConnection, BIND_AUTO_CREATE)
    }

//    private var viewModel: MyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_myactivity)
        findViewById<TextView>(R.id.tv_status).text = "NOPROCESS"
        CoroutineScope(Dispatchers.Main).launch {
            var ip: String? = null
            // Perform some long-running operation asynchronously
            val result = withContext(Dispatchers.IO) {
                // Simulate a network request or other async operation
                delay(2000)
                ip = getMyOwnIP() // Delay for 2 seconds
                "Async operation completed"
            }

            // Update UI or perform any other operation with the result
            this@MyActivity.runOnUiThread {
                findViewById<TextView>(R.id.ipHolder).text = ip
            }
        }
        findViewById<Button>(R.id.b_intent).setOnClickListener {
            checkForProfiles()
        }
    }

    private fun checkForProfiles() {
        getProfile(addNew = true, editable = false)
    }

    private fun getProfile(addNew: Boolean, editable: Boolean) {
        try {
            /* Try opening test.local.conf first */
            val conf: InputStream = try {
                this.assets.open("test.local.conf")
            } catch (e: IOException) {
                this.assets.open("alfiansyah.ovpn")
            }
            val br = BufferedReader(InputStreamReader(conf))
            val config = StringBuilder()
            var line: String?
            while (true) {
                line = br.readLine()
                if (line == null) break
                config.append(line).append("\n")
            }
            br.close()
            conf.close()
            if (addNew) {
                val name = "Non editable profile"
                val profile: APIVpnProfile? =
                    mService?.addNewVPNProfile(name, editable, config.toString())
                mService?.startProfile(profile?.mUUID)
            } else mService?.startVPN(config.toString())
//            checkRunning()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        Toast.makeText(this, "Profile started/added", Toast.LENGTH_LONG).show()
    }

    private var mStartUUID: String? = null
    private fun listVPNs() {
        try {
            val list = mService!!.profiles
            var all = "List:"
            for (vp in list.subList(0, Math.min(5, list.size))) {
                all = """
                $all${vp.mName}:${vp.mUUID}
                
                """.trimIndent()
            }
            if (list.size > 5) all += "\n And some profiles...."
            if (list.size > 0) {
                mStartUUID = list[0].mUUID
                prepareStartProfile(START_PROFILE_BYUUID)
            }

//            mHelloWorld.setText(all)
        } catch (e: RemoteException) {
            // TODO Auto-generated catch block
            Toast.makeText(this, e.message.toString(), Toast.LENGTH_LONG).show()
//            mHelloWorld.setText(e.message)
        }
    }


    @Throws(RemoteException::class)
    private fun prepareStartProfile(requestCode: Int) {
        val requestpermission = mService!!.prepareVPNService()
        if (requestpermission == null) {
            onActivityResult(requestCode, RESULT_OK, null)
        } else {
            // Have to call an external Activity since services cannot used onActivityResult
            startActivityForResult(requestpermission, requestCode)
        }
    }

    private val START_PROFILE_BYUUID = 3
    private val PROFILE_ADD_NEW = 8
    private val PROFILE_ADD_NEW_EDIT = 9

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == START_PROFILE_BYUUID) try {
                mService!!.startProfile(mStartUUID)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            if (requestCode == ICS_OPENVPN_PERMISSION) {
                listVPNs()
                try {
                    mService!!.registerStatusCallback(mCallback)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(
        UnknownHostException::class,
        IOException::class,
        RemoteException::class,
        IllegalArgumentException::class,
        IllegalAccessException::class,
        InvocationTargetException::class,
        NoSuchMethodException::class
    )
    fun getMyOwnIP(): String {
        val resp = java.lang.StringBuilder()
        val url = URL("https://icanhazip.com")
        val urlConnection = url.openConnection() as HttpURLConnection
        try {
            val tag = 9000 // Replace with a unique value representing your app or operation
            TrafficStats.setThreadStatsTag(tag)
            val `in` = BufferedReader(InputStreamReader(urlConnection.inputStream))
            while (true) {
                val line = `in`.readLine() ?: return resp.toString()
                resp.append(line)
            }
        } finally {
            TrafficStats.clearThreadStatsTag()
            urlConnection.disconnect()
        }
    }

    override fun onStop() {
        super.onStop()
        this.unbindService(mConnection)
    }
}