package sk.tuke.meta.persistence;

import org.jetbrains.annotations.NotNull;
import sk.tuke.meta.persistence.exceptions.MissingAnnotationException;
import sk.tuke.meta.persistence.exceptions.PrimaryKeyException;
import sk.tuke.meta.persistence.exceptions.UnhandledTypeException;

import javax.persistence.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;

public class ReflectivePersistenceManager implements PersistenceManager {
    Connection connection;
    List<?> types;

    public ReflectivePersistenceManager(Connection connection, Class<?>... types) {
        this.connection = connection;
        this.types = List.of(types);
    }


    private @NotNull String getSqlTypeForField(@NotNull Field field) throws UnhandledTypeException {
        Class<?> type = field.getType();

        if (type == String.class) {
            return "TEXT";
        } else if(type == int.class || type == long.class){
            return "INTEGER";
        } else if(type == float.class || type == double.class){
            return "REAL";
        } else {
            throw new UnhandledTypeException("Unhandled type ( " + type.getSimpleName() + ") of variable.");
        }
    }



    private void entityAnnotationCheck(@NotNull Class<?> objectClass) throws MissingAnnotationException {
        if(objectClass.getAnnotation(Entity.class) != null){
            return;
        }
        throw new MissingAnnotationException(objectClass.getName() + " does not have Entity annotation.");
    }

    private void idAnnotationCheck(@NotNull Class<?> objectClass) throws PrimaryKeyException {
        Field idField = FieldsManager.getIdField(objectClass);

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

    @Override
    public void createTables(){
        for (Object object : types) {
            Class<?> objectClass = (Class<?>) object;

            try {
                entityAnnotationCheck(objectClass);
                idAnnotationCheck(objectClass);
                manyToOneAnnotationCheck(objectClass);
            } catch (Exception e) {
                throw new PersistenceException(e);
            }

            List<String> strings = new ArrayList<>();
            boolean hasIncorrectFields = false;

            for (Field field : objectClass.getDeclaredFields()) {
                String str = field.getName() + " ";
                Annotation[] annotations = field.getDeclaredAnnotations();

                if (annotations.length == 0) {
                    try {
                        str += getSqlTypeForField(field);
                        strings.add(str);
                    } catch (UnhandledTypeException e) {
                        throw new PersistenceException(e);
                    }
                } else {
                    for(Annotation annotation: annotations){
                        if(annotation.annotationType() == Transient.class){
                            break;
                        } else if(annotation.annotationType() == Id.class){
                            str += "INTEGER PRIMARY KEY AUTOINCREMENT";
                            strings.add(str);
                        } else if(annotation.annotationType() == ManyToOne.class){
                            str += "INTEGER";
                            strings.add(str);
                            String freignKeyString = String.format(
                                    "FOREIGN KEY (%s) REFERENCES %s(%s)",
                                    field.getName(),
                                    field.getName(),
                                    FieldsManager.getIdField(field.getType()).getName());

                            strings.add(freignKeyString);
                        }
                    }
                }

            }

            try {
                if (!hasIncorrectFields) {
                    String sql = String.format("CREATE TABLE IF NOT EXISTS %s (%s);",
                            objectClass.getSimpleName(), String.join(", ", strings));

                    connection.createStatement().execute(sql);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

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

    private void updateMapOfValues(Class<?> type, Map<String, Object> valuesMap, List<String> fieldNamesWithForeignKeys) throws NoSuchFieldException{
        for (String fieldName : fieldNamesWithForeignKeys) {
            Class<?> typeOfField = type.getDeclaredField(fieldName).getType();
            Object value = valuesMap.get(fieldName);

            if(value == null){
                valuesMap.put(fieldName, null);
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

        List<String> fieldNamesWithForeignKeys = FieldsManager.getNameOfFieldsWithForeignKey(type);
        updateMapOfValues(type, valuesMap, fieldNamesWithForeignKeys);

        if(valuesMap.values().stream().allMatch(Objects::isNull)){
            return null;
        }

        Constructor<?> constructor = type.getConstructor();
        Object object = constructor.newInstance();

        FieldsManager.setObjectFields(object, valuesMap);

        return object;
    }

    @Override
    public <T> Optional<T> get(Class<T> type, long id){
        try {
            entityAnnotationCheck(type);
            idAnnotationCheck(type);
            manyToOneAnnotationCheck(type);

            String name = type.getSimpleName();
            Field primaryKeyField = FieldsManager.getIdField(type);
            String select = String.format("SELECT * FROM %s WHERE %s=?;", name, primaryKeyField.getName());

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
            ResultSet resultSet = statement.executeQuery("SELECT * FROM " + type.getSimpleName());

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
                sql = String.format("SELECT * FROM %s WHERE %s is ?",
                        type.getSimpleName(), fieldName);
            } else {
                sql = String.format("SELECT * FROM %s WHERE %s = ?",
                        type.getSimpleName(), fieldName);
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
            throws SQLException, IllegalAccessException {
        String fieldNames = String.join(",", fieldNamesWithValuesExceptPrimaryKey.keySet());
        String placesForValues = String.join(",", fieldNamesWithValuesExceptPrimaryKey
                .values()
                .stream()
                .map(obj -> "?")
                .toList());

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                entity.getClass().getSimpleName(), fieldNames, placesForValues);

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
        FieldsManager.setObjectPrimaryKey(entity, newId);

        resultSet.close();
        return newId;
    }

    private void updateObject(Object entity, long id, Map<String, Object> fieldNamesWithValuesExceptPrimaryKey)
            throws SQLException {

        Field primaryKeyField = FieldsManager.getIdField(entity.getClass());

        if(primaryKeyField == null){
            throw new PersistenceException("Field with @Id annotation is missing");
        }

        List<String> list = fieldNamesWithValuesExceptPrimaryKey
                .entrySet()
                .stream()
                .map(entry -> String.format("%s = ?", entry.getKey()))
                .toList();

        String sql = String.format("UPDATE %s SET %s WHERE %s = ?",
                entity.getClass().getSimpleName(), String.join(",", list), primaryKeyField.getName());

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

            long id = (long) FieldsManager.getObjectPrimaryKey(entity);;

            List<String> fieldNamesOfForeignKey = FieldsManager.getNameOfFieldsWithForeignKey(entity.getClass());
            for (String fieldName : fieldNamesOfForeignKey) {
                Object obj = FieldsManager.getValueOfFieldByName(entity, fieldName);
                if(obj != null) {
                    long foreignId = (long) FieldsManager.getObjectPrimaryKey(obj);
                    if (foreignId == 0) {
                        save(obj);
                    }
                }
            }


            Map<String, Object> fieldNamesWithValuesExceptPrimaryKey = FieldsManager
                    .getFieldNamesWithValuesExceptPrimaryKey(entity);

            if(id == 0){
                return saveObject(entity, fieldNamesWithValuesExceptPrimaryKey);
            } else {
                updateObject(entity, id, fieldNamesWithValuesExceptPrimaryKey);
                return id;
            }

        } catch (IllegalAccessException | SQLException | NoSuchFieldException | MissingAnnotationException |
                 PrimaryKeyException e) {
            throw new PersistenceException(e);
        }
    }

    @Override
    public void delete(Object entity){
        try {
            entityAnnotationCheck(entity.getClass());
            idAnnotationCheck(entity.getClass());
            manyToOneAnnotationCheck(entity.getClass());

            long id  = (long) FieldsManager.getObjectPrimaryKey(entity);
            Field primaryKeyField = FieldsManager.getIdField(entity.getClass());


            String className = entity.getClass().getSimpleName();

            String sql = String.format("DELETE FROM %s WHERE %s=?;", className, primaryKeyField.getName());
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            preparedStatement.setLong(1, id);

            preparedStatement.execute();
            preparedStatement.close();
        } catch (IllegalAccessException | SQLException | PrimaryKeyException | MissingAnnotationException e){
            throw new PersistenceException(e);
        }
    }
}
