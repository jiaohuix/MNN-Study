package com.k2fsa.sherpa.mnn

class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)

    companion object {
        init {
            // 与 OnlineRecognizer 同步：按依赖顺序加载，避免本类先被 class-init 时
            // sherpa-mnn-jni 因 MNN_Express 未在主命名空间而 dlopen 失败。
            System.loadLibrary("MNN")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("asrtest_shim")
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}
