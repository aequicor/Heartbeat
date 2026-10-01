package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.OllamaMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectWizardPresentationTest {
    @Test
    fun `method step selects the first method with its defaults and keeps an explicit choice`() {
        val choosing = ConnectWizardState.ChoosingMethod(engineInfo())
        val first = ConnectWizardScreenState().reflect(choosing)
        assertEquals(WizardStep.Method, first.step)
        assertEquals(ApiKeyMethod.id.value, first.selectedMethod)
        assertEquals(CredentialForm("OpenAI", "https://api.openai.com"), first.form)
        val edited = first.copy(
            selectedMethod = OllamaMethod.id.value,
            form = CredentialForm("Home", "http://nas:11434"),
        )
        val again = edited.reflect(ConnectWizardState.Connecting(engineInfo(), OllamaMethod.id))
        assertEquals(OllamaMethod.id.value, again.selectedMethod)
        assertEquals("Home", again.form.label)
        assertTrue(again.isBusy)
    }

    @Test
    fun `api key is required and trimmed the name falls back to the provider`() {
        assertEquals(
            FormCheck.Invalid(FormError.MissingKey),
            CredentialForm(key = SecretText(" ")).toRequest(ApiKeyMethod),
        )
        val valid = assertIs<FormCheck.Valid>(CredentialForm(key = SecretText(" sk-1 ")).toRequest(ApiKeyMethod))
        val credential = assertIs<CredentialInput.ApiKey>(valid.credential)
        assertEquals("OpenAI", credential.label)
        assertEquals(ApiKeyMethod.origin, credential.origin)
        credential.key.use { key -> key.reveal { assertEquals("sk-1", it.concatToString()) } }
    }

    @Test
    fun `an editable host is canonicalized or rejected`() {
        val lan = CredentialForm("NAS", "HTTP://NAS.local:11434/").toRequest(OllamaMethod)
        assertEquals(
            FormCheck.Valid(CredentialInput.Existing("NAS", EndpointOrigin("http://nas.local:11434"))),
            lan,
        )
        assertEquals(
            FormCheck.Invalid(FormError.InvalidOrigin),
            CredentialForm("NAS", "nas.local/api").toRequest(OllamaMethod),
        )
    }

    @Test
    fun `typed keys never appear in the screen state text`() {
        val state = ConnectWizardScreenState(form = CredentialForm(key = SecretText("sk-secret")))
        assertFalse(state.toString().contains("sk-secret"))
    }

    @Test
    fun `failures map to localizable categories`() {
        val auth = EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))
        assertEquals(FailureUi.Authentication, auth.toUi())
        assertEquals(FailureUi.Unknown, EngineFailure.Unknown().toUi())
    }

    @Test
    fun `a compatible method keeps the base path of a full URL`() {
        val method = CompatibleProtocol.OpenAI.method
        val valid = assertIs<FormCheck.Valid>(
            CredentialForm("Router", " https://OpenRouter.ai/api/v1/ ", SecretText("sk-1")).toRequest(method),
        )
        val credential = assertIs<CredentialInput.ApiKey>(valid.credential)
        assertEquals(EndpointOrigin("https://openrouter.ai"), credential.origin)
        assertEquals("/api/v1", credential.basePath)
        credential.key.close()
        assertEquals(
            FormCheck.Invalid(FormError.InvalidOrigin),
            CredentialForm("NAS", "http://nas.local:11434/api").toRequest(OllamaMethod),
        )
    }

    @Test
    fun `a compatible method refuses plain HTTP off the loopback interface`() {
        val method = CompatibleProtocol.Anthropic.method
        assertEquals(
            FormCheck.Invalid(FormError.InsecureOrigin),
            CredentialForm("LAN", "http://192.168.1.10:8000/v1", SecretText("sk-1")).toRequest(method),
        )
        val local = assertIs<FormCheck.Valid>(
            CredentialForm("Local", "http://127.0.0.1:4000", SecretText("sk-1")).toRequest(method),
        )
        assertIs<CredentialInput.ApiKey>(local.credential).key.close()
    }
}
