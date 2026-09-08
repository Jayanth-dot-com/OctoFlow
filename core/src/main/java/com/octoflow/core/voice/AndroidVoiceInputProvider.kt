package com.octoflow.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.octoflow.core.contract.VoiceInputProvider
import com.octoflow.core.contract.VoiceResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android SpeechRecognizer implementation for voice input
 */
class AndroidVoiceInputProvider(private val context: Context) : VoiceInputProvider {
    
    companion object {
        private const val TAG = "VoiceInput"
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionChannel: Channel<VoiceResult>? = null
    private var _isListening = false
    
    override val isListening: Boolean
        get() = _isListening
    
    override suspend fun startListening(): Flow<VoiceResult> {
        return callbackFlow {
            val channel = Channel<VoiceResult>(Channel.UNLIMITED)
            recognitionChannel = channel
            _isListening = true
            
            withContext(Dispatchers.Main) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e(TAG, "Speech recognition not available on device")
                    channel.trySend(VoiceResult("", 0f, true))
                    return@withContext
                }
                
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "onReadyForSpeech")
                        }
                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "onBeginningOfSpeech")
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            Log.d(TAG, "onEndOfSpeech")
                        }
                        
                        override fun onError(error: Int) {
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                                SpeechRecognizer.ERROR_SERVER -> "Server error"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                                else -> "Unknown error: $error"
                            }
                            Log.e(TAG, "Speech error: $errorMsg ($error)")
                            channel.trySend(VoiceResult("", 0f, true))
                        }
                        
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            Log.d(TAG, "onResults: $matches")
                            matches?.firstOrNull()?.let { text ->
                                val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 0.8f
                                channel.trySend(VoiceResult(text, confidence, true))
                            } ?: run {
                                channel.trySend(VoiceResult("", 0f, true))
                            }
                        }
                        
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { text ->
                                channel.trySend(VoiceResult(text, 0.5f, false))
                            }
                        }
                        
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    }
                    
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start speech recognition", e)
                    channel.trySend(VoiceResult("", 0f, true))
                }
            }
            
            // Forward results to flow
            for (result in channel) {
                trySend(result)
                if (result.isFinal) {
                    break
                }
            }
            close()
            
            awaitClose {
                _isListening = false
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.destroy()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error cleaning up SpeechRecognizer", e)
                    } finally {
                        speechRecognizer = null
                    }
                }
                recognitionChannel = null
            }
        }
    }
    
    override suspend fun stopListening() {
        _isListening = false
        withContext(Dispatchers.Main.immediate) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer", e)
            } finally {
                speechRecognizer = null
            }
        }
        recognitionChannel?.close()
        recognitionChannel = null
    }
}