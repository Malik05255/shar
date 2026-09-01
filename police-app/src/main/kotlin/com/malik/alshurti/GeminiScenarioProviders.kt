package com.malik.alshurti

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Two narrowly-scoped office planners. They receive semantic scene context only, never the child's
 * raw transcript, and may only propose names from the existing runtime 3D vocabulary. They cannot
 * generate media, dialogue, meshes, textures or arbitrary asset URLs.
 */
class GeminiScenarioProvider(
    private val role: Role,
    private val apiKey: String = BuildConfig.GEMINI_API_KEY.trim()
) : ScenarioProvider {
    enum class Role(val model: String) {
        CONTINUITY("gemini-2.5-flash-lite"),
        REALISM("gemini-2.5-flash")
    }

    override suspend fun propose(
        context: SceneContext,
        recentPlans: List<RuntimeScenarioPlan>
    ): RuntimeScenarioPlan? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val connection = (URL("$BASE_URL/${role.model}:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt(context, recentPlans))))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", if (role == Role.CONTINUITY) 0.72 else 0.58)
                        .put("maxOutputTokens", 1400)
                        .put("responseMimeType", "application/json")
                )
                .toString()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (connection.responseCode !in 200..299) return@withContext null
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseResponse(response)
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun prompt(context: SceneContext, recentPlans: List<RuntimeScenarioPlan>): String {
        val purpose = if (role == Role.CONTINUITY) {
            "Plan the next quiet office beat with strong continuity, low repetition, and no staged spectacle."
        } else {
            "Plan the next physically believable human office beat with subtle independent actions and natural timing."
        }
        val recent = recentPlans.takeLast(5).joinToString(" | ") { plan ->
            plan.commands.joinToString(",") { "${it.actor}:${it.clip}" }
        }.ifBlank { "none" }

        return """
            You are one planner inside a fictional cinematic security-office simulation for children.
            $purpose

            The observer is NOT the center of the scene. Do not make POLICE_DOG look at CAMERA unless the semantic context explicitly requires engagement; normal ambient work should use desk, monitor, staff or door attention.
            Never imply a real emergency, real police dispatch, surveillance, arrest, or collection of personal data.
            Output JSON only. Do not output markdown.

            Semantic context only:
            domain=${context.domain}
            explicitCue=${context.explicitCue}
            suppressMajorEvents=${context.suppressMajorEvents}
            suppressApproach=${context.suppressApproach}
            recent=$recent

            Allowed actors:
            POLICE_DOG, STAFF_MALE_01, STAFF_MALE_02, STAFF_FEMALE_01, VISITOR_01,
            DOOR, PHONE, FILE, CHAIR, PRINTER, COFFEE_CUP.

            POLICE_DOG clips:
            IdleWork, Breathing, Blink, LookAtDesk, LookAtMonitor, LookAtCamera, LookAtDoor,
            LookAtStaff, ReviewFile, TurnPage, WriteNote, ReachFile, SetFileDown, UsePhone,
            Listen, Talk, StandUp, SitDown, Walk, LeanForward, LeanBack.

            Staff clips:
            IdleDesk, Type, Read, TalkToStaff, ListenToStaff, HeadNod, Walk, WalkCarryFile,
            CarryFile, StandUp, SitDown, UsePhone, DrinkCoffee, OpenDoor, CloseDoor.

            Prop clips:
            DOOR: OpenDoor, CloseDoor, Idle
            PHONE: Ring, Idle
            FILE: Idle, MoveToDesk, MoveToHand
            CHAIR: Idle, Shift
            PRINTER: Idle, Print
            COFFEE_CUP: Idle, Lift, SetDown

            Channels: LOCOMOTION, BODY, HEAD, GAZE, FACE, HANDS, PROP.
            Allowed non-dialogue sounds: PAGE_TURN, PAPER_HANDLE, KEYBOARD_SHORT, FOOTSTEPS_SOFT,
            DOOR_OPEN, DOOR_CLOSE, PHONE_RING, CHAIR_SHIFT, PRINTER_SHORT, CUP_SETDOWN.
            Sound zones: POLICE_DESK, LEFT_WORKSTATION, RIGHT_WORKSTATION, BACK_WORKSTATION,
            DOORWAY, CORRIDOR, PRINTER_AREA.

            Use 2-7 commands, usually involving 2-4 independent actors. Stagger timing. Avoid synchronized movement.
            Keep duration_ms between 5000 and 15000. Do not invent names outside these lists.

            Schema:
            {
              "duration_ms": 9000,
              "commands": [
                {"actor":"POLICE_DOG","clip":"ReviewFile","channel":"HANDS","loop":true,"delay_ms":0,"blend_ms":250,"playback_rate":0.95}
              ],
              "sounds": [
                {"sound":"PAGE_TURN","zone":"POLICE_DESK","delay_ms":2200,"gain":0.15}
              ]
            }
        """.trimIndent()
    }

    private fun parseResponse(response: String): RuntimeScenarioPlan? {
        val root = JSONObject(response)
        val candidates = root.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val text = buildString {
            for (i in 0 until parts.length()) {
                val value = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (value.isNotBlank()) append(value)
            }
        }.trim()
        if (text.isBlank()) return null
        return parsePlan(JSONObject(text))
    }

    private fun parsePlan(json: JSONObject): RuntimeScenarioPlan? {
        val duration = json.optLong("duration_ms", 0L)
        val commandsJson = json.optJSONArray("commands") ?: return null
        val commands = ArrayList<SceneAnimationCommand>()

        for (i in 0 until commandsJson.length()) {
            val item = commandsJson.optJSONObject(i) ?: continue
            val actor = enumValueOrNull<SceneActorId>(item.optString("actor")) ?: continue
            val channel = enumValueOrNull<AnimationChannel>(item.optString("channel")) ?: continue
            val clip = item.optString("clip").trim()
            if (clip.isBlank()) continue
            val target = enumValueOrNull<SceneActorId>(item.optString("target_actor"))
            commands += SceneAnimationCommand(
                actor = actor,
                clip = clip,
                channel = channel,
                loop = item.optBoolean("loop", false),
                interruptible = true,
                blendMs = item.optInt("blend_ms", 220).coerceIn(80, 900),
                targetActor = target,
                delayMs = item.optLong("delay_ms", 0L).coerceIn(0L, 12_000L),
                playbackRate = item.optDouble("playback_rate", 1.0).toFloat().coerceIn(0.72f, 1.18f)
            )
        }

        val sounds = ArrayList<SpatialSoundCommand>()
        val soundsJson = json.optJSONArray("sounds") ?: JSONArray()
        for (i in 0 until soundsJson.length()) {
            val item = soundsJson.optJSONObject(i) ?: continue
            val sound = enumValueOrNull<OfficeSoundId>(item.optString("sound")) ?: continue
            if (sound == OfficeSoundId.DISTANT_STAFF_SPEECH) continue
            val zone = enumValueOrNull<OfficeZone>(item.optString("zone")) ?: continue
            sounds += SpatialSoundCommand(
                sound = sound,
                zone = zone,
                delayMs = item.optLong("delay_ms", 0L).coerceIn(0L, 12_000L),
                gain = item.optDouble("gain", 0.12).toFloat().coerceIn(0.04f, 0.30f)
            )
        }

        return RuntimeScenarioPlan(
            commands = commands,
            durationHintMs = duration,
            sounds = sounds,
            reason = "ai-${role.name.lowercase()}",
            keepWorldRunning = true
        ).takeIf { commands.isNotEmpty() }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value.trim().uppercase() }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}
