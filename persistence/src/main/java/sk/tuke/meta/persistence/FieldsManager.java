package sk.tuke.meta.persistence;

import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FieldsManager {

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

    static public List<String> getNameOfFieldsWithForeignKey(Class<?> objectClass){
        List<String> nameOfFields = new ArrayList<>();
        for (Field field : objectClass.getDeclaredFields()) {
            if(field.getAnnotation(ManyToOne.class) != null){
                nameOfFields.add(field.getName());
            }
        }
        return nameOfFields;
    }

    static public void setObjectFields(Object object, Map<String, Object> map)
            throws NoSuchFieldException, IllegalAccessException {

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Field field = object.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(object, entry.getValue());
        }
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
