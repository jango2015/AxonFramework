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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.common.Assert;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.eventstore.*;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcasterChain;
import org.axonframework.serialization.xml.XStreamSerializer;

import javax.persistence.EntityManager;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.axonframework.eventsourcing.eventstore.EventUtils.asDomainEventMessage;

/**
 * EventStorageEngine implementation that uses JPA to store and fetch events.
 * <p>
 * By default the payload of events is stored as a serialized blob of bytes. Other columns are used to store meta-data
 * that allow quick finding of DomainEvents for a specific aggregate in the correct order.
 *
 * @author Rene de Waele
 */
public class JpaEventStorageEngine extends BatchingEventStorageEngine {
    private final EntityManagerProvider entityManagerProvider;

    /**
     * Initializes an EventStorageEngine that uses JPA to store and load events. The payload and metadata of events is
     * stored as a serialized blob of bytes using a new {@link XStreamSerializer}.
     * <p>
     * Events are read in batches of 100. No upcasting is performed after the events have been fetched.
     *
     * @param entityManagerProvider Provider for the {@link EntityManager} used by this EventStorageEngine.
     * @param transactionManager    The transaction manager used to set the isolation level of the transaction when
     *                              loading events
     */
    public JpaEventStorageEngine(EntityManagerProvider entityManagerProvider, TransactionManager transactionManager) {
        this(null, null, null, transactionManager, null, entityManagerProvider);
    }

    /**
     * Initializes an EventStorageEngine that uses JPA to store and load events. Events are fetched in batches of 100.
     *
     * @param serializer            Used to serialize and deserialize event payload and metadata.
     * @param upcasterChain         Allows older revisions of serialized objects to be deserialized.
     * @param dataSource            Allows the EventStore to detect the database type and define the
     *                              error codes that represent concurrent access failures for most database types.
     * @param transactionManager    The transaction manager used to set the isolation level of the transaction when
     *                              loading events
     * @param entityManagerProvider Provider for the {@link EntityManager} used by this EventStorageEngine.
     * @throws SQLException         If the database product name can not be determined from the given {@code dataSource}
     */
    public JpaEventStorageEngine(Serializer serializer, EventUpcasterChain upcasterChain, DataSource dataSource,
                                 TransactionManager transactionManager,
                                 EntityManagerProvider entityManagerProvider) throws SQLException {
        this(serializer, upcasterChain, new SQLErrorCodesResolver(dataSource), transactionManager, null,
             entityManagerProvider);
    }

    /**
     * Initializes an EventStorageEngine that uses JPA to store and load events.
     *
     * @param serializer                   Used to serialize and deserialize event payload and metadata.
     * @param upcasterChain                Allows older revisions of serialized objects to be deserialized.
     * @param persistenceExceptionResolver Detects concurrency exceptions from the backing database. If {@code null}
     *                                     persistence exceptions are not explicitly resolved.
     * @param transactionManager           The transaction manager used to set the isolation level of the transaction
     *                                     when loading events
     * @param batchSize                    The number of events that should be read at each database access. When more
     *                                     than this number of events must be read to rebuild an aggregate's state, the
     *                                     events are read in batches of this size. Tip: if you use a snapshotter, make
     *                                     sure to choose snapshot trigger and batch size such that a single batch will
     *                                     generally retrieve all events required to rebuild an aggregate's state.
     * @param entityManagerProvider        Provider for the {@link EntityManager} used by this EventStorageEngine.
     */
    public JpaEventStorageEngine(Serializer serializer, EventUpcasterChain upcasterChain,
                                 PersistenceExceptionResolver persistenceExceptionResolver,
                                 TransactionManager transactionManager, Integer batchSize,
                                 EntityManagerProvider entityManagerProvider) {
        super(serializer, upcasterChain, persistenceExceptionResolver, transactionManager, batchSize);
        this.entityManagerProvider = entityManagerProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<? extends TrackedEventData<?>> fetchTrackedEvents(TrackingToken lastToken, int batchSize) {
        Assert.isTrue(lastToken == null || lastToken instanceof GlobalIndexTrackingToken,
                      String.format("Token %s is of the wrong type", lastToken));
        return entityManager().createQuery(
                "SELECT new org.axonframework.eventsourcing.eventstore.GenericTrackedDomainEventEntry(" +
                        "e.globalIndex, e.type, e.aggregateIdentifier, e.sequenceNumber, " +
                        "e.eventIdentifier, e.timeStamp, e.payloadType, " +
                        "e.payloadRevision, e.payload, e.metaData) " + "FROM " + domainEventEntryEntityName() + " e " +
                        "WHERE e.globalIndex > :token " + "ORDER BY e.globalIndex ASC")
                .setParameter("token", lastToken == null ? -1 : ((GlobalIndexTrackingToken) lastToken).getGlobalIndex())
                .setMaxResults(batchSize).getResultList();
    }

    @Override
    protected TrackingToken getTokenForGapDetection(TrackingToken token) {
        return token;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<? extends DomainEventData<?>> fetchDomainEvents(String aggregateIdentifier, long firstSequenceNumber,
                                                                   int batchSize) {
        return entityManager().createQuery(
                "SELECT new org.axonframework.eventsourcing.eventstore.GenericTrackedDomainEventEntry(" +
                        "e.globalIndex, e.type, e.aggregateIdentifier, e.sequenceNumber, " +
                        "e.eventIdentifier, e.timeStamp, e.payloadType, " +
                        "e.payloadRevision, e.payload, e.metaData) " + "FROM " + domainEventEntryEntityName() + " e " +
                        "WHERE e.aggregateIdentifier = :id " + "AND e.sequenceNumber >= :seq " +
                        "ORDER BY e.sequenceNumber ASC").setParameter("id", aggregateIdentifier)
                .setParameter("seq", firstSequenceNumber).setMaxResults(batchSize).getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Optional<? extends DomainEventData<?>> readSnapshotData(String aggregateIdentifier) {
        return entityManager().createQuery(
                "SELECT new org.axonframework.eventsourcing.eventstore.GenericDomainEventEntry(" +
                        "e.type, e.aggregateIdentifier, e.sequenceNumber, e.eventIdentifier, " +
                        "e.timeStamp, e.payloadType, e.payloadRevision, e.payload, e.metaData) " + "FROM " +
                        snapshotEventEntryEntityName() + " e " + "WHERE e.aggregateIdentifier = :id " +
                        "ORDER BY e.sequenceNumber DESC").setParameter("id", aggregateIdentifier).setMaxResults(1)
                .getResultList().stream().findFirst();
    }

    @Override
    protected void appendEvents(List<? extends EventMessage<?>> events, Serializer serializer) {
        if (events.isEmpty()) {
            return;
        }
        try {
            events.stream().map(event -> createEventEntity(event, serializer)).forEach(entityManager()::persist);
            entityManager().flush();
        } catch (Exception e) {
            handlePersistenceException(e, events.get(0));
        }
    }

    @Override
    protected void storeSnapshot(DomainEventMessage<?> snapshot, Serializer serializer) {
        deleteSnapshots(snapshot.getAggregateIdentifier());
        try {
            entityManager().persist(createSnapshotEntity(snapshot, serializer));
            entityManager().flush();
        } catch (Exception e) {
            handlePersistenceException(e, snapshot);
        }
    }

    protected void deleteSnapshots(String aggregateIdentifier) {
        entityManager().createQuery("DELETE FROM " + snapshotEventEntryEntityName() +
                                            " e WHERE e.aggregateIdentifier = :aggregateIdentifier")
                .setParameter("aggregateIdentifier", aggregateIdentifier).executeUpdate();
    }

    protected Object createEventEntity(EventMessage<?> eventMessage, Serializer serializer) {
        return new DomainEventEntry(asDomainEventMessage(eventMessage), serializer);
    }

    protected Object createSnapshotEntity(DomainEventMessage<?> snapshot, Serializer serializer) {
        return new SnapshotEventEntry(snapshot, serializer);
    }

    protected String domainEventEntryEntityName() {
        return DomainEventEntry.class.getSimpleName();
    }

    protected String snapshotEventEntryEntityName() {
        return SnapshotEventEntry.class.getSimpleName();
    }

    protected EntityManager entityManager() {
        return entityManagerProvider.getEntityManager();
    }
}
