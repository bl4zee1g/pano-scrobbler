package com.arn.scrobble.utils

import com.arn.scrobble.BuildVariant

/**
 * Safe to use in any process, unlike VariantStuff, which instantiates the billing repository.
 * Intentionally not a const, so that it is not inlined into the modules that use it.
 */
object AndroidBuildVariant {
    val isPlay: Boolean = BuildVariant.IS_PLAY
}
