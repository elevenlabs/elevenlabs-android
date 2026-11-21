package io.elevenlabs

import org.junit.Assert.*
import org.junit.Test

class ConversationOverridesBuilderTest {

    private fun textOnlyIn(config: ConversationConfig): Boolean =
        ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override")
            .getJSONObject("conversation")
            .getBoolean("text_only")

    @Test
    fun `top-level textOnly is sent to the server without an override`() {
        val config = ConversationConfig(agentId = "agent", textOnly = true)

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `text_only false is sent for voice sessions`() {
        val config = ConversationConfig(agentId = "agent")

        assertFalse(textOnlyIn(config))
    }

    @Test
    fun `conversation override is used when top-level textOnly is not set`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(conversation = ConversationOverrides(textOnly = true))
        )

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `top-level textOnly wins over a conflicting override`() {
        val config = ConversationConfig(
            agentId = "agent",
            textOnly = true,
            overrides = Overrides(conversation = ConversationOverrides(textOnly = false))
        )

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `other overrides are kept alongside text_only`() {
        val config = ConversationConfig(
            agentId = "agent",
            textOnly = true,
            overrides = Overrides(
                agent = AgentOverrides(firstMessage = "Hi"),
                tts = TtsOverrides(voiceId = "voice")
            )
        )

        val override = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override")
        assertEquals("Hi", override.getJSONObject("agent").getString("first_message"))
        assertEquals("voice", override.getJSONObject("tts").getString("voice_id"))
        assertTrue(override.getJSONObject("conversation").getBoolean("text_only"))
    }

    @Test
    fun `resolveTextOnly selects the text transport from the conversation override`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(conversation = ConversationOverrides(textOnly = true))
        )

        assertTrue(ConversationClientImpl.resolveTextOnly(config).textOnly)
    }

    @Test
    fun `resolveTextOnly keeps voice transport by default`() {
        val config = ConversationConfig(agentId = "agent")

        assertFalse(ConversationClientImpl.resolveTextOnly(config).textOnly)
    }

    @Test
    fun `tts speed override is sent`() {
        val config = ConversationConfig(agentId = "agent", overrides = Overrides(tts = TtsOverrides(speed = 1.2)))

        val tts = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override").getJSONObject("tts")
        assertEquals(1.2, tts.getDouble("speed"), 0.0)
        assertFalse(tts.has("voice_id"))
    }

    @Test
    fun `branch, starting workflow node and procedures are sent`() {
        val config = ConversationConfig(
            agentId = "agent",
            branchId = "branch",
            startingWorkflowNodeId = "node",
            procedureIds = listOf("proc_a", "proc_b"),
            startProcedureIds = listOf("proc_a")
        )

        val root = ConversationOverridesBuilder.constructOverrides(config)
        assertEquals("branch", root.getString("branch_id"))
        assertEquals("node", root.getString("starting_workflow_node_id"))
        assertEquals(listOf("proc_a", "proc_b"), root.getJSONArray("procedure_ids").toList())
        assertEquals(listOf("proc_a"), root.getJSONArray("start_procedure_ids").toList())
    }

    @Test
    fun `an empty procedure list is sent to disable every procedure`() {
        val config = ConversationConfig(agentId = "agent", procedureIds = emptyList())

        val root = ConversationOverridesBuilder.constructOverrides(config)
        assertEquals(0, root.getJSONArray("procedure_ids").length())
    }

    @Test
    fun `unset initiation fields are left out`() {
        val root = ConversationOverridesBuilder.constructOverrides(
            ConversationConfig(agentId = "agent", overrides = Overrides())
        )

        listOf("branch_id", "starting_workflow_node_id", "procedure_ids", "start_procedure_ids").forEach {
            assertFalse("$it should be absent", root.has(it))
        }
        val override = root.getJSONObject("conversation_config_override")
        assertEquals(listOf("conversation"), override.keys().asSequence().toList())
        assertEquals(listOf("text_only"), override.getJSONObject("conversation").keys().asSequence().toList())
    }

    @Test
    fun `every conversation config override field is sent`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(
                agent = AgentOverrides(
                    prompt = PromptOverrides(
                        prompt = "Be brief",
                        llm = "gemini-2.0-flash-001",
                        reasoningEffort = "low",
                        toolIds = listOf("tool_a"),
                        toolIdsStrict = true,
                        nativeMcpServerIds = listOf("mcp_a"),
                        knowledgeBase = listOf(
                            KnowledgeBaseLocator(type = "file", name = "Docs", id = "kb_a", usageMode = "prompt")
                        )
                    ),
                    firstMessage = "Hi",
                    language = Language.EN,
                    maxConversationDurationMessage = "Time is up"
                ),
                tts = TtsOverrides(
                    voiceId = "voice",
                    speed = 1.1,
                    modelId = "eleven_flash_v2_5",
                    stability = 0.5,
                    similarityBoost = 0.8,
                    cloneStrength = 0.3,
                    pronunciationDictionaryLocators = listOf(
                        PronunciationDictionaryLocator(pronunciationDictionaryId = "dict", versionId = "v1")
                    ),
                    supportedVoices = listOf(
                        SupportedVoice(
                            label = "Calm",
                            voiceId = "voice_b",
                            description = "A calm voice",
                            language = "en",
                            modelFamily = "flash",
                            optimizeStreamingLatency = 2,
                            stability = 0.4,
                            speed = 0.9,
                            similarityBoost = 0.7,
                            cloneStrength = 0.2
                        )
                    )
                ),
                conversation = ConversationOverrides(maxDurationSeconds = 600),
                asr = AsrOverrides(keywords = listOf("hello", "world")),
                turn = TurnOverrides(
                    softTimeoutConfig = SoftTimeoutConfigOverrides(
                        message = "Hmm...",
                        additionalSoftTimeoutMessages = listOf("One moment", "Almost there")
                    ),
                    noisyEnvironmentMessage = "It is loud there"
                )
            )
        )

        val override = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override")

        val agent = override.getJSONObject("agent")
        assertEquals("Hi", agent.getString("first_message"))
        assertEquals("en", agent.getString("language"))
        assertEquals("Time is up", agent.getString("max_conversation_duration_message"))
        val prompt = agent.getJSONObject("prompt")
        assertEquals("Be brief", prompt.getString("prompt"))
        assertEquals("gemini-2.0-flash-001", prompt.getString("llm"))
        assertEquals("low", prompt.getString("reasoning_effort"))
        assertEquals(listOf("tool_a"), prompt.getJSONArray("tool_ids").toList())
        assertTrue(prompt.getBoolean("tool_ids_strict"))
        assertEquals(listOf("mcp_a"), prompt.getJSONArray("native_mcp_server_ids").toList())
        val kb = prompt.getJSONArray("knowledge_base").getJSONObject(0)
        assertEquals("file", kb.getString("type"))
        assertEquals("Docs", kb.getString("name"))
        assertEquals("kb_a", kb.getString("id"))
        assertEquals("prompt", kb.getString("usage_mode"))

        val tts = override.getJSONObject("tts")
        assertEquals("voice", tts.getString("voice_id"))
        assertEquals(1.1, tts.getDouble("speed"), 0.0)
        assertEquals("eleven_flash_v2_5", tts.getString("model_id"))
        assertEquals(0.5, tts.getDouble("stability"), 0.0)
        assertEquals(0.8, tts.getDouble("similarity_boost"), 0.0)
        assertEquals(0.3, tts.getDouble("clone_strength"), 0.0)
        val locator = tts.getJSONArray("pronunciation_dictionary_locators").getJSONObject(0)
        assertEquals("dict", locator.getString("pronunciation_dictionary_id"))
        assertEquals("v1", locator.getString("version_id"))
        val voice = tts.getJSONArray("supported_voices").getJSONObject(0)
        assertEquals("Calm", voice.getString("label"))
        assertEquals("voice_b", voice.getString("voice_id"))
        assertEquals("A calm voice", voice.getString("description"))
        assertEquals("en", voice.getString("language"))
        assertEquals("flash", voice.getString("model_family"))
        assertEquals(2, voice.getInt("optimize_streaming_latency"))
        assertEquals(0.4, voice.getDouble("stability"), 0.0)
        assertEquals(0.9, voice.getDouble("speed"), 0.0)
        assertEquals(0.7, voice.getDouble("similarity_boost"), 0.0)
        assertEquals(0.2, voice.getDouble("clone_strength"), 0.0)

        val conversation = override.getJSONObject("conversation")
        assertEquals(600, conversation.getInt("max_duration_seconds"))
        assertFalse(conversation.getBoolean("text_only"))

        assertEquals(listOf("hello", "world"), override.getJSONObject("asr").getJSONArray("keywords").toList())

        val turn = override.getJSONObject("turn")
        val softTimeout = turn.getJSONObject("soft_timeout_config")
        assertEquals("Hmm...", softTimeout.getString("message"))
        assertEquals(
            listOf("One moment", "Almost there"),
            softTimeout.getJSONArray("additional_soft_timeout_messages").toList()
        )
        assertEquals("It is loud there", turn.getString("noisy_environment_message"))
    }

    @Test
    fun `an empty asr keyword list is sent`() {
        val config = ConversationConfig(agentId = "agent", overrides = Overrides(asr = AsrOverrides(keywords = emptyList())))

        val asr = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override").getJSONObject("asr")
        assertEquals(0, asr.getJSONArray("keywords").length())
    }

    @Test
    fun `optional knowledge base usage mode is left to the server`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(
                agent = AgentOverrides(
                    prompt = PromptOverrides(knowledgeBase = listOf(KnowledgeBaseLocator(type = "url", name = "Site", id = "kb_b")))
                )
            )
        )

        val prompt = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override").getJSONObject("agent").getJSONObject("prompt")
        assertFalse(prompt.has("prompt"))
        assertFalse(prompt.getJSONArray("knowledge_base").getJSONObject(0).has("usage_mode"))
    }

    private fun org.json.JSONArray.toList(): List<Any> = (0 until length()).map { get(it) }
}
