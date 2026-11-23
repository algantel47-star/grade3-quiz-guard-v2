package com.edu.grade3guard

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class QuizGuardService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var questionsList = JSONArray()
    private var currentIndex = 0
    private var wrongCount = 0

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val WATCH_INTERVAL_MS = 5 * 60 * 1000L
    private val LOCKOUT_DURATION_MS = 15 * 60 * 1000L

    private val showQuizRunnable = Runnable {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            showQuizOverlay()
        } else {
            scheduleNextQuiz(WATCH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initTextToSpeech()
        loadQuestions()
        startForegroundNotification()
        scheduleNextQuiz(WATCH_INTERVAL_MS)
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val arLocale = Locale("ar")
                val res = tts?.setLanguage(arLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(0.95f)
                isTtsReady = true
            }
        }
    }

    private fun speakError(spokenText: String) {
        if (isTtsReady) {
            tts?.stop()
            tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "error_feedback")
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
    }

    private fun startForegroundNotification() {
        val channelId = "quiz_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "حارس المذاكرة - أحمد فريد",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("حارس المذاكرة (م. أحمد فريد)")
            .setContentText("يتم عرض سؤال كل 5 دقائق من الاستخدام")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun loadQuestions() {
        val prefs = getSharedPreferences("GuardPrefs", Context.MODE_PRIVATE)
        currentIndex = prefs.getInt("q_index", 0)
        wrongCount = prefs.getInt("wrong_count", 0)

        val inputStream = assets.open("questions.json")
        val jsonText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        questionsList = JSONArray(jsonText)
    }

    private fun scheduleNextQuiz(delayMs: Long) {
        handler.removeCallbacks(showQuizRunnable)
        handler.postDelayed(showQuizRunnable, delayMs)
    }

    private fun pauseBackgroundAudio() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    }

    private fun showQuizOverlay() {
        if (overlayView != null || questionsList.length() == 0) return

        pauseBackgroundAudio()

        val prefs = getSharedPreferences("GuardPrefs", Context.MODE_PRIVATE)
        val lockUntil = prefs.getLong("lock_until", 0L)
        val now = System.currentTimeMillis()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F4F7FB"))
            setPadding(40, 40, 40, 40)
        }

        if (now < lockUntil) {
            val lastPage = prefs.getString("last_page", "الكتاب المدرسي") ?: "الكتاب المدرسي"
            buildLockoutUI(root, lockUntil - now, lastPage)
        } else {
            buildQuestionUI(root)
        }

        overlayView = root
        windowManager.addView(overlayView, params)
    }

    private fun buildQuestionUI(root: LinearLayout) {
        stopSpeaking()
        root.removeAllViews()
        val prefs = getSharedPreferences("GuardPrefs", Context.MODE_PRIVATE)

        if (currentIndex >= questionsList.length()) currentIndex = 0
        val qObj = questionsList.getJSONObject(currentIndex)

        val subject = qObj.getString("subject")
        val questionText = qObj.getString("question")
        val options = qObj.getJSONArray("options")
        val correctIndex = qObj.getInt("correctIndex")
        val explanation = qObj.getString("explanation")
        val pageRef = qObj.getString("page")
        val iconEmoji = qObj.optString("icon", "📚")

        val header = TextView(this).apply {
            text = "المادة: $subject   |   الأخطاء: $wrongCount / 5"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E88E5"))
                cornerRadius = 24f
            }
        }

        val tvQuestion = TextView(this).apply {
            text = questionText
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 16)
        }

        val visualCard = TextView(this).apply {
            text = iconEmoji
            textSize = 54f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E3F2FD"))
                cornerRadius = 32f
                setStroke(3, Color.parseColor("#90CAF9"))
            }
        }

        val tvFeedback = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#C62828"))
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFEBEE"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#EF9A9A"))
            }
            visibility = View.GONE
        }

        root.addView(header)
        root.addView(tvQuestion)
        root.addView(visualCard)
        root.addView(tvFeedback)

        val optionButtons = mutableListOf<Button>()
        val labels = listOf("أ", "ب", "ج")

        for (i in 0 until options.length()) {
            val btn = Button(this).apply {
                text = "${labels[i]})  ${options.getString(i)}"
                textSize = 18f
                setTextColor(Color.parseColor("#212121"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 14, 0, 14) }
                layoutParams = lp
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 24f
                    setStroke(3, Color.parseColor("#BBDEFB"))
                }

                setOnClickListener {
                    currentIndex++
                    prefs.edit().putInt("q_index", currentIndex).apply()

                    if (i == correctIndex) {
                        stopSpeaking()
                        Toast.makeText(this@QuizGuardService, "أحسنت! إجابة صحيحة 🎉 استمتع بـ 5 دقائق", Toast.LENGTH_SHORT).show()
                        removeOverlay()
                        scheduleNextQuiz(WATCH_INTERVAL_MS)
                    } else {
                        wrongCount++
                        prefs.edit().putInt("wrong_count", wrongCount).putString("last_page", pageRef).apply()

                        if (wrongCount >= 5) {
                            val lockUntilTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                            prefs.edit().putLong("lock_until", lockUntilTime).apply()
                            speakError("لقد أخطأت في 5 أسئلة. سيتوقف الهاتف لمدة 15 دقيقة لمراجعة $pageRef")
                            buildLockoutUI(root, LOCKOUT_DURATION_MS, pageRef)
                        } else {
                            val correctText = options.getString(correctIndex)
                            tvFeedback.text = "❌ إجابة خاطئة!\nالصحيح هو: ($correctText)\n💡 $explanation\n📖 راجع: $pageRef"
                            tvFeedback.visibility = View.VISIBLE

                            // قفل أزرار الاختيارات أثناء مراجعة الخطأ وعدم الانتقال تلقائياً
                            optionButtons.forEach { b -> b.isEnabled = false }

                            speakError("إجابة خاطئة. الإجابة الصحيحة هي $correctText. $explanation. راجع $pageRef")

                            val btnRetry = Button(this@QuizGuardService).apply {
                                text = "حاول تاني 🔄"
                                textSize = 19f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                                val lpBtn = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(0, 20, 0, 8) }
                                layoutParams = lpBtn
                                setPadding(24, 24, 24, 24)
                                background = GradientDrawable().apply {
                                    setColor(Color.parseColor("#1565C0"))
                                    cornerRadius = 24f
                                }
                                setOnClickListener {
                                    stopSpeaking()
                                    if (overlayView != null) buildQuestionUI(root)
                                }
                            }

                            val btnRepeatVoice = Button(this@QuizGuardService).apply {
                                text = "🔊 اسمع الشرح تاني"
                                textSize = 16f
                                setTextColor(Color.parseColor("#1565C0"))
                                val lpSpeak = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(0, 8, 0, 8) }
                                layoutParams = lpSpeak
                                setPadding(18, 18, 18, 18)
                                background = GradientDrawable().apply {
                                    setColor(Color.parseColor("#E3F2FD"))
                                    cornerRadius = 20f
                                    setStroke(2, Color.parseColor("#90CAF9"))
                                }
                                setOnClickListener {
                                    speakError("الإجابة الصحيحة هي $correctText. $explanation. راجع $pageRef")
                                }
                            }

                            root.addView(btnRetry)
                            root.addView(btnRepeatVoice)
                        }
                    }
                }
            }
            optionButtons.add(btn)
            root.addView(btn)
        }
    }

    private fun buildLockoutUI(root: LinearLayout, remainingMs: Long, pageRef: String) {
        root.removeAllViews()
        root.setBackgroundColor(Color.parseColor("#FFF3E0"))

        val icon = TextView(this).apply {
            text = "📖⏳"
            textSize = 64f
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "توقف الهاتف لمدة 15 دقيقة!"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
        }

        val msg = TextView(this).apply {
            text = "لقد أخطأت في 5 أسئلة.\nمن فضلك افتح الكتاب وراجع:\n👈 $pageRef 👉"
            textSize = 20f
            setTextColor(Color.parseColor("#3E2723"))
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 32)
        }

        val timerText = TextView(this).apply {
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D84315"))
            gravity = Gravity.CENTER
        }

        root.addView(icon)
        root.addView(title)
        root.addView(msg)
        root.addView(timerText)

        object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = (millisUntilFinished / 1000) / 60
                val secs = (millisUntilFinished / 1000) % 60
                timerText.text = String.format("الوقت المتبقي: %02d:%02d", mins, secs)
            }

            override fun onFinish() {
                val prefs = getSharedPreferences("GuardPrefs", Context.MODE_PRIVATE)
                wrongCount = 0
                prefs.edit().putInt("wrong_count", 0).putLong("lock_until", 0L).apply()
                if (overlayView != null) {
                    buildQuestionUI(root)
                }
            }
        }.start()
    }

    private fun removeOverlay() {
        stopSpeaking()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        handler.removeCallbacks(showQuizRunnable)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onBind(intent: Intent?) = null
}
