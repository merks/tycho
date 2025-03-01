/*******************************************************************************
 * Copyright (c) 2022 Christoph Läubrich and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.core;

import java.util.Map;
import java.util.Optional;

import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.tycho.ReactorProject;

@Component(role = TychoProjectManager.class)
public class TychoProjectManager {

    @Requirement(role = TychoProject.class)
    Map<String, TychoProject> projectTypes;

    public Optional<TychoProject> getTychoProject(MavenProject project) {
        return Optional.ofNullable(projectTypes.get(project.getPackaging()));
    }

    public Optional<TychoProject> getTychoProject(ReactorProject project) {
        return Optional.ofNullable(projectTypes.get(project.getPackaging()));
    }

}
