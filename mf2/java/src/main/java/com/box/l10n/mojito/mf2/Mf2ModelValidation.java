package com.box.l10n.mojito.mf2;

import java.util.List;
import java.util.Map;

/** Structural checks that Java's model types alone cannot express. */
final class Mf2ModelValidation {
    private Mf2ModelValidation() {}

    static void validate(Mf2Message message) throws Mf2Exception {
        require(message != null);
        for (var declaration : message.declarations()) {
            require(declaration.name() != null);
            expression(declaration.value());
        }
        switch (message) {
            case Mf2Message.PatternMessage value -> pattern(value.pattern());
            case Mf2Message.SelectMessage value -> {
                for (var selector : value.selectors()) require(selector.name() != null);
                for (var variant : value.variants()) {
                    for (var key : variant.keys()) {
                        if (key instanceof Mf2Message.LiteralVariantKey literal) require(literal.value() != null);
                    }
                    pattern(variant.value());
                }
            }
        }
    }

    private static void expression(Mf2Message.Expression expression) throws Mf2Exception {
        require(expression != null);
        require(expression.arg() != null || expression.function() != null);
        if (expression.arg() != null) argument(expression.arg());
        if (expression.function() != null) {
            require(expression.function().name() != null);
            options(expression.function().options());
        }
        attributes(expression.attributes());
    }

    private static void argument(Mf2Message.ExpressionArgument argument) throws Mf2Exception {
        require(argument != null);
        switch (argument) {
            case Mf2Message.LiteralArgument literal -> require(literal.value() != null);
            case Mf2Message.VariableArgument variable -> require(variable.name() != null);
        }
    }

    private static void options(Map<String, Mf2Message.ExpressionArgument> options) throws Mf2Exception {
        for (var value : options.values()) argument(value);
    }

    private static void attributes(Map<String, Mf2Message.AttributeValue> attributes) throws Mf2Exception {
        for (var value : attributes.values()) {
            switch (value) {
                case Mf2Message.PresentAttribute present -> require(present.value());
                case Mf2Message.LiteralAttribute literal -> {
                    require(literal.value() instanceof Mf2Message.LiteralArgument);
                    argument(literal.value());
                }
            }
        }
    }

    private static void pattern(List<Mf2Message.PatternPart> pattern) throws Mf2Exception {
        for (var part : pattern) {
            switch (part) {
                case Mf2Message.TextPart text -> require(text.value() != null);
                case Mf2Message.ExpressionPart value -> expression(value.expression());
                case Mf2Message.MarkupPart value -> {
                    var markup = value.markup();
                    require(markup != null && markup.kind() != null && markup.name() != null);
                    options(markup.options());
                    attributes(markup.attributes());
                }
            }
        }
    }

    private static void require(boolean valid) throws Mf2Exception {
        if (!valid) throw new Mf2Exception("invalid-model", "Message model does not match the shared model schema.");
    }
}
