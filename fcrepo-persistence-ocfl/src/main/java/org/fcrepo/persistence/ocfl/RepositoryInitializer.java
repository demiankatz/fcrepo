/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.persistence.ocfl;


import org.fcrepo.config.FedoraPropsConfig;
import org.fcrepo.config.OcflPropsConfig;
import org.fcrepo.kernel.api.TransactionManager;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.operations.RdfSourceOperation;
import org.fcrepo.kernel.api.operations.RdfSourceOperationFactory;
import org.fcrepo.kernel.api.operations.VersionResourceOperationFactory;
import org.fcrepo.persistence.api.PersistentStorageSession;
import org.fcrepo.persistence.api.exceptions.PersistentItemNotFoundException;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;
import org.fcrepo.persistence.ocfl.api.IndexBuilder;
import org.fcrepo.persistence.ocfl.impl.OcflPersistentSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.inject.Inject;

import static org.fcrepo.kernel.api.RdfLexicon.BASIC_CONTAINER;

/**
 * This class is responsible for initializing the repository on start-up.
 *
 * @author dbernstein
 */
@Component
public class RepositoryInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryInitializer.class);

    @Inject
    private OcflPersistentSessionManager sessionManager;

    @Inject
    private RdfSourceOperationFactory operationFactory;

    @Inject
    private IndexBuilder indexBuilder;

    @Inject
    private VersionResourceOperationFactory versionResourceOperationFactory;

    @Inject
    private OcflPropsConfig config;

    @Inject
    private FedoraPropsConfig fedoraPropsConfig;

    @Inject
    private TransactionManager txManager;

    // This is used in-place of @PostConstruct so that it is called _after_ the rest of context has been
    // completely initialized.
    @EventListener
    public void onApplicationEvent(final ContextRefreshedEvent event) {
        try {
            initialize();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize repository", e);
            ((ConfigurableApplicationContext) event.getApplicationContext()).close();
        }
    }

    /**
     * Initializes the repository
     */
    public void initialize() {
        LOGGER.info("Initializing repository");

        indexBuilder.rebuildIfNecessary();

        final var root = FedoraId.getRepositoryRootId();

        try {
            //check that the root is initialized
            final var transaction = txManager.create();
            transaction.setShortLived(true);
            final PersistentStorageSession session = this.sessionManager.getSession(transaction);

            try {
                session.getHeaders(root, null);
            } catch (final PersistentItemNotFoundException e) {
                LOGGER.debug("Repository root ({}) not found. Creating...", root);
                final RdfSourceOperation operation = this.operationFactory.createBuilder(transaction, root,
                        BASIC_CONTAINER.getURI(), fedoraPropsConfig.getServerManagedPropsMode())
                        .parentId(root).build();

                session.persist(operation);

                //if auto versioning is not enabled, be sure to create an immutable version
                if (!config.isAutoVersioningEnabled()) {
                    final var versionOperation = this.versionResourceOperationFactory
                            .createBuilder(transaction, root).build();
                    session.persist(versionOperation);
                }

                transaction.commit();

                LOGGER.debug("Successfully created repository root ({}).", root);
            }

        } catch (final PersistentStorageException ex) {
            throw new RepositoryRuntimeException(ex.getMessage(), ex);
        }
    }

}
