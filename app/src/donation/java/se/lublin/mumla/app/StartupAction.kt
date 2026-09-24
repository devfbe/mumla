package se.lublin.mumla.app

import android.app.Activity

class StartupAction : IStartupAction {
    override fun execute(activity: Activity) {
        maybeShowNewsDialog(activity)
    }
}
