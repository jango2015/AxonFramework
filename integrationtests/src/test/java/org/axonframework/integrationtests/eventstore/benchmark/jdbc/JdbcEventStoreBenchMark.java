/*
 * Copyright (c) 2010-2016. Axon Framework
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.integrationtests.eventstore.benchmark.jdbc;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.UnitOfWorkAwareConnectionProviderWrapper;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jdbc.MySqlEventTableFactory;
import org.axonframework.integrationtests.eventstore.benchmark.AbstractEventStoreBenchmark;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Jettro Coenradie
 */
public class JdbcEventStoreBenchMark extends AbstractEventStoreBenchmark {

    private EventStore eventStore;
    private JdbcEventStorageEngine eventStorageEngine;
    private DataSource dataSource;
    private PlatformTransactionManager transactionManager;
    private MessageHandlerInterceptor<Message<?>> transactionManagingInterceptor;

    public static void main(String[] args) throws Exception {
        AbstractEventStoreBenchmark benchmark = prepareBenchMark("META-INF/spring/benchmark-jdbc-context.xml");
        benchmark.startBenchMark();
        System.out.println(String.format("End benchmark at: %s", new Date()));
    }

    public JdbcEventStoreBenchMark(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        ConnectionProvider connectionProvider = new UnitOfWorkAwareConnectionProviderWrapper(dataSource::getConnection);
        TransactionManager axonTransactionManager = new SpringTransactionManager(transactionManager);
        this.eventStorageEngine = new JdbcEventStorageEngine(axonTransactionManager, connectionProvider);
        this.eventStore = new EmbeddedEventStore(eventStorageEngine);
        this.transactionManagingInterceptor = new TransactionManagingInterceptor<>(axonTransactionManager);
    }

    @Override
    protected void prepareEventStore() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                try {
                    Connection connection = dataSource.getConnection();
                    connection.prepareStatement("DROP TABLE IF EXISTS DomainEventEntry").executeUpdate();
                    connection.prepareStatement("DROP TABLE IF EXISTS SnapshotEventEntry").executeUpdate();
                    eventStorageEngine.createSchema(MySqlEventTableFactory.INSTANCE);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @Override
    protected Runnable getRunnableInstance() {
        return new TransactionalBenchmark();
    }

    private class TransactionalBenchmark implements Runnable {

        @Override
        public void run() {
            final String aggregateId = UUID.randomUUID().toString();
            // the inner class forces us into a final variable, hence the AtomicInteger
            final AtomicInteger eventSequence = new AtomicInteger(0);
            for (int t = 0; t < getTransactionCount(); t++) {
                new DefaultUnitOfWork<>(null).execute(() -> eventSequence
                        .set(saveAndLoadLargeNumberOfEvents(aggregateId, eventStore, eventSequence.get())));
            }
        }
    }

}
