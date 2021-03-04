package org.openkilda.functionaltests.extension.tags

import static org.openkilda.functionaltests.extension.ExtensionHelper.isFeatureSpecial

import groovy.util.logging.Slf4j
import org.opentest4j.TestAbortedException
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.IterationInfo
import org.spockframework.runtime.model.SpecInfo

/**
 * A list of tags may be supplied to a build by setting 'tags' system property.
 * Tag expression is basically a boolean expression, that consists of typical operators AND, OR, NOT applied to
 * certain tags. List of allowed tags can be found in {@link Tag} enum. Also, round brackets can be used to build more
 * complex expressions.<br>
 * Example: {@code -Dtags="not ondemand or (positive and negative)"}<br>
 * In order to tag a certain test use annotation {@link Tags}, {@link IterationTags} or {@link IterationTag}.<br>
 *
 * Any spec will get its parent's tags, too. As well as any test method will get all the spec's tags.
 */
@Slf4j
class TagExtension extends AbstractGlobalExtension {

    static final String TAGS_PROPERTY_NAME = "tags"

    static Set<String> specialLiterals = ["NOT", "AND", "OR", "(", ")"]
    static Set<String> tagLiterals = Tag.values()*.toString()

    @Override
    void start() {
        //provide getNameTagged implementation for spec/feature/iteration names and inject Tags information
        [SpecInfo, FeatureInfo].each {
            it.metaClass.getNameTagged = { ->
                def tags = collectAllTags(delegate)
                delegate.name + tagsCollectionToString(tags)
            }
        }
        IterationInfo.metaClass.getNameTagged = { ->
            def iteration = delegate as IterationInfo
            def featureMethod = iteration.feature.featureMethod
            def iterationTags = (featureMethod.getAnnotation(IterationTags)?.value()?.toList() ?: [] +
                    featureMethod.getAnnotation(IterationTag)).findAll()
            def applicableTags = iterationTags.findAll {
                iteration.name =~ it.iterationNameRegex()
            }.collectMany { it.tags().toList() }
            def tagsAnnotation = featureMethod.getAnnotation(Tags)
            if (tagsAnnotation) {
                applicableTags.addAll(tagsAnnotation.value().toList())
            }
            return iteration.name + tagsCollectionToString(applicableTags)
        }
    }

    @Override
    void visitSpec(SpecInfo spec) {
        String tagsExpression = System.getProperty(TAGS_PROPERTY_NAME)
        if (!tagsExpression) {
            return
        }
        spec.getAllFeatures().findAll { !isFeatureSpecial(it) }.each { feature ->
            if (feature.excluded) { //do not compete if feature is already excluded somehow
                return
            }
            def tags = collectAllTags(feature)
            def iterationTags = (feature.featureMethod.getAnnotation(IterationTags)?.value()?.toList() ?: [] +
                    feature.featureMethod.getAnnotation(IterationTag)).findAll()
            if (!iterationTags) {
                feature.excluded = !matches(tagsExpression, tags)
            } else {
                feature.addIterationInterceptor(new IMethodInterceptor() {
                    /*This stores how many times did we match a certain iteration tag.
                     Use this when calculating 'take' limitation for the iteration tag*/
                    Map<IterationTag, Integer> tagExecutions = iterationTags.collectEntries { [(it): 0] }

                    @Override
                    void intercept(IMethodInvocation invocation) throws Throwable {
                        //find all iteration tags that apply to current iteration name
                        def iteration = invocation.iteration
                        Map<IterationTag, Integer> applicableITags = tagExecutions.findAll { tagEntry ->
                            iteration.name =~ tagEntry.key.iterationNameRegex() && tagEntry.key.take() > tagEntry.value
                        }
                        //compare whether the list of tags matches our tag expression, include top-level tags
                        def matched = matches(tagsExpression, tags + (Set) applicableITags.keySet()*.tags().flatten())
                        if (matched) {
                            //increment 'exec' counter for used tags
                            tagExecutions.each { tagEntry ->
                                if (tagEntry.key in applicableITags.keySet()) {
                                    tagEntry.value++
                                }
                            }
                            invocation.proceed()
                        } else {
                            throw new TestAbortedException("The test '$iteration.feature.spec.name#" +
                                    "$iteration.name' does not match the provided tags expression: '$tagsExpression'")
                        }
                    }
                })
            }
        }
        if (spec.getFeatures().every { it.excluded || it.skipped }) {
            spec.skipped = true
        }
    }

    static Set<Tag> collectAllTags(IterationInfo iteration) {
        if (iteration == null)
            return [] as Set
        def feature = iteration.feature
        def iterationTags = (feature.featureMethod.getAnnotation(IterationTags)?.value()?.toList() ?: [] +
                feature.featureMethod.getAnnotation(IterationTag)).findAll()
        def applicableTags = iterationTags.findAll {
            iteration.name =~ it.iterationNameRegex()
        }.collectMany { it.tags().toList() }
        def tagsAnnotations = collectAllTags(feature)
        if (tagsAnnotations) {
            applicableTags.addAll(tagsAnnotations)
        }
        return applicableTags as Set
    }

    static Set<Tag> collectAllTags(FeatureInfo feature) {
        Set<Tag> tags = []
        def annotation = feature.featureMethod.getAnnotation(Tags)
        if (annotation) {
            tags.addAll(annotation.value())
        }
        tags.addAll(collectAllTags(feature.featureMethod.getParent()))
        return tags
    }

    static Set<Tag> collectAllTags(SpecInfo spec) {
        Set<Tag> tags = []
        def annotation = spec.getAnnotation(Tags)
        if (annotation) {
            tags.addAll(annotation.value())
        }
        def superSpec = spec.getSuperSpec()
        if (superSpec) {
            tags.addAll(collectAllTags(superSpec))
        }
        return tags
    }

    /**
     * Check whether given tags expression evaluates into 'true' for given list of tags.
     */
    static boolean matches(String tagsExpression, Set<Tag> tags) {
        def literals = tagsExpression
                .replaceAll(/[()]/, / $0 /)
                .replaceAll(/\s+/, " ").trim()
                .split(" ")
        return Eval.me(literals.collect {
            def literal = it.toUpperCase()
            if (literal == "AND") {
                "&&"
            } else if (literal == "OR") {
                "||"
            } else if (literal == "NOT") {
                "!"
            } else if (literal in specialLiterals) {
                literal
            } else if (literal in tagLiterals) {
                if (tags.contains(Tag.valueOf(literal))) {
                    "true"
                } else {
                    "false"
                }
            } else {
                throw new UnknownTagLiteralException("Unknown tag: $literal")
            }
        }.join(" "))
    }

    private static String tagsCollectionToString(Collection<Tag> input) {
        if (input.empty) {
            return ""
        } else {
            return (input as Set)*.toString().inspect()
        }
    }
}
