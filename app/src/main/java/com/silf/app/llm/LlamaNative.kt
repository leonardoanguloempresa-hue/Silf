package com.silf.app.llm

class LlamaNative {
    external fun loadModel(modelPath: String, useMmap: Boolean, threads: Int, contextSize: Int): Long
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temp: Float, callback: TokenCallback)
    external fun cancel(handle: Long)
    external fun unload(handle: Long)

    companion object {
        init {
            System.loadLibrary("silf_llama")
        }
    }
}
