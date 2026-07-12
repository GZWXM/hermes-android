package com.hermes.client.data

sealed interface ChatError {
    data class Network(val message: String, val detail: String? = null) : ChatError
    data object Unauthorized : ChatError
    data class Server(val code: Int, val message: String) : ChatError
    data object Cancelled : ChatError
    data class Unknown(val message: String, val detail: String? = null) : ChatError
}
