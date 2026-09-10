package com.box.l10n.mojito.mf2

/** Validate the semantic fields of the shared model schema, ignoring extension properties. */
internal fun validateModelShape(model: Mf2Model) {
    fun invalid(): Nothing = throw Mf2Error("invalid-model", "Message model does not match the shared model schema.")
    fun obj(value: Any?): Map<*, *> = (value as? Map<*, *>)?.takeIf { it.keys.all { key -> key is String } } ?: invalid()
    fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    fun text(value: Any?) { if (value !is String) invalid() }
    fun argument(value: Any?, literalOnly: Boolean = false) {
        val item = obj(value)
        when (item["type"]) {
            "literal" -> text(item["value"])
            "variable" -> if (literalOnly) invalid() else text(item["name"])
            else -> invalid()
        }
    }
    fun fields(item: Map<*, *>) {
        if (item.containsKey("options")) obj(item["options"]).values.forEach { argument(it) }
        if (item.containsKey("attributes")) obj(item["attributes"]).values.forEach { if (it != true) argument(it, true) }
    }
    fun expression(value: Any?) {
        val item = obj(value)
        if (item["type"] != "expression" || (!item.containsKey("arg") && !item.containsKey("function"))) invalid()
        if (item.containsKey("arg")) argument(item["arg"])
        if (item.containsKey("function")) {
            val function = obj(item["function"])
            if (function["type"] != "function") invalid()
            text(function["name"])
            if (function.containsKey("options")) obj(function["options"]).values.forEach { argument(it) }
        }
        if (item.containsKey("attributes")) obj(item["attributes"]).values.forEach { if (it != true) argument(it, true) }
    }
    fun pattern(value: Any?) {
        for (part in list(value)) {
            if (part is String) continue
            val item = obj(part)
            when (item["type"]) {
                "expression" -> expression(item)
                "markup" -> { text(item["kind"]); text(item["name"]); fields(item) }
                else -> invalid()
            }
        }
    }
    for (raw in list(model["declarations"])) {
        val declaration = obj(raw)
        if (declaration["type"] !in setOf("input", "local")) invalid()
        text(declaration["name"])
        expression(declaration["value"])
    }
    when (model["type"]) {
        "message" -> pattern(model["pattern"])
        "select" -> {
            for (raw in list(model["selectors"])) {
                val selector = obj(raw)
                if (selector["type"] != "variable") invalid()
                text(selector["name"])
            }
            for (raw in list(model["variants"])) {
                val variant = obj(raw)
                for (key in list(variant["keys"])) {
                    val item = obj(key)
                    if (item["type"] == "*") { if (item.containsKey("value")) text(item["value"]) } else argument(item, true)
                }
                pattern(variant["value"])
            }
        }
        else -> invalid()
    }
}

internal fun detachedModelFields(fields: Map<String, Any?>): Map<String, Any?> =
    fields.mapValues { (_, value) -> if (value is Map<*, *>) value.toMap() else value }
