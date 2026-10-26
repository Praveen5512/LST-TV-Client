package com.localserver.tv.ui.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.localserver.tv.R

class MainTvActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainTvFragment())
                .commitNow()
        }
    }
}
