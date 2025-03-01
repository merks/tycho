/*******************************************************************************
 * Copyright (c) 2008, 2022 Sonatype Inc. and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *    Bachmann electronic GmbH - Bug 457314 - handle null as tycho version
 *    Christoph Läubrich    - Bug 569829 - TychoMavenLifecycleParticipant should respect fail-at-end flag / error output is missing
 *                          - Issue 557 - BuildPropertiesParser should use reactor project instead of basedir
 *******************************************************************************/
package org.eclipse.tycho.core.maven;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.ModelWriter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Disposable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.BuildFailureException;
import org.eclipse.tycho.BuildPropertiesParser;
import org.eclipse.tycho.DependencyResolutionException;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TychoConstants;
import org.eclipse.tycho.core.osgitools.BundleReader;
import org.eclipse.tycho.core.osgitools.DefaultBundleReader;
import org.eclipse.tycho.core.osgitools.DefaultReactorProject;
import org.eclipse.tycho.core.utils.TychoVersion;
import org.eclipse.tycho.p2maven.MavenProjectDependencyProcessor;
import org.eclipse.tycho.p2maven.MavenProjectDependencyProcessor.ProjectDependencyClosure;
import org.eclipse.tycho.resolver.TychoResolver;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "TychoMavenLifecycleListener")
public class TychoMavenLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final boolean DUMP_DATA = Boolean.getBoolean("tycho.p2.dump") || Boolean.getBoolean("tycho.p2.dump.model");

    private static final String TYCHO_GROUPID = "org.eclipse.tycho";
    private static final Set<String> TYCHO_PLUGIN_IDS = new HashSet<>(
            Arrays.asList("tycho-maven-plugin", "tycho-p2-director-plugin", "tycho-p2-plugin",
                    "tycho-p2-publisher-plugin", "tycho-p2-repository-plugin", "tycho-packaging-plugin",
                    "tycho-source-plugin", "tycho-surefire-plugin", "tycho-versions-plugin", "tycho-compiler-plugin"));
    private static final String P2_USER_AGENT_KEY = "p2.userAgent";
    private static final String P2_USER_AGENT_VALUE = "tycho/";

    @Requirement
    private BundleReader bundleReader;

    @Requirement
    private TychoResolver resolver;

    @Requirement
    private PlexusContainer plexus;

    @Requirement
    private Logger log;

    @Requirement
    private BuildPropertiesParser buildPropertiesParser;

    @Requirement
    MavenProjectDependencyProcessor dependencyProcessor;

    @Requirement
    private ModelWriter modelWriter;

    public TychoMavenLifecycleParticipant() {
        // needed for plexus
    }

    // needed for unit tests
    protected TychoMavenLifecycleParticipant(Logger log) {
        this.log = log;
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        try {
            if (disableLifecycleParticipation(session)) {
                return;
            }
            List<MavenProject> projects = session.getProjects();
            validate(projects);

            // setting this system property to let EF figure out where the traffic 
            // is coming from (#467418)
            System.setProperty(P2_USER_AGENT_KEY, P2_USER_AGENT_VALUE + TychoVersion.getTychoVersion());

            configureComponents(session);

            for (MavenProject project : projects) {
                ReactorProject reactorProject = DefaultReactorProject.adapt(project);
                reactorProject.setContextValue(ReactorProject.CTX_INTERPOLATOR,
                        new TychoInterpolator(session, project));
                reactorProject.setContextValue(ReactorProject.CTX_BUILDPROPERTIESPARSER, buildPropertiesParser);
                resolver.setupProject(session, project, reactorProject);
            }
            if (TychoConstants.USE_OLD_RESOLVER) {
                resolveProjects(session, projects);
            } else {
                try {
                    ProjectDependencyClosure closure = dependencyProcessor.computeProjectDependencyClosure(projects,
                            session);
                    for (MavenProject project : projects) {
                        Model model = project.getModel();
                        Set<String> existingDependencies = model.getDependencies().stream()
                                .map(TychoMavenLifecycleParticipant::getKey)
                                .collect(Collectors.toCollection(HashSet::new));
                        Collection<MavenProject> dependencyProjects = closure.getDependencyProjects(project);
                        for (MavenProject dependencyProject : dependencyProjects) {
                            Dependency dependency = new Dependency();
                            dependency.setArtifactId(dependencyProject.getArtifactId());
                            dependency.setGroupId(dependencyProject.getGroupId());
                            dependency.setVersion(dependencyProject.getVersion());
                            String packaging = dependencyProject.getPackaging();
                            dependency.setType(packaging);
                            dependency.setScope(Artifact.SCOPE_COMPILE);
                            dependency.setOptional(false);
                            if (existingDependencies.add(getKey(dependency))) {
                                model.addDependency(dependency);
                            }
                        }
                        if (DUMP_DATA) {
                            try {
                                Set<MavenProject> visited = new HashSet<>();
                                modelWriter.write(new File(project.getBasedir(), "pom-model.xml"), Map.of(), model);
                                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                                        new FileOutputStream(new File(project.getBasedir(), "requirements.txt"))))) {
                                    writer.write(project.getId() + ":\r\n");
                                    dumpProjectRequirements(project, writer, closure, dependencyProjects, "\t",
                                            visited);
                                }
                            } catch (IOException e) {
                            }
                        }
                    }
                } catch (CoreException e) {
                    throw new MavenExecutionException(e.getMessage(), e);
                }
            }
        } catch (BuildFailureException e) {
            // build failure is not an internal (unexpected) error, so avoid printing a stack
            // trace by wrapping it in MavenExecutionException   
            throw new MavenExecutionException(e.getMessage(), e);
        }
    }

    private void dumpProjectRequirements(MavenProject project, BufferedWriter writer, ProjectDependencyClosure closure,
            Collection<MavenProject> dependencyProjects, String indent, Set<MavenProject> visited) throws IOException {
        if (visited.add(project)) {
            List<IRequirement> projectRequirements = closure.getProjectUnits(project).stream()
                    .flatMap(iu -> iu.getRequirements().stream()).toList();
            String indent2 = indent + "\t";
            for (MavenProject dependency : dependencyProjects) {
                writer.write(indent + " depends on " + dependency.getId() + ":\r\n");
                for (IRequirement requirement : projectRequirements) {
                    List<IInstallableUnit> satisfies = closure.getProjectUnits(dependency).stream()
                            .filter(iu -> iu.satisfies(requirement)).toList();
                    for (IInstallableUnit satIU : satisfies) {
                        writer.write(indent2 + "provides " + satIU + " that satisfies " + requirement + "\r\n");
                    }
                }
                dumpProjectRequirements(dependency, writer, closure, closure.getDependencyProjects(dependency), indent2,
                        visited);
            }
        }
    }

    private static String getKey(Dependency dependency) {

        return dependency.getGroupId() + ":" + dependency.getArtifactId() + ":"
                + Objects.requireNonNullElse(dependency.getType(), "jar") + ":" + dependency.getVersion() + ":"
                + Objects.requireNonNullElse(dependency.getClassifier(), "");
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        if (plexus.hasComponent(EquinoxServiceFactory.class)) {
            try {
                EquinoxServiceFactory factory = plexus.lookup(EquinoxServiceFactory.class);
                // do not use plexus.dispose() as this only works once and we
                // want to reuse the factory multiple times but make sure the
                // equinox framework is fully recreated
                if (factory instanceof Disposable disposable) {
                    disposable.dispose();
                }
            } catch (ComponentLookupException e) {
                throw new MavenExecutionException(e.getMessage(), e);
            }
        }
    }

    private void validate(List<MavenProject> projects) throws MavenExecutionException {
        validateConsistentTychoVersion(projects);
        validateUniqueBaseDirs(projects);
    }

    private void resolveProjects(MavenSession session, List<MavenProject> projects) {
        List<ReactorProject> reactorProjects = DefaultReactorProject.adapt(session);

        MavenExecutionRequest request = session.getRequest();
        boolean failFast = MavenExecutionRequest.REACTOR_FAIL_FAST.equals(request.getReactorFailureBehavior());
        Map<MavenProject, BuildFailureException> resolutionErrors = new ConcurrentHashMap<>();
        Consumer<MavenProject> resolveProject = project -> {
            if (failFast && !resolutionErrors.isEmpty()) {
                //short circuit
                return;
            }
            try {
                MavenSession clone = session.clone();
                clone.setCurrentProject(project);
                resolver.resolveProject(clone, project, reactorProjects);
                if (DUMP_DATA) {
                    try {
                        modelWriter.write(new File(project.getBasedir(), "pom-model-classic.xml"), Map.of(),
                                project.getModel());
                    } catch (IOException e) {
                    }
                }
            } catch (BuildFailureException e) {
                resolutionErrors.put(project, e);
                if (failFast) {
                    throw e;
                }
            }
        };

        int degreeOfConcurrency = request.getDegreeOfConcurrency();
        Predicate<MavenProject> takeWhile = Predicate.not(p -> failFast && !resolutionErrors.isEmpty());
        if (degreeOfConcurrency > 1) {
            ForkJoinPool executor = new ForkJoinPool(degreeOfConcurrency);
            ForkJoinTask<?> future = executor
                    .submit(() -> projects.parallelStream().takeWhile(takeWhile).forEach(resolveProject));
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw cause instanceof RuntimeException ex //
                        ? ex
                        : new RuntimeException("resolve dependencies failed", cause);
            } finally {
                executor.shutdown();
            }

        } else {
            projects.stream().takeWhile(takeWhile).forEach(resolveProject);
        }

        reportResolutionErrors(resolutionErrors, projects, failFast);
    }

    private void reportResolutionErrors(Map<MavenProject, BuildFailureException> resolutionErrors,
            List<MavenProject> projects, boolean failFast) {
        if (resolutionErrors.isEmpty()) {
            return;
        }

        if (resolutionErrors.size() == 1 || failFast) {
            //The idea is if user want to fail-fast he would expect to get exactly one error (the first one),
            //while if parallel execution is enabled it might report an (incomplete) list of other failures that happened due to the parallel processing.
            throw resolutionErrors.values().iterator().next();
        }

        DependencyResolutionException exception = new DependencyResolutionException(
                String.format("Cannot resolve dependencies of %d/%d projects, see log for details",
                        resolutionErrors.size(), projects.size()));
        resolutionErrors.values().forEach(exception::addSuppressed);
        resolutionErrors.forEach((project, error) -> log.error(project.getName() + ": " + error.getMessage()));

        throw exception;
    }

    protected void validateConsistentTychoVersion(List<MavenProject> projects) throws MavenExecutionException {
        Map<String, Set<MavenProject>> versionToProjectsMap = new HashMap<>();
        for (MavenProject project : projects) {
            for (Plugin plugin : project.getBuild().getPlugins()) {
                if (TYCHO_GROUPID.equals(plugin.getGroupId()) && TYCHO_PLUGIN_IDS.contains(plugin.getArtifactId())) {
                    String version = plugin.getVersion();
                    // Skip checking plug ins that do not have a version
                    if (version == null) {
                        continue;
                    }
                    log.debug(
                            TYCHO_GROUPID + ":" + plugin.getArtifactId() + ":" + version + " configured in " + project);
                    Set<MavenProject> projectSet = versionToProjectsMap.get(version);
                    if (projectSet == null) {
                        projectSet = new LinkedHashSet<>();
                        versionToProjectsMap.put(version, projectSet);
                    }
                    projectSet.add(project);
                }
            }
        }
        if (versionToProjectsMap.size() > 1) {
            List<String> versions = new ArrayList<>(versionToProjectsMap.keySet());
            Collections.sort(versions);
            log.error("Several versions of tycho plugins are configured " + versions + ":");
            for (String version : versions) {
                log.error(version + ":");
                for (MavenProject project : versionToProjectsMap.get(version)) {
                    log.error("\t" + project.toString());
                }
            }
            throw new MavenExecutionException("All tycho plugins configured in one reactor must use the same version",
                    projects.get(0).getFile());
        }
    }

    private void validateUniqueBaseDirs(List<MavenProject> projects) throws MavenExecutionException {
        // we store intermediate build results in the target/ folder and use the baseDir as unique key
        // so multiple modules in the same baseDir would lead to irreproducible/unexpected results
        // e.g. with mvn clean. This should really not be supported by maven core
        Set<File> baseDirs = new HashSet<>();
        for (MavenProject project : projects) {
            File basedir = project.getBasedir();
            if (baseDirs.contains(basedir)) {
                throw new MavenExecutionException(
                        "Multiple modules within the same basedir are not supported: " + basedir, project.getFile());
            } else {
                baseDirs.add(basedir);
            }
        }
    }

    private static final Set<String> CLEAN_PHASES = Set.of("pre-clean", "clean", "post-clean");

    private boolean disableLifecycleParticipation(MavenSession session) {
        // command line property to disable Tycho lifecycle participant
        return "maven".equals(session.getUserProperties().get("tycho.mode"))
                || session.getUserProperties().containsKey("m2e.version")
                // disable for 'clean-only' builds. Consider that Maven can be invoked without explicit goals, if default goals are specified
                || (!session.getGoals().isEmpty() && CLEAN_PHASES.containsAll(session.getGoals()));
    }

    private void configureComponents(MavenSession session) {
        // TODO why does the bundle reader need to cache stuff in the local maven repository?
        File localRepository = new File(session.getLocalRepository().getBasedir());
        ((DefaultBundleReader) bundleReader).setLocationRepository(localRepository);
    }

}
