package com.screentranslation.app.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedCloudServiceTest {
    @Test
    fun `managed protocol pins model and uses the verified Hy-MT2 prompt`() {
        val source = "キャッシュだけを削除してください。"
        val root = JSONObject(
            ManagedHyMt2ChatProtocol.buildRequestJson(
                sourceLanguage = "ja",
                targetLanguage = "zh",
                ocrText = source,
            ),
        )

        assertEquals(ManagedCloudService.PUBLIC_MODEL_ID, root.getString("model"))
        assertEquals(0, root.getInt("temperature"))
        assertEquals(1, root.getInt("top_k"))
        assertFalse(root.getBoolean("stream"))
        val messages = root.getJSONArray("messages")
        assertEquals(1, messages.length())
        val prompt = messages.getJSONObject(0).getString("content")
        assertTrue(prompt.startsWith("Translate the following text into Chinese."))
        assertTrue(prompt.endsWith(source))
    }

    @Test
    fun `managed mode accepts Chinese targets and rejects other targets`() {
        listOf("zh", "ZH-CN", "zh-Hans").forEach {
            ManagedCloudService.requireSupportedTarget(it)
        }
        assertTrue(
            runCatching { ManagedCloudService.requireSupportedTarget("en") }.isFailure,
        )
    }

    @Test
    fun `legacy user settings migrate to user API mode`() {
        assertEquals(
            OnlineProviderMode.USER_API,
            OnlineProviderMode.fromStoredValue(null, hasLegacyUserConfig = true),
        )
        assertEquals(
            OnlineProviderMode.MANAGED_CLOUD,
            OnlineProviderMode.fromStoredValue(null, hasLegacyUserConfig = false),
        )
    }
}
