package com.proxai.hermes.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * WAKE-PHONE: this build has the wake engine, sherpa-onnx keyword spotting (Apache-2.0), with the English
 * GigaSpeech keyword model. Compiled only when app/libs/sherpa-onnx.aar is present and matches its pinned hash
 * (app/build.gradle.kts); the model files are in app/wake/assets/wake/ (scripts/wake-engine.sh puts them there).
 * Written against sherpa-onnx 2.0.1's Kotlin API (kotlin-api/KeywordSpotter.kt).
 */
object WakeEngines {
    const val AVAILABLE = true

    /** Round 2: no engine unless the keywords line is the one pinned shape: "@HEY_PROX" with its own threshold. */
    fun create(context: Context): WakeEngine? = runCatching {
        val line = context.assets.open("wake/keywords.txt").bufferedReader().use { it.readText() }
        if (!com.proxai.hermes.data.KeywordLine.valid(line)) null else SherpaWakeEngine(context)
    }.getOrNull()
}

/** "Hey Prox" by sherpa-onnx, all on the phone: the sound and the model never leave it. */
class SherpaWakeEngine(context: Context) : WakeEngine {
    override val sampleRate = 16_000
    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DIR/encoder.onnx",
                    decoder = "$DIR/decoder.onnx",
                    joiner = "$DIR/joiner.onnx",
                ),
                tokens = "$DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            keywordsFile = "$DIR/keywords.txt",
        ),
    )
    private var stream: OnlineStream = spotter.createStream()

    @Synchronized
    override fun accept(samples: FloatArray, count: Int): Boolean {
        val part = if (count == samples.size) samples else samples.copyOf(count)
        stream.acceptWaveform(part, sampleRate)
        if (part !== samples) part.fill(0f)   // the short copy doesn't outlive this call either
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) return true
        }
        return false
    }

    /** A new stream: the old one, with whatever sound it still held, is freed. */
    @Synchronized
    override fun reset() {
        stream.release()
        stream = spotter.createStream()
    }

    @Synchronized
    override fun close() {
        stream.release()
        spotter.release()
    }

    private companion object {
        const val DIR = "wake"
    }
}
