package com.silf.app.llm

interface TokenCallback {
    fun onToken(text: String)
    fun onComplete()
    fun onError(message: String)
}
