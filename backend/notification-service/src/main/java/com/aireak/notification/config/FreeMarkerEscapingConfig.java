package com.aireak.notification.config;

import freemarker.cache.ConditionalTemplateConfigurationFactory;
import freemarker.cache.FileNameGlobMatcher;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.TemplateConfiguration;
import freemarker.template.Configuration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns on HTML auto-escaping for the {@code *.html.ftl} email templates.
 *
 * <p>FreeMarker only escapes when a template's {@code output_format} is an escaping one, and the
 * default is {@code UndefinedOutputFormat}, which escapes nothing. It infers HTML from the
 * <em>standard</em> extensions {@code .ftlh}/{@code .ftlx} — these templates are named
 * {@code <name>.html.ftl}, so nothing inferred anything and every {@code ${...}} was interpolated
 * raw into the message body.
 *
 * <p>That mattered because not every value in those models is server-authored. The cancellation
 * template renders {@code ${reason}}, and the reason for a match cancellation is free text an
 * administrator types into {@code PUT /api/v1/matches/{id}/cancel} — it then reaches the inbox of
 * every customer holding a booking for that match. Saga-generated reasons are no safer: they
 * concatenate {@code e.getMessage()} from a downstream HTTP error, whose body quotes back the seat
 * codes the client asked for, and {@code seatCodes} is validated only as a non-empty list.
 *
 * <p>Scoped to the HTML half deliberately. The paired {@code *.txt.ftl} templates are plain-text
 * bodies where escaping would be a defect of its own — an ampersand in a team name would reach the
 * reader as {@code &amp;}.
 *
 * <p>Applied to the Spring-managed {@link Configuration} rather than replacing it, so Boot keeps
 * owning the template loader and every other {@code spring.freemarker.*} setting.
 */
@Component
@RequiredArgsConstructor
public class FreeMarkerEscapingConfig {

    private final Configuration freemarkerConfiguration;

    @PostConstruct
    void enableHtmlAutoEscaping() {
        applyTo(freemarkerConfiguration);
    }

    /**
     * Static so tests configure their own {@link Configuration} through this exact call rather than
     * a copy of it — a test that escaped differently from production would assert nothing about
     * production.
     */
    public static void applyTo(Configuration configuration) {
        TemplateConfiguration htmlTemplates = new TemplateConfiguration();
        htmlTemplates.setOutputFormat(HTMLOutputFormat.INSTANCE);
        // A glob, not FileExtensionMatcher: FreeMarker treats the extension as everything after the
        // LAST dot, which here is "ftl" for both halves of every pair — it cannot tell the HTML
        // template from the text one.
        configuration.setTemplateConfigurations(
                new ConditionalTemplateConfigurationFactory(
                        new FileNameGlobMatcher("*.html.ftl"), htmlTemplates));
    }
}
