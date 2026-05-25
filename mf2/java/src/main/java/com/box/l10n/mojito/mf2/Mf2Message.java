package com.box.l10n.mojito.mf2;

import java.util.List;
import java.util.Map;

public sealed interface Mf2Message permits Mf2Message.PatternMessage, Mf2Message.SelectMessage {
    List<Declaration> declarations();

    default Mf2FormatResult format(Map<String, ?> arguments, Mf2FormatOptions options)
            throws Mf2Exception {
        return Mf2Formatter.formatMessage(this, arguments, options);
    }

    default Mf2PartsResult formatToParts(
            Map<String, ?> arguments, Mf2FormatOptions options)
            throws Mf2Exception {
        return Mf2Formatter.formatMessageToParts(this, arguments, options);
    }

    record PatternMessage(List<Declaration> declarations, List<PatternPart> pattern)
            implements Mf2Message {
        public PatternMessage {
            declarations = List.copyOf(declarations);
            pattern = List.copyOf(pattern);
        }
    }

    record SelectMessage(
            List<Declaration> declarations,
            List<VariableRef> selectors,
            List<Variant> variants)
            implements Mf2Message {
        public SelectMessage {
            declarations = List.copyOf(declarations);
            selectors = List.copyOf(selectors);
            variants = List.copyOf(variants);
        }
    }

    sealed interface Declaration permits InputDeclaration, LocalDeclaration {
        String name();

        Expression value();
    }

    record InputDeclaration(String name, Expression value) implements Declaration {}

    record LocalDeclaration(String name, Expression value) implements Declaration {}

    sealed interface PatternPart permits TextPart, ExpressionPart, MarkupPart {}

    record TextPart(String value) implements PatternPart {}

    record ExpressionPart(Expression expression) implements PatternPart {}

    record MarkupPart(Markup markup) implements PatternPart {}

    record Expression(
            ExpressionArgument arg, FunctionRef function, Map<String, AttributeValue> attributes) {
        public Expression {
            attributes = Map.copyOf(attributes);
        }
    }

    sealed interface ExpressionArgument permits LiteralArgument, VariableArgument {}

    record LiteralArgument(String value) implements ExpressionArgument {}

    record VariableArgument(String name) implements ExpressionArgument {}

    record FunctionRef(String name, Map<String, ExpressionArgument> options) {
        public FunctionRef {
            options = Map.copyOf(options);
        }
    }

    sealed interface AttributeValue permits LiteralAttribute, PresentAttribute {}

    record LiteralAttribute(ExpressionArgument value) implements AttributeValue {}

    record PresentAttribute(boolean value) implements AttributeValue {}

    record Markup(
            String kind,
            String name,
            Map<String, ExpressionArgument> options,
            Map<String, AttributeValue> attributes) {
        public Markup {
            options = Map.copyOf(options);
            attributes = Map.copyOf(attributes);
        }
    }

    record VariableRef(String name) {}

    record Variant(List<VariantKey> keys, List<PatternPart> value) {
        public Variant {
            keys = List.copyOf(keys);
            value = List.copyOf(value);
        }
    }

    sealed interface VariantKey permits LiteralVariantKey, CatchAllVariantKey {}

    record LiteralVariantKey(String value) implements VariantKey {}

    record CatchAllVariantKey() implements VariantKey {}

}
