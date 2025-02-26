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

import lombok.extern.slf4j.Slf4j;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.GroupCommandDefinition;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.jboss.pnc.bacon.common.cli.AbstractCommand;
import org.jboss.pnc.bacon.common.cli.AbstractGetSpecificCommand;
import org.jboss.pnc.bacon.common.cli.AbstractListCommand;
import org.jboss.pnc.bacon.pnc.client.PncClientHelper;
import org.jboss.pnc.client.ClientException;
import org.jboss.pnc.client.ProductMilestoneClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.ProductMilestone;

import java.util.Optional;

@Slf4j
@GroupCommandDefinition(
        name = "product-milestone",
        description = "Product Milestones",
        groupCommands = {
                ProductMilestoneCli.CancelMilestoneClose.class,
                ProductMilestoneCli.Get.class,
                ProductMilestoneCli.PerformedBuilds.class
        })
public class ProductMilestoneCli extends AbstractCommand {

    private static ProductMilestoneClient clientCache;

    private static ProductMilestoneClient getClient() {
        if (clientCache == null) {
            clientCache = new ProductMilestoneClient(PncClientHelper.getPncConfiguration());
        }
        return clientCache;
    }

    @CommandDefinition(name = "cancel-milestone-close", description = "Cancel milestone close")
    public class CancelMilestoneClose extends AbstractCommand {

        @Argument(required = true, description = "Milestone id")
        private String id;

        @Override
        public CommandResult execute(CommandInvocation commandInvocation) throws CommandException, InterruptedException {

            return super.executeHelper(commandInvocation, () -> getClient().cancelMilestoneClose(id));
        }
    }

    @CommandDefinition(name = "get", description = "Get product milestone")
    public class Get extends AbstractGetSpecificCommand<ProductMilestone> {

        @Override
        public ProductMilestone getSpecific(String id) throws ClientException {
            return getClient().getSpecific(id);
        }
    }

    @CommandDefinition(name = "get-performed-builds", description = "Get performed builds")
    public class PerformedBuilds extends AbstractListCommand<Build> {

        @Argument(required = true, description = "Milestone id")
        private String id;

        @Override
        public RemoteCollection<Build> getAll(String sort, String query) throws RemoteResourceException {
            // TODO: figure out what to do with BuildsFilter
            return getClient().getBuilds(id, null, Optional.ofNullable(sort), Optional.of(query));
        }
    }

}
