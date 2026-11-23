package com.example.heyandroid.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a JSON-schema object for tools whose parameters are all strings.
 */
internal fun stringParameters(
    required: Map<String, String> = emptyMap(),
    optional: Map<String, String> = emptyMap()
): String {
    val properties = JSONObject()
    (required + optional).forEach { (name, description) ->
        properties.put(
            name,
            JSONObject()
                .put("type", "string")
                .put("description", description)
        )
    }

    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray().apply { required.keys.forEach { put(it) } })
        .put("additionalProperties", false)
        .toString()
}

/**
 * Builds a JSON-schema object with a single optional integer parameter.
 */
internal fun integerParameter(name: String, description: String): String =
    JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                name,
                JSONObject()
                    .put("type", "integer")
                    .put("description", description)
            )
        )
        .put("required", JSONArray())
        .put("additionalProperties", false)
        .toString()
