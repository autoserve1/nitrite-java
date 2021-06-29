package org.dizitart.no2.transaction;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.NitriteConfig;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.collection.NitriteId;
import org.dizitart.no2.common.concurrent.LockService;
import org.dizitart.no2.common.module.NitriteModule;
import org.dizitart.no2.exceptions.TransactionException;
import org.dizitart.no2.repository.ObjectRepository;
import org.dizitart.no2.store.NitriteMap;
import org.dizitart.no2.store.NitriteStore;

import java.util.*;
import java.util.concurrent.locks.Lock;

import static org.dizitart.no2.common.util.ObjectUtils.findRepositoryName;

/**
 * @author Anindya Chatterjee
 * @since 4.0
 */
@Slf4j
class NitriteTransaction implements Transaction {
    private final Nitrite nitrite;
    private final LockService lockService;

    private TransactionalStore<?> transactionalStore;
    private TransactionalConfig transactionalConfig;
    private Map<String, TransactionContext> contextMap;
    private Map<String, NitriteCollection> collectionRegistry;
    private Map<String, ObjectRepository<?>> repositoryRegistry;
    private Map<String, Stack<UndoEntry>> undoRegistry;

    @Getter
    private String id;

    @Getter
    private State state;

    public NitriteTransaction(Nitrite nitrite, LockService lockService) {
        this.nitrite = nitrite;
        this.lockService = lockService;
        prepare();
    }

    @Override
    public NitriteCollection getCollection(String name) {
        checkState();

        if (collectionRegistry.containsKey(name)) {
            return collectionRegistry.get(name);
        }

        NitriteCollection primary;
        if (nitrite.hasCollection(name)) {
            primary = nitrite.getCollection(name);
        } else {
            throw new TransactionException("collection " + name + " does not exists");
        }

        NitriteMap<NitriteId, Document> txMap = transactionalStore.openMap(name,
            NitriteId.class, Document.class);

        TransactionContext context = new TransactionContext();
        context.setCollectionName(name);
        context.setNitriteMap(txMap);
        context.setJournal(new LinkedList<>());
        context.setConfig(transactionalConfig);

        NitriteCollection txCollection = new DefaultTransactionalCollection(primary, context, nitrite);
        collectionRegistry.put(name, txCollection);
        contextMap.put(name, context);
        return txCollection;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectRepository<T> getRepository(Class<T> type) {
        checkState();

        String name = findRepositoryName(type, null);
        if (repositoryRegistry.containsKey(name)) {
            return (ObjectRepository<T>) repositoryRegistry.get(name);
        }

        ObjectRepository<T> primary;
        if (nitrite.hasRepository(type)) {
            primary = nitrite.getRepository(type);
        } else {
            throw new TransactionException("repository of type " + type.getName() + " does not exists");
        }

        NitriteMap<NitriteId, Document> txMap = transactionalStore.openMap(name,
            NitriteId.class, Document.class);

        TransactionContext context = new TransactionContext();
        context.setCollectionName(name);
        context.setNitriteMap(txMap);
        context.setJournal(new LinkedList<>());
        context.setConfig(transactionalConfig);

        NitriteCollection primaryCollection = primary.getDocumentCollection();
        NitriteCollection backingCollection = new DefaultTransactionalCollection(primaryCollection, context, nitrite);
        ObjectRepository<T> txRepository = new DefaultTransactionalRepository<>(type,
            primary, backingCollection, transactionalConfig);

        repositoryRegistry.put(name, txRepository);
        contextMap.put(name, context);
        return txRepository;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectRepository<T> getRepository(Class<T> type, String key) {
        checkState();

        String name = findRepositoryName(type, key);
        if (repositoryRegistry.containsKey(name)) {
            return (ObjectRepository<T>) repositoryRegistry.get(name);
        }

        ObjectRepository<T> primary;
        if (nitrite.hasRepository(type, key)) {
            primary = nitrite.getRepository(type, key);
        } else {
            throw new TransactionException("repository of type " + type.getName()
                + " and key " + key + " does not exists");
        }

        NitriteMap<NitriteId, Document> txMap = transactionalStore.openMap(name,
            NitriteId.class, Document.class);

        TransactionContext context = new TransactionContext();
        context.setCollectionName(name);
        context.setNitriteMap(txMap);
        context.setJournal(new LinkedList<>());
        context.setConfig(transactionalConfig);

        NitriteCollection primaryCollection = primary.getDocumentCollection();
        NitriteCollection backingCollection = new DefaultTransactionalCollection(primaryCollection, context, nitrite);
        ObjectRepository<T> txRepository = new DefaultTransactionalRepository<>(type,
            primary, backingCollection, transactionalConfig);
        repositoryRegistry.put(name, txRepository);
        contextMap.put(name, context);
        return txRepository;
    }

    @Override
    public void commit() {
        checkState();
        this.state = State.PartiallyCommitted;

        for (Map.Entry<String, TransactionContext> contextEntry : contextMap.entrySet()) {
            String collectionName = contextEntry.getKey();
            TransactionContext transactionContext = contextEntry.getValue();

            Stack<UndoEntry> undoLog = undoRegistry.containsKey(collectionName)
                ? undoRegistry.get(collectionName) : new Stack<>();

            Lock lock = lockService.getWriteLock(collectionName);
            try {
                lock.lock();
                Queue<JournalEntry> commitLog = transactionContext.getJournal();
                int length = commitLog.size();
                for (int i = 0; i < length; i++) {
                    JournalEntry entry = commitLog.poll();
                    if (entry != null) {
                        Command commitCommand = entry.getCommit();
                        if (commitCommand != null) {
                            try {
                                commitCommand.execute();
                            } finally {
                                UndoEntry undoEntry = new UndoEntry();
                                undoEntry.setCollectionName(collectionName);
                                undoEntry.setRollback(entry.getRollback());
                                undoLog.push(undoEntry);
                            }
                        }
                    }
                }
            } catch (TransactionException te) {
                state = State.Failed;
                log.error("Error while committing transaction", te);
                throw te;
            } catch (Exception e) {
                state = State.Failed;
                log.error("Error while committing transaction", e);
                throw new TransactionException("failed to commit transaction", e);
            } finally {
                undoRegistry.put(collectionName, undoLog);
                transactionContext.getActive().set(false);
                lock.unlock();
            }
        }

        state = State.Committed;
        close();
    }

    @Override
    public void rollback() {
        this.state = State.Aborted;

        for (Map.Entry<String, Stack<UndoEntry>> entry : undoRegistry.entrySet()) {
            String collectionName = entry.getKey();
            Stack<UndoEntry> undoLog = entry.getValue();

            Lock writeLock = lockService.getWriteLock(collectionName);
            try {
                writeLock.lock();

                int size = undoLog.size();
                for (int i = 0; i < size; i++) {
                    UndoEntry undoEntry = undoLog.pop();
                    if (undoEntry != null) {
                        Command rollbackCommand = undoEntry.getRollback();
                        rollbackCommand.execute();
                    }
                }
            } finally {
                writeLock.unlock();
            }
        }
        close();
    }

    @Override
    public void close() {
        try {
            state = State.Closed;
            for (TransactionContext context : contextMap.values()) {
                context.getActive().set(false);
            }

            this.contextMap.clear();
            this.collectionRegistry.clear();
            this.repositoryRegistry.clear();
            this.undoRegistry.clear();
            this.transactionalStore.close();
        } catch (Exception e) {
            throw new TransactionException("transaction failed to close", e);
        }
    }

    private void prepare() {
        this.contextMap = new HashMap<>();
        this.collectionRegistry = new HashMap<>();
        this.repositoryRegistry = new HashMap<>();
        this.undoRegistry = new HashMap<>();

        this.id = UUID.randomUUID().toString();

        NitriteStore<?> nitriteStore = nitrite.getStore();
        NitriteConfig nitriteConfig = nitrite.getConfig();
        this.transactionalConfig = new TransactionalConfig(nitriteConfig);
        this.transactionalConfig.loadModule(NitriteModule.module(new TransactionalStore<>(nitriteStore)));

        this.transactionalConfig.autoConfigure();
        this.transactionalConfig.initialize();
        this.transactionalStore = (TransactionalStore<?>) this.transactionalConfig.getNitriteStore();
        this.state = State.Active;
    }

    private void checkState() {
        if (state != State.Active) {
            throw new TransactionException("transaction is not active");
        }
    }
}
