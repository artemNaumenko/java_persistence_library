package sk.tuke.meta.persistence;

import org.jetbrains.annotations.NotNull;
import sk.tuke.meta.persistence.exceptions.MissingAnnotationException;
import sk.tuke.meta.persistence.exceptions.PrimaryKeyException;
import sk.tuke.meta.persistence.exceptions.UnhandledTypeException;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
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
        Class type = field.getType();

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

    private void idAnnotationCheck(@NotNull Class objectClass) throws PrimaryKeyException {
        Field idField = FieldsManager.getIdField(objectClass);

        if(idField == null){
            throw new PrimaryKeyException("Entity (" + objectClass.getName() +
                    ") does not have field with Id annotation.");
        }

        if(idField.getType() != long.class){
            throw new PrimaryKeyException("Field (" + idField.getName() + ") with Id annotation must be long.");
        }

    }

    private void manyToOneAnnotationCheck(@NotNull Class objectClass)
            throws MissingAnnotationException, PrimaryKeyException {
        for(Field field: objectClass.getDeclaredFields()){
            Annotation manyToOneAnnotation = field.getDeclaredAnnotation(ManyToOne.class);
            if(manyToOneAnnotation != null) {
                Class referenceType = field.getType();
                entityAnnotationCheck(referenceType);
                idAnnotationCheck(referenceType);
            }
        }
    }

    @Override
    public void createTables(){ //TODO refactor this function
        for (Object object : types) {
            Class objectClass = (Class) object;

            try {
                entityAnnotationCheck(objectClass);
                idAnnotationCheck(objectClass);
                manyToOneAnnotationCheck(objectClass);
            } catch (Exception e) {
                System.err.println(e);
                System.err.println("Table " + objectClass.getSimpleName() + " was not created.");
                continue;
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
                        System.err.println(e);
                        System.err.println("Table " + objectClass.getSimpleName() + " was not created.");
                        hasIncorrectFields = true;
                        break;
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


    private List<Class> getTypesOfFieldsWithoutPrimaryKey(Class type){
        List<Class> types = new ArrayList<>();
        for (Field declaredField : type.getDeclaredFields()) {
            boolean isPrimaryKey = declaredField.getAnnotation(Id.class) != null;
            boolean isFieldIgnored = declaredField.getAnnotation(Transient.class) != null;

            if(isPrimaryKey || isFieldIgnored){
                continue;
            }

            types.add(declaredField.getType());
        }

        return types;
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

    private void updateMapOfValues(Class<?> type, Map<String, Object> valuesMap, List<String> fieldNamesWithForeignKeys) throws NoSuchFieldException, SQLException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        for (String fieldName : fieldNamesWithForeignKeys) {
            Class<?> typeOfField = type.getDeclaredField(fieldName).getType();
            Optional<?> optional = get(typeOfField, (int) valuesMap.get(fieldName));
            if(optional.isEmpty()){
                valuesMap.put(fieldName, null);
            } else {
                valuesMap.put(fieldName, optional.get());
            }
        }
    }

    @Override
    public <T> Optional<T> get(Class<T> type, long id) throws SQLException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchFieldException {
        String name = type.getSimpleName();
        Field primaryKeyField = FieldsManager.getIdField(type);
        String select = String.format("SELECT * FROM %s WHERE %s=%s;", name, primaryKeyField.getName(), id);

        ResultSet resultSet = connection.createStatement().executeQuery(select);

        if(resultSet.isClosed()){
            return Optional.empty();
        }

        Map<String, Object> valuesMap = getMapOfValues(resultSet);
        resultSet.close();

        List<String> fieldNamesWithForeignKeys = FieldsManager.getNameOfFieldsWithForeignKey(type);
        updateMapOfValues(type, valuesMap, fieldNamesWithForeignKeys);

        Constructor<T> constructor = type.getConstructor();
        T object = constructor.newInstance();

        FieldsManager.setObjectFields(object, valuesMap);

        return Optional.of(object);
    }

    @Override
    public <T> List<T> getAll(Class<T> type) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<T> getBy(Class<T> type, String fieldName, Object value) {
        return Collections.emptyList();
    }

    @Override
    public long save(Object entity) {
        return 0;
    }

    @Override
    public void delete(Object entity) {
    }
}
