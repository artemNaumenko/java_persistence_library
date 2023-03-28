package sk.tuke.meta.persistence;

import javax.persistence.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FieldsManager {

    static public String getTableName(Class<?> type) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if(type.isAnnotationPresent(Table.class)){
            Annotation annotation = type.getAnnotation(Table.class);
            String name = (String) annotation.getClass().getMethod("name").invoke(annotation);
            return (name != null && name.length() > 0) ? name : type.getSimpleName();
        }

        return type.getSimpleName();
    }

    static public String getFieldName(Field field) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if(field.isAnnotationPresent(Column.class)){
            Annotation annotation = field.getAnnotation(Column.class);
            String name = (String) annotation.getClass().getMethod("name").invoke(annotation);
            return (name != null && name.length() > 0) ? name : field.getName();
        }

        return field.getName();
    }

    static boolean isUniqueField(Field field) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if(field.isAnnotationPresent(Column.class)){
            Annotation annotation = field.getAnnotation(Column.class);
            return (boolean) annotation.getClass().getMethod("unique").invoke(annotation);
        }

        return false;
    }

    static  boolean isNullableField(Field field) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if(field.isAnnotationPresent(Column.class)){
            Annotation annotation = field.getAnnotation(Column.class);
            return (boolean) annotation.getClass().getMethod("nullable").invoke(annotation);
        }

        return true;
    }

    static boolean isTransientField(Field field){
        return field.isAnnotationPresent(Transient.class);
    }

    static boolean isPrimaryKeyField(Field field){
        return field.isAnnotationPresent(Id.class);
    }

    static boolean isManyToOneField(Field field){
        return field.isAnnotationPresent(ManyToOne.class);
    }



    static public Object getValueOfFieldByName(Object object, String name) throws IllegalAccessException, NoSuchFieldException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static public Object getObjectPrimaryKey(Object object) throws IllegalAccessException {

        if(object == null){
            return null;
        }

        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if( field.getAnnotation(Id.class) != null) {
                return field.get(object);
            }
        }

        return null;
    }

    static public void setObjectPrimaryKey(Object object, long id) throws IllegalAccessException {
        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if( field.getAnnotation(Id.class) != null) {
                field.set(object, id);
                break;
            }
        }
    }

    static public Field getIdField(Class<?> objectClass){
        for(Field field: objectClass.getDeclaredFields()){
            Annotation idAnnotation = field.getDeclaredAnnotation(Id.class);
            if(idAnnotation != null){
                return field;
            }
        }

        return null;
    }

    static public List<String> getNameOfFieldsWithForeignKey(Class<?> objectClass) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        List<String> nameOfFields = new ArrayList<>();
        for (Field field : objectClass.getDeclaredFields()) {
            if(field.getAnnotation(ManyToOne.class) != null){
                nameOfFields.add(getFieldName(field));
            }
        }
        return nameOfFields;
    }

    static public void setObjectFields(Object object, Map<String, Object> map)
            throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Field field = getFieldByName(object ,entry.getKey());
            field.setAccessible(true);
            field.set(object, entry.getValue());
        }
    }

    static public Field getFieldByName(Object obj, String name) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        for (Field field : obj.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if(field.isAnnotationPresent(Column.class)){
                Annotation annotation = field.getAnnotation(Column.class);
                String nameInAnnotation = (String) annotation.getClass().getMethod("name").invoke(annotation);

                if(nameInAnnotation.equals(name)){
                    return field;
                }
            }
        }

        return obj.getClass().getDeclaredField(name);
    }

    static public Map<String, Object> getFieldNamesWithValuesExceptPrimaryKey(Object object) throws IllegalAccessException {
        Map<String, Object> map = new HashMap<>();
        for (Field field : object.getClass().getDeclaredFields()) {
            boolean isPrimaryKey = field.getAnnotation(Id.class) != null;
            boolean isFieldIgnored = field.getAnnotation(Transient.class) != null;

            if(!isPrimaryKey && !isFieldIgnored){
                field.setAccessible(true);

                boolean isManyToOne = field.getAnnotation(ManyToOne.class) != null;

                if(isManyToOne){
                    map.put(field.getName(), getObjectPrimaryKey(field.get(object)));
                } else {
                    map.put(field.getName(), field.get(object));
                }
            }
        }

        return map;
    }
}
