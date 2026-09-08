package io.github.aindriub.dataprism.processor;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SubjectIdentifier;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import java.util.List;
import java.util.Set;

/**
 * Fails the build when a field of an {@code @LlmExposedModel} was never
 * classified.
 *
 * <p>The runtime already fails closed on this, but a redacted field discovered
 * in production is an incident, and the same mistake caught by javac is a
 * two-second fix. Adding a field to an exposed model should not be able to
 * change what is disclosed without someone saying so.
 *
 * <p>{@code @NonSensitive} needs a reason for the same purpose: the annotation
 * exists so that "this is safe" is a statement someone made and a reviewer can
 * disagree with, rather than the absence of a statement.
 */
@SupportedAnnotationTypes("io.github.aindriub.dataprism.annotations.LlmExposedModel")
public final class LlmExposedModelProcessor extends AbstractProcessor {

    private static final List<Class<? extends java.lang.annotation.Annotation>> DECLARATIONS =
            List.of(SensitiveData.class, NonSensitive.class,
                    InternalIdentifier.class, SubjectIdentifier.class);

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element type : round.getElementsAnnotatedWith(LlmExposedModel.class)) {
            if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
                continue;
            }
            for (Element member : type.getEnclosedElements()) {
                if (!carriesData(member)) {
                    continue;
                }
                if (!declared(member)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "field is on an @LlmExposedModel but carries no classification. "
                                    + "Add @SensitiveData, or @NonSensitive(reason = \"...\") "
                                    + "stating why it is safe to expose.",
                            member);
                }
            }
        }
        // Other processors may have something to say about these types too.
        return false;
    }

    /**
     * Record components rather than fields: for a record, javac generates both,
     * and reporting each one twice would double every error. A plain class has
     * only fields, so it is read there instead.
     */
    private static boolean carriesData(Element member) {
        if (member.getKind() == ElementKind.RECORD_COMPONENT) {
            return true;
        }
        return member.getKind() == ElementKind.FIELD
                && !member.getModifiers().contains(Modifier.STATIC)
                && member.getEnclosingElement().getKind() != ElementKind.RECORD;
    }

    private static boolean declared(Element member) {
        return DECLARATIONS.stream().anyMatch(a -> member.getAnnotation(a) != null);
    }
}
