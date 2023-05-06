package sk.tuke.meta.persistence;

import javassist.util.proxy.ProxyObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DAOPersistenceManager implements PersistenceManager {
    private final Connection connection;
    private final Map<Class<?>, EntityDAO<?>> daos = new LinkedHashMap<>();

    public DAOPersistenceManager(Connection connection) {
        this.connection = connection;
    }

    @SuppressWarnings("unchecked")
    public <T> EntityDAO<T> getDAO(Class<T> type) {
        // Types are checked in put DAO method to match properly,
        // so the cast should be OK
        return (EntityDAO<T>) daos.get(type);
    }

    protected <T> void putDAO(Class<T> type, EntityDAO<T> dao) {
        daos.put(type, dao);
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void createTables() {
        for (var dao : daos.values()) {
            dao.createTable();
        }
    }

    @Override
    public <T> Optional<T> get(Class<T> type, long id) {
        return getDAO(type).get(id);
    }

    @Override
    public <T> List<T> getAll(Class<T> type) {
        return getDAO(type).getAll();
    }

    @Override
    public <T> List<T> getBy(Class<T> type, String fieldName, Object value) {
        return getDAO(type).getBy(fieldName, value);
    }

    @Override
    public long save(Object entity) {
        // TODO: What if we would receive a Proxy?
        if(ProxyObject.class.isAssignableFrom(entity.getClass())){
            try {
                return (long) ReflectionManager.getObjectPrimaryKey(entity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return getDAO(entity.getClass()).save(entity);
    }

    @Override
    public void delete(Object entity) {
        getDAO((entity.getClass())).delete(entity);
    }

    @Override
    public void startTransaction() throws SQLException {
        connection.createStatement().execute("BEGIN TRANSACTION;");
        System.out.println("Transaction was started.");
    }

    @Override
    public void commitTransaction() throws SQLException {
        connection.createStatement().execute("COMMIT;");
        System.out.println("Transaction was successful. COMMIT command was applied.");
    }

    @Override
    public void rollbackTransaction() throws SQLException {
        connection.createStatement().execute("ROLLBACK;");
        System.out.println("Transaction was unsuccessful. ROLLBACK command was applied.");
    }
}
