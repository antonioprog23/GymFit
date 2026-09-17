package com.rutinaboxeo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object RoutineStore {
    private const val PREF = "routine_store"
    private const val ROUTINE = "routine_json"
    private const val PROGRESS = "progress_json"
    private const val ACTIVE_WEEK = "active_week"
    private const val IMPORTED = "routine_imported"

    fun saveRoutine(context: Context, plan: RoutinePlan) {
        val arr = JSONArray()
        plan.exercises.forEach { e ->
            arr.put(JSONObject().apply {
                put("week", e.week); put("day", e.day); put("block", e.block)
                put("exercise", e.exercise); put("series", e.series); put("reps", e.reps)
                put("rest", e.rest); put("note", e.note); put("order", e.order)
                put("instruction", e.instruction); put("videoUrl", e.videoUrl)
                put("alternatives", JSONArray().apply {
                    e.alternatives.forEach { alternative ->
                        put(JSONObject().apply {
                            put("title", alternative.title)
                            put("instruction", alternative.instruction)
                            put("videoUrl", alternative.videoUrl)
                        })
                    }
                })
            })
        }
        val sessions = JSONObject()
        plan.sessions.forEach { (day, info) ->
            sessions.put(day, JSONObject().apply {
                put("title", info.title); put("focus", info.focus); put("note", info.note)
            })
        }
        val morning = JSONArray()
        plan.morningSteps.forEach { step ->
            morning.put(JSONObject().apply {
                put("title", step.title); put("seconds", step.seconds); put("instruction", step.instruction)
                put("videoUrl", step.videoUrl)
            })
        }
        val root = JSONObject().put("exercises", arr).put("sessions", sessions).put("morning", morning)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(ROUTINE, root.toString()).putBoolean(IMPORTED, true).apply()
    }

    fun importedStatus(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return if (prefs.contains(IMPORTED)) prefs.getBoolean(IMPORTED, false) else null
    }

    fun setImported(context: Context, imported: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(IMPORTED, imported).apply()
    }

    fun loadRoutine(context: Context): RoutinePlan? {
        val text = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(ROUTINE, null) ?: return null
        return try {
            val root = if (text.startsWith("[")) null else JSONObject(text)
            val arr = root?.getJSONArray("exercises") ?: JSONArray(text)
            val list = mutableListOf<RoutineExercise>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val alternatives = mutableListOf<ExerciseAlternative>()
                o.optJSONArray("alternatives")?.let { savedAlternatives ->
                    for (j in 0 until savedAlternatives.length()) {
                        val alternative = savedAlternatives.getJSONObject(j)
                        alternatives += ExerciseAlternative(
                            alternative.optString("title"), alternative.optString("instruction"),
                            alternative.optString("videoUrl")
                        )
                    }
                }
                list += RoutineExercise(
                    o.getInt("week"), o.getString("day"), o.getString("block"), o.getString("exercise"),
                    o.getString("series"), o.getString("reps"), o.getString("rest"), o.optString("note"), o.getInt("order"),
                    o.optString("instruction"), o.optString("videoUrl"), alternatives
                )
            }
            val sessions = mutableMapOf<String, SessionInfo>()
            val saved = root?.optJSONObject("sessions")
            saved?.keys()?.forEach { day ->
                val info = saved.optJSONObject(day) ?: return@forEach
                sessions[day] = SessionInfo(info.optString("title"), info.optString("focus"), info.optString("note"))
            }
            val morning = mutableListOf<MorningStep>()
            root?.optJSONArray("morning")?.let { steps ->
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    morning += MorningStep(step.getString("title"), step.getInt("seconds"), step.getString("instruction"), step.optString("videoUrl"))
                }
            }
            RoutinePlan(list, sessions, morning)
        } catch (_: Exception) { null }
    }

    fun loadProgress(context: Context): MutableMap<String, ExerciseProgress> {
        val text = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PROGRESS, "{}") ?: "{}"
        val map = mutableMapOf<String, ExerciseProgress>()
        try {
            val root = JSONObject(text)
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                map[key] = ExerciseProgress(
                    weight = o.optString("weight"),
                    actualReps = o.optString("reps"),
                    rir = o.optString("rir"),
                    userNote = o.optString("userNote"),
                    done = o.optBoolean("done")
                )
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveProgress(context: Context, map: Map<String, ExerciseProgress>) {
        val root = JSONObject()
        map.forEach { (key, p) ->
            root.put(key, JSONObject().apply {
                put("weight", p.weight); put("reps", p.actualReps); put("rir", p.rir)
                put("userNote", p.userNote); put("done", p.done)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(PROGRESS, root.toString()).apply()
    }

    fun activeWeek(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(ACTIVE_WEEK, 1)

    fun saveActiveWeek(context: Context, week: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(ACTIVE_WEEK, week).apply()
    }

    fun clearProgress(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(PROGRESS).apply()
    }
}
