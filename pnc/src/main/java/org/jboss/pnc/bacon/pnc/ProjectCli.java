/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.bacon.pnc;

import org.aesh.command.CommandDefinition;
import org.aesh.command.GroupCommandDefinition;
import org.aesh.command.option.Argument;
import org.jboss.pnc.bacon.common.cli.AbstractCommand;
import org.jboss.pnc.bacon.common.cli.AbstractGetSpecificCommand;
import org.jboss.pnc.bacon.common.cli.AbstractListCommand;
import org.jboss.pnc.bacon.common.cli.AbstractNotImplementedCommand;
import org.jboss.pnc.bacon.pnc.client.PncClientHelper;
import org.jboss.pnc.client.ClientException;
import org.jboss.pnc.client.ProjectClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.BuildConfiguration;
import org.jboss.pnc.dto.Project;

import java.util.Optional;

@GroupCommandDefinition(
        name = "project",
        description = "Project",
        groupCommands = {
                ProjectCli.Create.class,
                ProjectCli.Get.class,
                ProjectCli.List.class,
                ProjectCli.ListBuildConfigurations.class,
                ProjectCli.ListBuilds.class,
                ProjectCli.Update.class,
                ProjectCli.Delete.class
        })
public class ProjectCli extends AbstractCommand {

    private static ProjectClient clientCache;

    private static ProjectClient getClient() {
        if (clientCache == null) {
            clientCache = new ProjectClient(PncClientHelper.getPncConfiguration());
        }
        return clientCache;
    }

    @CommandDefinition(name = "create", description = "Create a project")
    public class Create extends AbstractNotImplementedCommand {
    }

    @CommandDefinition(name = "get", description = "Get a project")
    public class Get extends AbstractGetSpecificCommand<Project> {

        @Override
        public Project getSpecific(String id) throws ClientException {
            return getClient().getSpecific(id);
        }
    }

    @CommandDefinition(name = "list", description = "List projects")
    public class List extends AbstractListCommand<Project> {

        @Override
        public RemoteCollection<Project> getAll(String sort, String query) throws RemoteResourceException {
            return getClient().getAll(Optional.ofNullable(sort), Optional.ofNullable(query));
        }
    }

    @CommandDefinition(name = "get-build-configurations", description = "List build configurations for a project")
    public class ListBuildConfigurations extends AbstractListCommand<BuildConfiguration> {

        @Argument(required = true, description = "Project id")
        private String id;

        @Override
        public RemoteCollection<BuildConfiguration> getAll(String sort, String query) throws RemoteResourceException {
            return getClient().getBuildConfigurations(id, Optional.ofNullable(sort), Optional.ofNullable(query));
        }
    }

    @CommandDefinition(name = "get-builds", description = "List builds for a project")
    public class ListBuilds extends AbstractNotImplementedCommand {
    }

    @CommandDefinition(name = "update", description = "Update a project")
    public class Update extends AbstractNotImplementedCommand {
    }

    @CommandDefinition(name = "delete", description = "Delete a project")
    public class Delete extends AbstractNotImplementedCommand {
    }
}
