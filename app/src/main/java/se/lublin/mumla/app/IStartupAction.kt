package se.lublin.mumla.app

import android.app.Activity

/** What a flavor does once when the main screen starts (news, donation prompts). */
fun interface IStartupAction {
    fun execute(activity: Activity)
}
