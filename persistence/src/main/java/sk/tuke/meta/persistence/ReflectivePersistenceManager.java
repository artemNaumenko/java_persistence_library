package sk.tuke.meta.persistence;

import org.jetbrains.annotations.NotNull;
import sk.tuke.meta.persistence.exceptions.MissingAnnotationException;
import sk.tuke.meta.persistence.exceptions.PrimaryKeyException;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ManyToOne;
import javax.persistence.PersistenceException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;

public class ReflectivePersistenceManager implements PersistenceManager {
    Connection connection;

    public ReflectivePersistenceManager(Connection connection) {
        this.connection = connection;
    }


    private void entityAnnotationCheck(@NotNull Class<?> objectClass) throws MissingAnnotationException {
        if(objectClass.getAnnotation(Entity.class) != null){
            return;
        }
        throw new MissingAnnotationException(objectClass.getName() + " does not have Entity annotation.");
    }

    private void idAnnotationCheck(@NotNull Class<?> objectClass) throws PrimaryKeyException {
        Field idField = ReflectionManager.getIdField(objectClass);

        if(idField == null){
            throw new PrimaryKeyException("Entity (" + objectClass.getName() +
                    ") does not have field with Id annotation.");
        }

        if(idField.getType() != long.class){
            throw new PrimaryKeyException("Field (" + idField.getName() + ") with Id annotation must be long.");
        }

    }

    private void manyToOneAnnotationCheck(@NotNull Class<?> objectClass)
            throws MissingAnnotationException, PrimaryKeyException {
        for(Field field: objectClass.getDeclaredFields()){
            Annotation manyToOneAnnotation = field.getDeclaredAnnotation(ManyToOne.class);
            if(manyToOneAnnotation != null) {
                Class<?> referenceType = field.getType();
                entityAnnotationCheck(referenceType);
                idAnnotationCheck(referenceType);
            }
        }
    }

    private String getGeneratedSqlFromFile() throws IOException {
        InputStream inputStream = ClassLoader.getSystemResourceAsStream("createTable.sql");

        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader reader = new BufferedReader(inputStreamReader);
        StringBuilder stringBuilder = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line).append('\n');
        }

        String fileContent = stringBuilder.toString().trim();

        System.out.println(fileContent);

        inputStream.close();
        inputStreamReader.close();
        reader.close();

        return fileContent;
    }

    @Override
    public void createTables(){
        try {
            String sqlCommands = getGeneratedSqlFromFile();

            Statement statement = connection.createStatement();
            for (String sql : sqlCommands.split(";")) {
                statement.execute(sql);
            }
            statement.close();
        } catch (SQLException | IOException e) {
            throw new PersistenceException(e);
        }

    }

    private Map<String, Object> getMapOfValues(ResultSet resultSet) throws SQLException {
        Map<String, Object> map = new HashMap<>();

        ResultSetMetaData metaData = resultSet.getMetaData();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            Object value = resultSet.getObject(i);
            String name = metaData.getColumnName(i);

            map.put(name, value);
        }

        return map;
    }

    private void updateMapOfValues(Class<?> type, Map<String, Object> valuesMap, List<String> fieldNamesWithForeignKeys) throws NoSuchFieldException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
        for (String fieldName : fieldNamesWithForeignKeys) {
            Field field = type.getDeclaredField(fieldName);
            Class<?> typeOfField = field.getType();
            Object value = valuesMap.get(fieldName);

            if(value == null){
                valuesMap.put(fieldName, null);
                continue;
            }

            Annotation annotation = field.getAnnotation(ManyToOne.class);
            FetchType fetchType = (FetchType) annotation.getClass().getMethod("fetch").invoke(annotation);
            if(fetchType.equals(FetchType.LAZY)){
               Class<?> targetClass = (Class<?>) annotation.getClass().getMethod("targetEntity").invoke(annotation);
               long id = (int) valuesMap.get(fieldName);

               Object proxy = ProxyManager.createProxy(connection, targetClass, id);
               valuesMap.put(fieldName, proxy);

               continue;
            }

            Optional<?> optional = get(typeOfField, (int) value);
            if(optional.isEmpty()){
                valuesMap.put(fieldName, null);
            } else {
                valuesMap.put(fieldName, optional.get());
            }
        }
    }

    private Object extractObjectFromResultSet(Class<?> type, ResultSet resultSet) throws SQLException, NoSuchFieldException, IllegalAccessException, InvocationTargetException, InstantiationException, NoSuchMethodException {
        Map<String, Object> valuesMap = getMapOfValues(resultSet);

        List<String> fieldNamesWithForeignKeys = ReflectionManager.getNameOfFieldsWithForeignKey(type);
        updateMapOfValues(type, valuesMap, fieldNamesWithForeignKeys);

        if(valuesMap.values().stream().allMatch(Objects::isNull)){
            return null;
        }

        Constructor<?> constructor = type.getConstructor();
        Object object = constructor.newInstance();

        ReflectionManager.setObjectFields(object, valuesMap);

        return object;
    }

    @Override
    public <T> Optional<T> get(Class<T> type, long id){
        try {
            entityAnnotationCheck(type);
            idAnnotationCheck(type);
            manyToOneAnnotationCheck(type);

            String name = ReflectionManager.getTableName(type);
            Field primaryKeyField = ReflectionManager.getIdField(type);
            String select = String.format("SELECT * FROM '%s' WHERE %s = ?;", name, ReflectionManager.getFieldName(primaryKeyField));

            PreparedStatement preparedStatement = connection.prepareStatement(select);
            preparedStatement.setLong(1, id);

            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.isClosed()) {
                return Optional.empty();
            }

            T object = (T) extractObjectFromResultSet(type, resultSet);

            resultSet.close();
            preparedStatement.close();

            if(object == null){
                return Optional.empty();
            } else {
                return Optional.of(object);
            }

        } catch (NoSuchFieldException | InvocationTargetException | IllegalAccessException | InstantiationException |
                 NoSuchMethodException | SQLException | PrimaryKeyException | MissingAnnotationException e) {
            throw new PersistenceException(e);
        }
    }

    @Override
    public <T> List<T> getAll(Class<T> type) {
        try {
            entityAnnotationCheck(type);
            idAnnotationCheck(type);
            manyToOneAnnotationCheck(type);

            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(String.format("SELECT * FROM '%s'", ReflectionManager.getTableName(type)));

            if(resultSet.isClosed()){
                return Collections.emptyList();
            }

            List<T> list = new ArrayList<>();

            while (resultSet.next()){
                T obj = (T) extractObjectFromResultSet(type, resultSet);
                list.add(obj);
            }

            resultSet.close();
            return list;
        } catch (SQLException | NoSuchFieldException | InvocationTargetException | IllegalAccessException |
                 NoSuchMethodException | InstantiationException | PrimaryKeyException | MissingAnnotationException e) {
            throw new PersistenceException(e);
        }


    }

    @Override
    public <T> List<T> getBy(Class<T> type, String fieldName, Object value) {
        try {
            entityAnnotationCheck(type);
            idAnnotationCheck(type);
            manyToOneAnnotationCheck(type);

            String sql;

            if(value == null){
                sql = String.format("SELECT * FROM '%s' WHERE %s is ?",
                        ReflectionManager.getTableName(type), fieldName);
            } else {
                sql = String.format("SELECT * FROM '%s' WHERE %s = ?",
                        ReflectionManager.getTableName(type), fieldName);
            }

            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            preparedStatement.setObject(1, value);

            ResultSet resultSet = preparedStatement.executeQuery();
            List<T> list = new ArrayList<>();

            while (resultSet.next()){
                T obj = (T) extractObjectFromResultSet(type, resultSet);
                list.add(obj);
            }

            resultSet.close();
            return list;

        } catch (SQLException | NoSuchFieldException | InvocationTargetException | IllegalAccessException |
                 InstantiationException | NoSuchMethodException | PrimaryKeyException | MissingAnnotationException e) {
            throw new PersistenceException(e);
        }
    }

    private long saveObject(Object entity, Map<String, Object> fieldNamesWithValuesExceptPrimaryKey)
            throws SQLException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        String fieldNames = String.join(",", fieldNamesWithValuesExceptPrimaryKey.keySet());
        String placesForValues = String.join(",", fieldNamesWithValuesExceptPrimaryKey
                .values()
                .stream()
                .map(obj -> "?")
                .toList());

        String sql = String.format("INSERT INTO '%s' (%s) VALUES (%s)",
                ReflectionManager.getTableName(entity.getClass()), fieldNames, placesForValues);

        PreparedStatement statement = connection.prepareStatement(sql);

        int index = 1;
        Collection<Object> values = fieldNamesWithValuesExceptPrimaryKey.values();

        for (Object value : values) {
            statement.setObject(index, value);
            index++;
        }

        statement.execute();

        ResultSet resultSet = statement.getGeneratedKeys();

        long newId = resultSet.getLong(1);
        ReflectionManager.setObjectPrimaryKey(entity, newId);

        resultSet.close();
        return newId;
    }

    private void updateObject(Object entity, long id, Map<String, Object> fieldNamesWithValuesExceptPrimaryKey)
            throws SQLException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {

        Field primaryKeyField = ReflectionManager.getIdField(entity.getClass());

        if(primaryKeyField == null){
            throw new PersistenceException("Field with @Id annotation is missing");
        }

        List<String> list = fieldNamesWithValuesExceptPrimaryKey
                .entrySet()
                .stream()
                .map(entry -> String.format("%s = ?", entry.getKey()))
                .toList();

        String sql = String.format("UPDATE '%s' SET %s WHERE %s = ?",
                ReflectionManager.getTableName(entity.getClass()), String.join(",", list), ReflectionManager.getFieldName(primaryKeyField));

        PreparedStatement statement = connection.prepareStatement(sql);

        int index = 1;
        Collection<Object> values = fieldNamesWithValuesExceptPrimaryKey.values();

        for (Object value : values) {
            statement.setObject(index, value);
            index++;
        }

        statement.setLong(index, id);

        statement.execute();
        statement.close();
    }

    @Override
    public long save(Object entity) {
        try {
            entityAnnotationCheck(entity.getClass());
            idAnnotationCheck(entity.getClass());
            manyToOneAnnotationCheck(entity.getClass());

            long id = (long) ReflectionManager.getObjectPrimaryKey(entity);

            List<String> fieldNamesOfForeignKey = ReflectionManager.getNameOfFieldsWithForeignKey(entity.getClass());
            for (String fieldName : fieldNamesOfForeignKey) {
                Object obj = ReflectionManager.getValueOfFieldByName(entity, fieldName);
                if(obj != null) {
                    long foreignId = (long) ReflectionManager.getObjectPrimaryKey(obj);
                    if (foreignId == 0) {
                        save(obj);
                    }
                }
            }


            Map<String, Object> fieldNamesWithValuesExceptPrimaryKey = ReflectionManager
                    .getFieldNamesWithValuesExceptPrimaryKey(entity);

            if(id == 0){
                return saveObject(entity, fieldNamesWithValuesExceptPrimaryKey);
            } else {
                updateObject(entity, id, fieldNamesWithValuesExceptPrimaryKey);
                return id;
            }

        } catch (IllegalAccessException | SQLException | NoSuchFieldException | MissingAnnotationException |
                 PrimaryKeyException | InvocationTargetException | NoSuchMethodException e) {
            throw new PersistenceException(e);
        }
    }

    @Override
    public void delete(Object entity){
        try {
            entityAnnotationCheck(entity.getClass());
            idAnnotationCheck(entity.getClass());
            manyToOneAnnotationCheck(entity.getClass());

            long id  = (long) ReflectionManager.getObjectPrimaryKey(entity);
            Field primaryKeyField = ReflectionManager.getIdField(entity.getClass());


            String className = ReflectionManager.getTableName(entity.getClass());

            String sql = String.format("DELETE FROM '%s' WHERE %s=?;", className, ReflectionManager.getFieldName(primaryKeyField));
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            preparedStatement.setLong(1, id);

            preparedStatement.execute();
            preparedStatement.close();
        } catch (IllegalAccessException | SQLException | PrimaryKeyException | MissingAnnotationException |
                 InvocationTargetException | NoSuchMethodException e){
            throw new PersistenceException(e);
        }
    }
}
