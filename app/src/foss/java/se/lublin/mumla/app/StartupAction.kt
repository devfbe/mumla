package se.lublin.mumla.app

import android.app.Activity
import se.lublin.mumla.ui.maybeShowNewsDialog

class StartupAction : IStartupAction {
    override fun execute(activity: Activity) {
        maybeShowNewsDialog(activity)
    }
}
