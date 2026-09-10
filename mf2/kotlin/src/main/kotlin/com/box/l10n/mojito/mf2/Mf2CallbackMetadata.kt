package com.box.l10n.mojito.mf2

import java.util.Collections

/** Read-only copies of semantic annotation fields exposed to function callbacks. */
internal fun immutableFunction(function: Map<String, Any?>): Map<String, Any?> {
    val copy = LinkedHashMap(function)
    val options = function["options"] as? Map<*, *>
    if (options != null) {
        val copiedOptions = options.mapValues { (_, argument) ->
            if (argument is Map<*, *>) Collections.unmodifiableMap(LinkedHashMap(argument)) else argument
        }
        copy["options"] = Collections.unmodifiableMap(copiedOptions)
    }
    return Collections.unmodifiableMap(copy)
}
