package se.lublin.mumla.app

import android.content.Intent
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ActivityMainBinding
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper

/** The foss flavor's drawer has a donation row. */
@RunWith(RobolectricTestRunner::class)
class MainDrawerDonationTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val binding = ActivityMainBinding.inflate(activity.layoutInflater).also { activity.setContentView(it.root) }

    init {
        MainDrawer(activity, binding.drawerLayout, binding.leftDrawer, binding.toolbar, { null }, { })
    }

    @Test
    fun theDonationRowOpensTheDonationLink() {
        idleMainLooper()
        val list = binding.leftDrawer
        list.measure(0, 0)
        list.layout(0, 0, WIDTH, HEIGHT)
        val donate = (0 until list.childCount).map { list.getChildAt(it) }
            .single { (it as? TextView)?.text == activity.getString(R.string.donate_foss) }
        donate.performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.dataString).isEqualTo(activity.getString(R.string.donate_link_foss))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 4000
    }
}
