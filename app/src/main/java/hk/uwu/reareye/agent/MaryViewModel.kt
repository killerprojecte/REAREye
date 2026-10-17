package hk.uwu.reareye.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hk.uwu.reareye.agent.tools.ReareyeToolEnvironment

/** Activity-scoped owner: changing tabs must not destroy an outstanding question or tool turn. */
class MaryViewModel(application: Application) : AndroidViewModel(application) {
    val controller =
        MaryController(application, viewModelScope, ReareyeToolEnvironment(application))
}
