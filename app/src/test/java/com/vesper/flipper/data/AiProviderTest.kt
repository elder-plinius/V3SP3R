package com.vesper.flipper.data

import org.junit.Assert.*
import org.junit.Test

class AiProviderTest {
    @Test fun `exact model identifiers are validated never repaired`() {
        val helper = runCatching { Class.forName("com.vesper.flipper.data.AiProviderKt").getMethod("modelIdValidationError", String::class.java) }.getOrNull()
        assertNotNull("Shared model validation must exist", helper)
        for (id in listOf("Vendor/Exact-Model:free", "Custom_Model.V2", "org/model@rev")) {
            assertNull(helper!!.invoke(null, id))
        }
        for (id in listOf("", " model", "model ", "two words", "x\n", "x\u0000", "https://host/model", "\"model\"", "'model'", "Model\u200BID")) {
            assertNotNull("Reject malformed pasted model ID: $id", helper!!.invoke(null, id))
        }
    }

    @Test fun `connection snapshot hides secrets and accepts generic bearer tokens`() {
        val configClass = runCatching { Class.forName("com.vesper.flipper.data.AiConnectionConfig") }.getOrNull()
        assertNotNull("Connection config must be captured atomically", configClass)
        val constructor = configClass!!.getConstructor(AiProvider::class.java, String::class.java, String::class.java)
        val validate = configClass.getMethod("validationError")
        val valid = constructor.newInstance(AiProvider.ORCAROUTER, "Fake_Any-Vendor.123", "Vendor/MixedCase:free")
        assertNull(validate.invoke(valid))
        assertFalse(valid.toString().contains("Fake_Any-Vendor.123"))
        for (key in listOf(null, "", " secret", "secret ", "a\nb", "a\tb", "a b")) {
            assertNotNull(validate.invoke(constructor.newInstance(AiProvider.OPENROUTER, key, "model")))
        }
    }

    @Test fun `provider configuration contract exists`() {
        val type = runCatching { Class.forName("com.vesper.flipper.data.AiProvider") }.getOrNull()
        assertNotNull("Provider identity must be explicit instead of implicit OpenRouter", type)
        assertEquals(listOf("OPENROUTER", "ORCAROUTER", "ABLITERATED"), type!!.enumConstants.map { it.toString() })
    }
}
