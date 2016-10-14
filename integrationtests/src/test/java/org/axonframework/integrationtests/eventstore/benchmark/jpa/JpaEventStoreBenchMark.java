/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.integrationtests.eventstore.benchmark.jpa;

import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.integrationtests.eventstore.benchmark.AbstractEventStoreBenchmark;
import org.axonframework.messaging.DefaultInterceptorChain;
import org.axonframework.messaging.InterceptorChain;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singleton;

/**
 * @author Jettro Coenradie
 */
public class JpaEventStoreBenchMark extends AbstractEventStoreBenchmark {

    private EventStore eventStore;
    private PlatformTransactionManager transactionManager;
    private MessageHandlerInterceptor<Message<?>> transactionManagingInterceptor;

    @PersistenceContext
    private EntityManager entityManager;

    public static void main(String[] args) throws Exception {
        AbstractEventStoreBenchmark benchmark = prepareBenchMark("META-INF/spring/benchmark-jpa-context.xml");
        benchmark.startBenchMark();
        System.out.println(String.format("End benchmark at: %s", new Date()));
    }

    public JpaEventStoreBenchMark(JpaEventStorageEngine storageEngine, PlatformTransactionManager transactionManager) {
        this.eventStore = new EmbeddedEventStore(storageEngine);
        this.transactionManager = transactionManager;
        this.transactionManagingInterceptor =
                new TransactionManagingInterceptor<>(new SpringTransactionManager(transactionManager));
    }

    @Override
    protected void prepareEventStore() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                entityManager.createQuery("DELETE FROM DomainEventEntry s").executeUpdate();
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
                UnitOfWork<?> unitOfWork = new DefaultUnitOfWork<>(null);
                unitOfWork.execute(() -> {
                    InterceptorChain interceptorChain = new DefaultInterceptorChain<Message<?>>(unitOfWork, singleton(
                            transactionManagingInterceptor), message -> {
                        eventSequence.set(saveAndLoadLargeNumberOfEvents(aggregateId, eventStore, eventSequence.get()));
                        return null;
                    });
                    try {
                        interceptorChain.proceed();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
    }

}
