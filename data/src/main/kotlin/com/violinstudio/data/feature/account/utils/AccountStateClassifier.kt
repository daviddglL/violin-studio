package com.violinstudio.data.feature.account.utils

enum class AccountState { EXISTS, GONE, UNKNOWN }

object AccountStateClassifier {
    fun classify(reloadError: Throwable?): AccountState = AccountState.UNKNOWN
}
