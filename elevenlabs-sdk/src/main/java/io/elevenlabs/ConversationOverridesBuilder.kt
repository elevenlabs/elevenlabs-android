package io.elevenlabs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helper to construct the initiation client data payload from ConversationConfig.
 */
object ConversationOverridesBuilder {

    private const val TYPE = "conversation_initiation_client_data"

    fun constructOverrides(config: ConversationConfig): JSONObject {
        val root = JSONObject().put("type", TYPE)

        // conversation_config_override
        val overrideObj = JSONObject()
        config.overrides?.let { ovr ->
            // agent
            val agentObj = JSONObject()
            ovr.agent?.let { agent ->
                agent.prompt?.let { agentObj.put("prompt", promptJson(it)) }
                agent.firstMessage?.let { agentObj.put("first_message", it) }
                agent.language?.let { agentObj.put("language", it.code) }
                agent.maxConversationDurationMessage?.let { agentObj.put("max_conversation_duration_message", it) }
            }
            if (agentObj.length() > 0) overrideObj.put("agent", agentObj)

            // tts
            ovr.tts?.let { tts ->
                val ttsObj = ttsJson(tts)
                if (ttsObj.length() > 0) overrideObj.put("tts", ttsObj)
            }

            // asr: an empty keyword list is sent, like the JS SDK
            ovr.asr?.keywords?.let { overrideObj.put("asr", JSONObject().put("keywords", JSONArray(it))) }

            // turn
            ovr.turn?.let { turn ->
                val turnObj = JSONObject()
                turn.softTimeoutConfig?.let { soft ->
                    val softObj = JSONObject()
                    soft.message?.let { softObj.put("message", it) }
                    soft.additionalSoftTimeoutMessages?.let { softObj.put("additional_soft_timeout_messages", JSONArray(it)) }
                    turnObj.put("soft_timeout_config", softObj)
                }
                turn.noisyEnvironmentMessage?.let { turnObj.put("noisy_environment_message", it) }
                if (turnObj.length() > 0) overrideObj.put("turn", turnObj)
            }
        }

        // conversation: always sent, like the JS SDK, so the server knows whether to run a
        // text-only conversation even when only the top-level textOnly flag is set
        val conversationObj = JSONObject().put("text_only", config.resolvedTextOnly)
        config.overrides?.conversation?.maxDurationSeconds?.let { conversationObj.put("max_duration_seconds", it) }
        overrideObj.put("conversation", conversationObj)
        root.put("conversation_config_override", overrideObj)

        config.branchId?.let { root.put("branch_id", it) }
        config.startingWorkflowNodeId?.let { root.put("starting_workflow_node_id", it) }
        // An empty list is sent: it disables all of the agent's procedures
        config.procedureIds?.let { root.put("procedure_ids", JSONArray(it)) }
        config.startProcedureIds?.let { root.put("start_procedure_ids", JSONArray(it)) }

        // custom_llm_extra_body
        config.customLlmExtraBody?.let { map ->
            root.put("custom_llm_extra_body", JSONObject(map))
        }

        // dynamic_variables
        config.dynamicVariables?.let { map ->
            root.put("dynamic_variables", JSONObject(map))
        }

        // user_id
        config.userId?.let { root.put("user_id", it) }

        // source_info
        config.overrides?.client?.let { c ->
            val src = JSONObject()
            c.source?.let { src.put("source", it) }
            c.version?.let { src.put("version", it) }
            if (src.length() > 0) root.put("source_info", src)
        }

        return root
    }

    private fun promptJson(prompt: PromptOverrides): JSONObject {
        val obj = JSONObject().put("prompt", prompt.prompt)
        prompt.llm?.let { obj.put("llm", it) }
        prompt.reasoningEffort?.let { obj.put("reasoning_effort", it) }
        prompt.toolIds?.let { obj.put("tool_ids", JSONArray(it)) }
        prompt.toolIdsStrict?.let { obj.put("tool_ids_strict", it) }
        prompt.nativeMcpServerIds?.let { obj.put("native_mcp_server_ids", JSONArray(it)) }
        prompt.knowledgeBase?.let { bases ->
            obj.put("knowledge_base", JSONArray(bases.map { kb ->
                JSONObject()
                    .put("type", kb.type)
                    .put("name", kb.name)
                    .put("id", kb.id)
                    .apply { kb.usageMode?.let { put("usage_mode", it) } }
            }))
        }
        return obj
    }

    private fun ttsJson(tts: TtsOverrides): JSONObject {
        val obj = JSONObject()
        tts.voiceId?.let { obj.put("voice_id", it) }
        tts.speed?.let { obj.put("speed", it) }
        tts.modelId?.let { obj.put("model_id", it) }
        tts.stability?.let { obj.put("stability", it) }
        tts.similarityBoost?.let { obj.put("similarity_boost", it) }
        tts.cloneStrength?.let { obj.put("clone_strength", it) }
        tts.pronunciationDictionaryLocators?.let { locators ->
            obj.put("pronunciation_dictionary_locators", JSONArray(locators.map {
                JSONObject()
                    .put("pronunciation_dictionary_id", it.pronunciationDictionaryId)
                    .put("version_id", it.versionId)
            }))
        }
        tts.supportedVoices?.let { voices ->
            obj.put("supported_voices", JSONArray(voices.map { supportedVoiceJson(it) }))
        }
        return obj
    }

    private fun supportedVoiceJson(voice: SupportedVoice): JSONObject {
        val obj = JSONObject()
            .put("label", voice.label)
            .put("voice_id", voice.voiceId)
        voice.description?.let { obj.put("description", it) }
        voice.language?.let { obj.put("language", it) }
        voice.modelFamily?.let { obj.put("model_family", it) }
        voice.optimizeStreamingLatency?.let { obj.put("optimize_streaming_latency", it) }
        voice.stability?.let { obj.put("stability", it) }
        voice.speed?.let { obj.put("speed", it) }
        voice.similarityBoost?.let { obj.put("similarity_boost", it) }
        voice.cloneStrength?.let { obj.put("clone_strength", it) }
        return obj
    }
}
