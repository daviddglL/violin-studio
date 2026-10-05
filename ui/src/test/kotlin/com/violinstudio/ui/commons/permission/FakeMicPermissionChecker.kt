package com.violinstudio.ui.commons.permission

class FakeMicPermissionChecker(var granted: Boolean = false, var rationale: Boolean = false) : MicPermissionChecker {
    override fun isGranted() = granted

    override fun shouldShowRationale() = rationale
}
