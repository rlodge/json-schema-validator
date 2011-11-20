/*
 * Copyright (c) 2011, Francis Galiegue <fgaliegue@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.eel.kitchen.jsonschema.main;

import org.codehaus.jackson.JsonNode;
import org.eel.kitchen.jsonschema.base.AlwaysFalseValidator;
import org.eel.kitchen.jsonschema.base.Validator;
import org.eel.kitchen.jsonschema.factories.ValidatorFactory;
import org.eel.kitchen.jsonschema.keyword.common.RefKeywordValidator;
import org.eel.kitchen.jsonschema.keyword.common.format.FormatValidator;
import org.eel.kitchen.jsonschema.syntax.SyntaxValidator;
import org.eel.kitchen.util.JsonPointer;
import org.eel.kitchen.util.SchemaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p>Class passed to all {@link Validator} implementations. This class is
 * responsible for several things:</p>
 * <ul>
 *     <li>checking the schema correctness (using {@link SyntaxValidator}
 *     instances);</li>
 *     <li>creating validator instances;</li>
 *     <li>resolving {@code $ref} (see {@link RefKeywordValidator}) <b>and</b>
 *     detect ref looping;</li>
 *     <li>providing {@link ValidationReport} instances;</li>
 *     <li>providing other instances of itself.</li>
 * </ul>
 */
// TODO: unclutter
public final class ValidationContext
{
    private static final Logger logger
        = LoggerFactory.getLogger(ValidationContext.class);

    /**
     * The schema provider used
     */
    private SchemaProvider provider;

    /**
     * The JSON path within the instance for the current context
     */
    private final JsonPointer path;

    /**
     * The map of {@link ValidatorFactory} instances paired with the
     * appropriate schema versions
     */
    private final Map<SchemaVersion, ValidatorFactory> factories;

    /**
     * The {@link ValidationReport} generator
     */
    private final ReportFactory reports;

    /**
     * The ref result lookups for this {@link #path},
     * used for ref looping detection
     */
    private final Set<JsonNode> refLookups = new LinkedHashSet<JsonNode>();

    /**
     * Private constructor
     *
     * @param provider the schema provider
     * @param path the JSON Pointer within the instance
     * @param factories the validator factory
     * @param reports the report generator
     */
    private ValidationContext(final SchemaProvider provider,
        final JsonPointer path,
        final Map<SchemaVersion, ValidatorFactory> factories,
        final ReportFactory reports)
    {
        this.provider = provider;
        this.path = path;
        this.factories = factories;
        this.reports = reports;
    }

    /**
     * Public constructor
     *
     * @param factories the validator factory
     * @param provider the schema provider
     * @param reports the report generator
     */
    public ValidationContext(
        final Map<SchemaVersion, ValidatorFactory> factories,
        final SchemaProvider provider, final ReportFactory reports)
    {
        path = new JsonPointer("");

        this.provider = provider;
        this.factories = factories;
        this.reports = reports;

        refLookups.add(provider.getSchema());
    }

    /**
     * Return the schema node of this context -- <b>not</b> the root schema!
     *
     * @return the matching {@link JsonNode}
     */
    public JsonNode getSchema()
    {
        return provider.getSchema();
    }

    /**
     * Spawn a new context from this context, with a (potentially) different
     * JSON Pointer within the instance and a new schema
     *
     * @param subPath the path element to append to {@link #path} (MUST be
     * {@code null} if the context is spawned for the same path: remember
     * that an empty string is a valid JSON Pointer element!)
     * @param subSchema the schema node to use for the new context
     * @return the new context
     */
    public ValidationContext relocate(final String subPath,
        final JsonNode subSchema)
    {
        final JsonPointer newPath = path.append(subPath);

        final SchemaProvider sp = provider.withSchema(subSchema);

        return new ValidationContext(sp, newPath, factories,  reports);
    }

    /**
     * Spawn a new context with the same JSON Pointer into the instance and a
     * different schema
     *
     * @param subSchema the new schema
     * @return the new context
     */
    public ValidationContext withSchema(final JsonNode subSchema)
    {
        final SchemaProvider sp = provider.withSchema(subSchema);

        final ValidationContext ret = new ValidationContext(sp, path, factories,
            reports);

        ret.refLookups.addAll(refLookups);
        return ret;
    }

    /**
     * Spawn a new context from a schema located at a given URI
     *
     * <p>Please note that this context will use the same pointer within the
     * instance than its caller. This is due to the fact that URI
     * redirections via {@code $ref} never traverse instances.
     * </p>
     *
     * @param uri the URI where the new scheme is located
     * @return the new context
     * @throws IOException the schema at the given URI could not be downloaded
     */
    public ValidationContext fromURI(final URI uri)
        throws IOException
    {
        // FIXME: move this out of here
        if (!uri.isAbsolute()) {
            if (!uri.getSchemeSpecificPart().isEmpty())
                throw new IllegalArgumentException("invalid URI: "
                    + "URI is not absolute and is not a JSON Pointer either");
            return this;
        }

        final SchemaProvider sp = provider.atURI(uri);

        final ValidationContext ret = new ValidationContext(sp, path, factories,
            reports);

        ret.refLookups.addAll(refLookups);

        return ret;
    }

    /**
     * Validate the currently active schema
     *
     * @return the validation report
     * @throws JsonValidationFailureException if reporting is configured to
     * throw this exception
     */
    public ValidationReport validateSchema()
        throws JsonValidationFailureException
    {
        final JsonNode schema = provider.getSchema();
        final ValidatorFactory factory;

        try {
            factory = factories.get(SchemaVersion.getVersion(schema));
            if (factory.isValidated(schema))
                return ValidationReport.TRUE;
        } catch (JsonValidationFailureException e) {
            final ValidationReport ret = createReport(" [schema]");
            ret.error(e.getMessage());
            return ret;
        }

        return factory.validateSchema(this);
    }

    /**
     * Create a {@link Validator} for a given JSON instance.
     *
     * <p>This is what MUST be called by validators when they need to spawn a
     * new validator, because this method handles syntax checking. If the syntax
     * of the schema itself is wrong, returns an {@link AlwaysFalseValidator}.
     * </p>
     *
     * @param instance the JSON instance
     * @return the validator
     * @throws JsonValidationFailureException on validation failure,
     * with the appropriate validation mode
     */
    public Validator getValidator(final JsonNode instance)
        throws JsonValidationFailureException
    {
        final ValidationReport report = validateSchema();

        if (!report.isSuccess())
            return new AlwaysFalseValidator(report);

        final SchemaVersion version
            = SchemaVersion.getVersion(provider.getSchema());
        return factories.get(version).getInstanceValidator(this, instance);
    }

    /**
     * Get a format validator for an instance
     *
     * @param fmt the format specification
     * @param instance the instance
     * @return the {@link FormatValidator}
     * @throws JsonValidationFailureException on validation failure,
     * with the appropriate validation mode
     */
    public Validator getFormatValidator(final String fmt,
        final JsonNode instance)
        throws JsonValidationFailureException
    {
        final SchemaVersion version
            = SchemaVersion.getVersion(provider.getSchema());
        return factories.get(version).getFormatValidator(this, fmt, instance);
    }

    /**
     * Get a validator for a given pointer within a schema for a given instance
     *
     * @param pointer the JSON Pointer from the root of the schema
     * @param instance the instance to validate
     * @return the appropriate validator
     * @throws JsonValidationFailureException on validation failure,
     * with the appropriate validation mode
     */
    public Validator getValidator(final JsonPointer pointer,
        final JsonNode instance)
        throws JsonValidationFailureException
    {
        final ValidationReport report = createReport();

        logger.trace("trying to lookup path \"#{}\" from node {} ", pointer,
            provider.getSchema());

        provider = provider.atPoint(pointer);
        final JsonNode schema = provider.getSchema();

        if (schema.isMissingNode()) {
            report.error("no match in schema for path " + pointer);
            return new AlwaysFalseValidator(report);
        }

        if (!refLookups.add(schema)) {
            logger.debug("ref loop detected!");
            logger.debug("path to loop: {}", refLookups);
            report.error("schema " + schema + " loops on itself");
            return new AlwaysFalseValidator(report);
        }


        return getValidator(instance);
    }

    /**
     * Create a new report with, optionally, a prefix to prepend to all messages
     *
     * @param prefix the prefix to use
     * @return the newly created report
     */
    public ValidationReport createReport(final String prefix)
    {
        return reports.create(path + prefix);
    }

    /**
     * Shortcut to {@link #createReport(String)} with an empty prefix
     *
     * @return the newly created report
     */
    public ValidationReport createReport()
    {
        return createReport("");
    }
}
