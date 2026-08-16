package com.screentranslation.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.screentranslation.app.ui.UiStyleController

class TrustDocumentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        val assetName = intent.getStringExtra(EXTRA_ASSET_NAME)
            ?.takeIf(ALLOWED_ASSETS::contains)
            ?: run {
                finish()
                return
            }
        title = assetName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val document = assets.open("trust/$assetName").bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        val text = TextView(this).apply {
            this.text = document
            textSize = 16f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(20), dp(24), dp(20), dp(40))
            Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        }
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    text,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ASSET_NAME = "asset_name"
        private val ALLOWED_ASSETS = setOf(
            "PRIVACY.md",
            "SECURITY.md",
            "APACHE-2.0.txt",
            "THIRD_PARTY_NOTICES.md",
        )

        fun intent(context: Context, assetName: String): Intent {
            require(assetName in ALLOWED_ASSETS)
            return Intent(context, TrustDocumentActivity::class.java)
                .putExtra(EXTRA_ASSET_NAME, assetName)
        }
    }
}
