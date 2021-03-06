package com.vladmihalcea.hibernate.masterclass.laboratory.util;

import net.ttddyy.dsproxy.listener.SLF4JQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.hibernate.Interceptor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public abstract class AbstractTest {

    static {
        Thread.currentThread().setName("Alice");
    }

    protected enum LockType {
        LOCKS,
        MVLOCKS,
        MVCC
    }

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread bob = new Thread(r);
        bob.setName("Bob");
        return bob;
    });

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @FunctionalInterface
    protected static interface VoidCallable extends Callable<Void> {

        void execute();

        default Void call() throws Exception {
            execute();
            return null;
        }
    }

    @FunctionalInterface
    protected static interface SessionCallable<T> {
        T execute(Session session);
    }

    @FunctionalInterface
    protected static interface SessionVoidCallable {
        void execute(Session session);
    }

    @FunctionalInterface
    protected static interface TransactionCallable<T> extends SessionCallable<T> {
        default void beforeTransactionCompletion() {

        }

        default void afterTransactionCompletion() {

        }
    }

    @FunctionalInterface
    protected static interface TransactionVoidCallable extends SessionVoidCallable {
        default void beforeTransactionCompletion() {

        }

        default void afterTransactionCompletion() {

        }
    }


    private SessionFactory sf;

    @Before
    public void init() {
        sf = newSessionFactory();
    }

    @After
    public void destroy() {
        sf.close();
    }

    public SessionFactory getSessionFactory() {
        return sf;
    }

    protected abstract Class<?>[] entities();

    protected String[] packages() {
        return null;
    }

    protected Interceptor interceptor() {
        return null;
    }

    private SessionFactory newSessionFactory() {
        Properties properties = getProperties();
        Configuration configuration = new Configuration().addProperties(properties);
        for(Class<?> entityClass : entities()) {
            configuration.addAnnotatedClass(entityClass);
        }
        String[] packages = packages();
        if(packages != null) {
            for(String scannedPackage : packages) {
                configuration.addPackage(scannedPackage);
            }
        }
        Interceptor interceptor = interceptor();
        if(interceptor != null) {
            configuration.setInterceptor(interceptor);
        }
        return configuration.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(properties)
                        .build()
        );
    }

    protected Properties getProperties() {
        Properties properties = new Properties();
        properties.put("hibernate.dialect", hibernateDialect());
        //log settings
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        //data source settings
        properties.put("hibernate.connection.datasource", newDataSource());
        return properties;
    }

    private ProxyDataSource newDataSource() {
        ProxyDataSource proxyDataSource = new ProxyDataSource();
        proxyDataSource.setDataSource(dataSource());
        proxyDataSource.setListener(new SLF4JQueryLoggingListener());
        return proxyDataSource;
    }

    protected String hibernateDialect() {
        return "org.hibernate.dialect.HSQLDialect";
    }

    protected DataSource dataSource() {
        JDBCDataSource dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:test;hsqldb.tx=" + lockType().name().toLowerCase());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    protected <T> T doInTransaction(TransactionCallable<T> callable) {
        T result = null;
        Session session = null;
        Transaction txn = null;
        try {
            session = sf.openSession();
            callable.beforeTransactionCompletion();
            txn = session.beginTransaction();

            result = callable.execute(session);
            txn.commit();
        } catch (RuntimeException e) {
            if ( txn != null && txn.isActive() ) txn.rollback();
            throw e;
        } finally {
            callable.afterTransactionCompletion();
            if (session != null) {
                session.close();
            }
        }
        return result;
    }

    protected void doInTransaction(TransactionVoidCallable callable) {
        Session session = null;
        Transaction txn = null;
        try {
            session = sf.openSession();
            callable.beforeTransactionCompletion();
            txn = session.beginTransaction();

            callable.execute(session);
            txn.commit();
        } catch (RuntimeException e) {
            if ( txn != null && txn.isActive() ) txn.rollback();
            throw e;
        } finally {
            callable.afterTransactionCompletion();
            if (session != null) {
                session.close();
            }
        }
    }

    protected void executeSync(VoidCallable callable) {
        executeSync(Collections.singleton(callable));
    }

    protected void executeSync(Collection<VoidCallable> callables) {
        try {
            List<Future<Void>> futures = executorService.invokeAll(callables);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    protected <T> void executeAsync(Runnable callable, final Runnable completionCallback) {
        final Future future = executorService.submit(callable);
        new Thread(() -> {
            while (!future.isDone()) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            try {
                completionCallback.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).start();
    }

    protected Future<?> executeAsync(Runnable callable) {
        return executorService.submit(callable);
    }

    protected LockType lockType() {
        return LockType.LOCKS;
    }

    protected void awaitOnLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void sleep(int millis) {
        sleep(millis, null);
    }

    protected <V> V sleep(int millis, Callable<V> callable) {
        V result = null;
        try {
            LOGGER.info("Wait {} ms!", millis);
            if (callable != null) {
                result = callable.call();
            }
            Thread.sleep(millis);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
