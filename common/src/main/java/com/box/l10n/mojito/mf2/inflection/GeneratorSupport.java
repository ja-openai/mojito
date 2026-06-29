package com.box.l10n.mojito.mf2.inflection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks locale-specific source-data, audit, report, and survey helpers that support generated
 * fixtures and pack compilation but are not part of the product runtime/tooling API.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface GeneratorSupport {}
