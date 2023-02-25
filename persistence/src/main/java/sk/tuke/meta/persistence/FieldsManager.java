package sk.tuke.meta.persistence;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

public class FieldsManager {

//    static public List<String> getStringsOfVariables(Map<String, Object> variables){
//        List<String> list = new ArrayList<>();
//
//        for(Map.Entry<String, Object> entry : variables.entrySet()){
//            if(entry.g)
//        }
//
//        return list;
//    }

    static public Map<String, String> getDeclaredVariablesWithSqlTypes(Object object) {
        Map<String, String> variables = Collections.emptyMap();

        for(Field field: object.getClass().getFields()){
            String name = field.getName();
            Object type  = field.getClass();

            if(type == CharSequence.class){
                variables.put(name, "TEXT");
            } else if(type == int.class){
                variables.put(name, "INTEGER");
            } else if(type == float.class || type == double.class){
                variables.put(name, "REAL");
            } else {
                System.err.println("Undefined type of variable.");
            }

        }

        return  variables;
    }

    static private Object getValueOfField(Object object, Field field){
        try {
            field.setAccessible(true);
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
