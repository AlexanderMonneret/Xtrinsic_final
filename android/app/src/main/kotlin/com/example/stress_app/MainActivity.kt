package com.example.stress_app
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.util.Log
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*


class MainActivity: FlutterActivity() {
    private var scores: List<StressScore> = mutableListOf()
    private lateinit var scoreslt: List<StressScore>
    private val STILA_PROVIDER_CONTENT_URI: Uri = Uri.parse("content://lmu.pms.stila.provider/stressscore")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getStressScore()

    }

    private val projection: Array<String> = arrayOf(
        StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_TIMESTAMP,    // stressscore table primary key timestamp
        // StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_MODEL_ID,     // stressscore model id, which shall be 0
        StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_STRESS_SCORE, // stressscore between 0 and 100
    )
    private val selectionClause: String =
        "${StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_TIMESTAMP}>=? AND " +
                "${StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_TIMESTAMP}<=? AND " +
                "${StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_MODEL_ID}=?"

    private val ASC = "ASC" // increase order
    private val DESC = "DESC" // decrease order
    private val sortOrder = "${StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_TIMESTAMP} ${DESC}"

    //###############################################################################
    private var mTTS: TextToSpeech? = null
    private  lateinit var channel:MethodChannel
    private val BATTERY_CHANNEL = "stress_app/battery"
    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine){
        super.configureFlutterEngine(flutterEngine);

        // BinaryMessenger messenger = flutterEngine.getDartExecutor().getBinaryMessenger();
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BATTERY_CHANNEL).setMethodCallHandler {
            // Note: this method is invoked on the main thread.
                call, result ->
            if (call.method == "getBatteryLevel") {
                val batteryLevel = getBatteryLevel()

                if (batteryLevel != -1) {
                    result.success(batteryLevel)
                } else {
                    result.error("UNAVAILABLE", "Battery level not available.", null)
                }
            } else {
                result.notImplemented()
            }
        }

    }




    private fun getBatteryLevel(): Int {
        //val intento = ContextWrapper(applicationContext).registerReceiver(null, IntentFilter(Intent.ACTION_VOICE_COMMAND))
        //if (intento != null) {
        //    intento.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        //}

        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        applicationContext.startActivity(intent)

        val batteryLevel: Int
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            val intent = ContextWrapper(applicationContext).registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryLevel = intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        }

        return batteryLevel
    }
    //#####################################################################


    /**
     * @param beginUTCts Begin timestamp (UTC Timestamp in Seconds)
     * @param endUTCts End timestamp (UTC Timestamp in Seconds)
     */
    private fun fetchStressScores(beginUTCts: Long, endUTCts: Long): List<StressScore> {
        // https://developer.android.com/guide/topics/providers/content-provider-basics#kotlin

        var selectionArgs: Array<String> = arrayOf(
            beginUTCts.toString(),
            endUTCts.toString(),
            StilaStressScoreContract.ComputedStressEntry.BASELINE_STRESS_MODEL_ID
        )
        val mutableList = mutableListOf<StressScore>()

        // https://developer.android.com/guide/topics/providers/content-provider-basics#kotlin
        val mCursor = contentResolver.query(
            STILA_PROVIDER_CONTENT_URI, // The content URI of the stila stressscore table
            projection, // The columns to return for each row
            selectionClause, // Selection criteria
            selectionArgs, // Selection criteria
            sortOrder
        )

        mCursor?.use {cursor ->
            // Determine the column index of the column named "timestamp"
            val tsIdx: Int = cursor.getColumnIndex(StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_TIMESTAMP)
            val scoreIdx: Int = cursor.getColumnIndex(StilaStressScoreContract.ComputedStressEntry.COLUMN_NAME_STRESS_SCORE)

            while (cursor.moveToNext()) {
                // added new stress score to the list
                mutableList.add(
                    StressScore(cursor.getLong(tsIdx), cursor.getInt(scoreIdx))
                )
            }
            // resource is closed by use block automatically, inside the apply block you will need to close resource manually
            // mCursor.close()
        }

        return mutableList.toList()
    }
    suspend fun fetchStilaStressScores(beginUTCts: Long, endUTCts: Long): List<StressScore> {
        var listReturn : List<StressScore>?
        listReturn =  withContext(Dispatchers.IO) {
            fetchStressScores(beginUTCts, endUTCts)
        }
        return listReturn
    }

    data class StressScore(val timestamp: Long, val stressscore: Int)

    private fun getStressScore(): Int {
        //val intento = ContextWrapper(applicationContext).registerReceiver(null, IntentFilter(Intent.ACTION_VOICE_COMMAND))
        //if (intento != null) {
        //    intento.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        //}
        var scoresString: String = ""
        var lastScore: Int = 0
        val curUtcTimestampInSecs = System.currentTimeMillis() / 1000L
        val aDayBefore = curUtcTimestampInSecs - (24 * 60 * 60)
        scores = fetchStressScores(aDayBefore, curUtcTimestampInSecs)

        //CoroutineScope(Dispatchers.IO).launch {
        //    scoresString = fetchStilaStressScores(aDayBefore, curUtcTimestampInSecs).toString()
        //}
        Log.d("STILA", scoresString)
        if (!scores.isEmpty()) {
            lastScore = scores.first().stressscore

            Log.d("STILA: ", "type in last score: $lastScore")
        }else{
            Log.d("STILA", "Empty ${scores.toString()}")
        }

        return lastScore
    }
}

