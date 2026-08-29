package com.redundo.obddiscover

/**
 * Visible build marker, so "did the new build install?" is answerable at a glance.
 *
 * DERIVED FROM THE COMMIT, NOT TYPED. This was a hand-edited constant and it went stale
 * for a full day of builds, each one announcing the commit before it -- so the screen said
 * an old build was installed when a new one was, which is worse than showing nothing.
 */
object BuildTag {
    val ID: String = BuildConfig.BUILD_TAG
}
