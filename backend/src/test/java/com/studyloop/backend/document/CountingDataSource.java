package com.studyloop.backend.document;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

// Counts the statements the application actually sends to Postgres (Phase 26.4).
//
// **A saving in round trips comes back silently, which is why it is asserted rather than
// commented.** The duplicate membership lookup 26.2 removed had been on the chat path since Phase
// 5 and nothing ever noticed; a number written in a comment would not have caught it and would not
// catch the next one. What catches it is a budget a test enforces.
//
// It counts `execute*` on statements rather than `getConnection`, because a connection checkout is
// not a trip to Tokyo and a statement is. Batched inserts count once, which is correct: one
// `executeBatch` is one round trip however many rows are in it.
//
// Off unless a test switches it on. It sits in the shared test configuration so that measuring a
// turn does not fork a second ApplicationContext with a second Hikari pool against Supabase's
// fifteen-client cap — the same reason every other stub in StubAiConfig is shared.
public final class CountingDataSource implements DataSource {

    private static final AtomicInteger STATEMENTS = new AtomicInteger();
    private static volatile boolean counting;

    private final DataSource delegate;

    CountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    // Starts a measurement from zero. Returns nothing on purpose: read the count with `count()`
    // when the work is done, so a test reads like "do the thing, then ask what it cost".
    public static void start() {
        STATEMENTS.set(0);
        counting = true;
    }

    public static int stop() {
        counting = false;
        return STATEMENTS.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    // The connection is proxied only so that the statements it makes can be, which is where the
    // counting happens. Everything else is passed through untouched.
    private static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                CountingDataSource.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (result instanceof CallableStatement statement) {
                        return wrapStatement(statement, CallableStatement.class);
                    }
                    if (result instanceof PreparedStatement statement) {
                        return wrapStatement(statement, PreparedStatement.class);
                    }
                    if (result instanceof Statement statement) {
                        return wrapStatement(statement, Statement.class);
                    }
                    return result;
                });
    }

    private static Object wrapStatement(Statement statement, Class<?> type) {
        return Proxy.newProxyInstance(
                CountingDataSource.class.getClassLoader(),
                new Class<?>[] { type },
                (proxy, method, args) -> {
                    if (counting && method.getName().startsWith("execute")) {
                        STATEMENTS.incrementAndGet();
                    }
                    return invoke(statement, method, args);
                });
    }

    // A proxy that swallowed the target's exception would turn a broken query into a null, so the
    // cause is unwrapped and rethrown as the caller expects it.
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
