package com.gameocr.app.translate

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProbeClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesOpenAiAndAnthropicModelLists() {
        val openAi = """{"object":"list","data":[{"id":"gpt-b"},{"id":"gpt-a"},{"id":"gpt-a"}]}"""
        assertEquals(listOf("gpt-a", "gpt-b"), parseModelIds(openAi, json))
    }

    @Test fun parsesGeminiStyleAndBareArrays() {
        val gemini = """{"models":[{"name":"models/gemini-x"}]}"""
        assertEquals(listOf("gemini-x"), parseModelIds(gemini, json))
        assertEquals(listOf("a", "b"), parseModelIds("""["b","a"]""", json))
    }

    @Test fun garbageYieldsNoModels() {
        assertTrue(parseModelIds("<html>nope</html>", json).isEmpty())
        assertTrue(parseModelIds("""{"data":"x"}""", json).isEmpty())
    }

    @Test fun extractsErrorMessagesFromBothShapes() {
        assertEquals(
            "bad key",
            probeErrorDetail("""{"error":{"message":"bad key","type":"x"}}""", json),
        )
        assertEquals("plain", probeErrorDetail("""{"error":"plain"}""", json))
        assertEquals("not json", probeErrorDetail("not json", json))
    }

    @Test fun buildsBaseUrlsForBothProtocols() {
        assertEquals("https://h/v1", openAiProbeBase("https://h/v1/"))
        assertEquals("https://h/v1", anthropicProbeBase("https://h"))
        assertEquals("https://h/v1", anthropicProbeBase("https://h/v1/"))
    }
}
