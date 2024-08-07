package org.muilab.notigpt.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.zqc.opencc.android.lib.ChineseConverter
import com.zqc.opencc.android.lib.ConversionType
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.muilab.notigpt.R
import org.muilab.notigpt.model.NotiUnit
import org.muilab.notigpt.util.getDisplayTimeStr
import org.muilab.notigpt.util.getNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes

class GPTService: Service() {

    private var openAI: OpenAI
//    val model = "gpt-3.5-turbo"
    val model = "gpt-4"
    private val dotenv = dotenv {
        directory = "./assets"
        filename = "env"
    }
    private val apiKey = dotenv["GPT_KEY"]
    private val MAX_TOKEN = 5000

    // GPT Prompts
    private lateinit var summaryLeadPrompt: String
    private lateinit var summaryEndPrompt: String
    private lateinit var categoryLeadPrompt: String
    private lateinit var categoryEndPrompt: String
    private lateinit var sortLeadPrompt: String
    private lateinit var sortEndPrompt: String

    private val binder = GPTBinder()

    init {
        openAI = run {
            val config = OpenAIConfig(
                apiKey,
                timeout = Timeout(15.minutes, 20.minutes, 25.minutes)
            )
            OpenAI(config)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        summaryLeadPrompt = getString(R.string.summary_lead_prompt)
        summaryEndPrompt = getString(R.string.summary_end_prompt)
        categoryLeadPrompt = getString(R.string.category_lead_prompt)
        categoryEndPrompt = getString(R.string.category_end_prompt)
        sortLeadPrompt = getString(R.string.sort_lead_prompt)
        sortEndPrompt = getString(R.string.sort_end_prompt)
        return START_STICKY
    }

    private fun getPostContent(notifications: ArrayList<NotiUnit>): ArrayList<String> {

        val notiChunks = arrayListOf<String>()

        val chunkSb = StringBuilder()
        val notiSb = StringBuilder()
        notifications.forEach { noti ->

            notiSb.append("<whole_noti>\n\n")

            notiSb.append("<id>${noti.hashKey}</id>\n")
            // First Line: App & Title (If title is consistent)
            notiSb.append("<app>${noti.appName}</app>\n")

            val titlesIdentical = (noti.notiInfos + noti.prevNotiInfos)
                .map { it.title }
                .filter { it.isNotBlank() }
                .toSet().size == 1
            val notiType = if (noti.isPeople) "message" else "info"
            val notiTypeTitle = if (noti.isPeople) "sender" else "title"

            notiSb.append("<overall_$notiTypeTitle>${org.muilab.notigpt.util.replaceChars(noti.title)}</overall_$notiTypeTitle>\n\n")

            val prevNotiInfos = noti.prevNotiInfos
            if (prevNotiInfos.size > 0) {
                notiSb.append("<previous_${notiType}s\n")
                prevNotiInfos.forEach {
                    notiSb.append("<$notiType>\n")
                    notiSb.append("<time>${getDisplayTimeStr(it.time)}</time>")
                    if (!titlesIdentical)
                        notiSb.append("<$notiTypeTitle>${org.muilab.notigpt.util.replaceChars(it.title)}</$notiTypeTitle>")
                    notiSb.append("<content>${org.muilab.notigpt.util.replaceChars(it.content)}</content>\n")
                    notiSb.append("<$notiType>\n")
                }
                notiSb.append("</previous_${notiType}s>\n\n")
                notiSb.append("<new_${notiType}s>\n")
            }

            val notiInfos = noti.notiInfos

            notiInfos.forEach {
                notiSb.append("<$notiType>\n")
                notiSb.append("<time>${getDisplayTimeStr(it.time)}</time>")
                if (!titlesIdentical)
                    notiSb.append("<$notiTypeTitle>${org.muilab.notigpt.util.replaceChars(it.title)}</$notiTypeTitle>")
                notiSb.append("<content>${org.muilab.notigpt.util.replaceChars(it.content)}</content>\n")
                notiSb.append("<$notiType>\n")
            }
            if (prevNotiInfos.size > 0)
                notiSb.append("</new_${notiType}s>\n")
            notiSb.append("\n</whole_noti>\n\n\n")

            if (chunkSb.length + notiSb.length > MAX_TOKEN) {
                notiChunks.add(chunkSb.toString())
                chunkSb.clear()
            }
            chunkSb.append(notiSb.toString())
            notiSb.clear()
        }
        if (chunkSb.isNotEmpty())
            notiChunks.add(chunkSb.toString())

        return notiChunks
    }

    suspend fun makeRequest(requestType: String): String {

        return withContext(Dispatchers.IO) {

            val notifications = getNotifications(applicationContext)
            val notiChunks = getPostContent(notifications)

            val multiDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
            val deferredResults = mutableListOf<Deferred<String>>()

            val getLocalTimeString = {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.getDefault())
                "The current time is ${sdf.format(Date())}."
            }

            notiChunks.forEach { notiChunk ->

                val deferred = async(multiDispatcher) {
                    var responseStr: String = ""
                    val chatCompletionRequest = ChatCompletionRequest(
                        model = ModelId(model),
                        messages = listOf(
                            ChatMessage(
                                role = ChatRole.System,
                                content = when (requestType) {
                                    "categorize" -> categoryLeadPrompt
                                    "sort" -> sortLeadPrompt
                                    "summarize" -> summaryLeadPrompt
                                    else -> ""
                                }
                            ),
                            ChatMessage(
                                role = ChatRole.System,
                                content = getLocalTimeString()
                            ),
                            ChatMessage(
                                role = ChatRole.User,
                                content = notiChunk
                            ),
                            ChatMessage(
                                role = ChatRole.System,
                                content = when (requestType) {
                                    "categorize" -> categoryEndPrompt
                                    "sort" -> sortEndPrompt
                                    "summarize" -> summaryEndPrompt
                                    else -> ""
                                }
                            )
                        )
                    )

                    try {
                        val response = openAI.chatCompletion(chatCompletionRequest)
                        val message = response.choices.first().message
                        val finishReason = response.choices.first().finishReason
                        val responseText =
                            message.content?.replace("\\n", "\r\n")?.replace("\\", "")
                                ?.removeSurrounding("\"")
                        responseStr += ChineseConverter.convert(
                            responseText,
                            ConversionType.S2TWP,
                            applicationContext
                        )
                    } catch (e: OpenAIAPIException) {
                        Log.d("Error", e.stackTraceToString())
                        responseStr = e.error.detail?.message.toString()
                    } catch (e: Exception) {
                        Log.d("Error", e.stackTraceToString())
                        responseStr = "Unknown"
                    }
                    responseStr
                }

                deferredResults.add(deferred)
            }
            val finalResponse = deferredResults.awaitAll().joinToString(separator = "\n")
            finalResponse
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class GPTBinder : Binder() {
        fun getService(): GPTService {
            return this@GPTService
        }
    }
}