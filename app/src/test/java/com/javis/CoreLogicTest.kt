package com.javis

import com.javis.ai.AIResponse
import com.javis.ai.MockBackend
import com.javis.ai.AIRequest
import com.javis.commands.CommandRouter
import com.javis.commands.JavisCommand
import com.javis.security.CommandValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommandRouterTest {

    @Test
    fun `plain response routes to PlainResponse`() {
        val response = AIResponse(type = "response", message = "Hello!")
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.PlainResponse)
        assertEquals("Hello!", (command as JavisCommand.PlainResponse).message)
    }

    @Test
    fun `allowed action routes to correct command`() {
        val response = AIResponse(type = "action", action = "open_app", target = "WhatsApp")
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.OpenApp)
        assertEquals("WhatsApp", (command as JavisCommand.OpenApp).appNameOrPackage)
    }

    @Test
    fun `disallowed action is rejected as Unsupported`() {
        val response = AIResponse(type = "action", action = "run_shell_command", target = "rm -rf /")
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.Unsupported)
    }

    @Test
    fun `missing fields on action type produce Unsupported not a crash`() {
        val response = AIResponse(type = "action", action = null)
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.Unsupported)
    }

    @Test
    fun `unknown response type is Unsupported`() {
        val response = AIResponse(type = "mystery")
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.Unsupported)
    }

    @Test
    fun `suspicious target text is rejected`() {
        val response = AIResponse(
            type = "action", action = "open_app", target = "WhatsApp; rm -rf /"
        )
        val command = CommandRouter.route(response)
        assertTrue(command is JavisCommand.Unsupported)
    }
}

class CommandValidatorTest {
    @Test
    fun `allowlist accepts known actions`() {
        assertTrue(CommandValidator.isActionAllowed("open_app"))
        assertTrue(CommandValidator.isActionAllowed("get_time"))
    }

    @Test
    fun `allowlist rejects unknown actions`() {
        assertFalse(CommandValidator.isActionAllowed("delete_all_files"))
        assertFalse(CommandValidator.isActionAllowed(null))
    }

    @Test
    fun `safe text rejects shell metacharacters`() {
        assertFalse(CommandValidator.isSafeFreeText("hello; rm -rf /"))
        assertFalse(CommandValidator.isSafeFreeText("`whoami`"))
        assertTrue(CommandValidator.isSafeFreeText("Open WhatsApp please"))
    }
}

class MockBackendTest {
    @Test
    fun `mock backend understands open app pattern`() = runBlocking {
        val backend = MockBackend()
        val result = backend.process(AIRequest(message = "open whatsapp"))
        assertTrue(result.isSuccess)
        assertEquals("open_app", result.getOrNull()?.action)
    }

    @Test
    fun `mock backend handles empty input gracefully`() = runBlocking {
        val backend = MockBackend()
        val result = backend.process(AIRequest(message = ""))
        assertTrue(result.isSuccess)
        assertEquals("response", result.getOrNull()?.type)
    }

    @Test
    fun `mock backend recognizes calculator pattern`() = runBlocking {
        val backend = MockBackend()
        val result = backend.process(AIRequest(message = "25 * 8"))
        assertTrue(result.isSuccess)
        assertEquals("calculator", result.getOrNull()?.action)
    }
}
