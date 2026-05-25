package com.box.l10n.mojito.mf2;

import java.util.Map;

public sealed interface Mf2FormattedPart
        permits Mf2FormattedPart.Text,
                Mf2FormattedPart.Fallback,
                Mf2FormattedPart.Expression,
                Mf2FormattedPart.Markup {
    record Text(String value) implements Mf2FormattedPart {}

    record Fallback(String source, String value) implements Mf2FormattedPart {}

    record Expression(
            String value,
            Map<String, Mf2Message.AttributeValue> attributes,
            String direction)
            implements Mf2FormattedPart {
        public Expression {
            attributes = Map.copyOf(attributes);
        }
    }

    record Markup(
            String kind,
            String name,
            Map<String, Mf2Message.ExpressionArgument> options,
            Map<String, Mf2Message.AttributeValue> attributes)
            implements Mf2FormattedPart {
        public Markup {
            options = Map.copyOf(options);
            attributes = Map.copyOf(attributes);
        }
    }
}
