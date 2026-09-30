package com.violinstudio.domain.feature.health.failure

class ServerUnavailableException(val status: String) :
    IllegalStateException("El servidor respondió con estado '$status'")
