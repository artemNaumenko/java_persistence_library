package sk.tuke.meta.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.persistence.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class TableManager {

    static String getTableName(TypeElement element) throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {

        Annotation tableAnnotation = element.getAnnotation(Table.class);
        if(tableAnnotation != null){
            String tableName = (String) tableAnnotation.getClass().getMethod("name").invoke(tableAnnotation);
            return (tableName != null && tableName.length() > 0) ? tableName : element.getSimpleName().toString();
        }
        return element.getSimpleName().toString();
    }

    static List<? extends VariableElement> getFields(TypeElement element){
        return element.getEnclosedElements().stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .map(elem -> (VariableElement) elem)
                .toList();
    }

    static List<? extends VariableElement> getNonIdFields(TypeElement element){
        return element.getEnclosedElements().stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .filter(elem -> elem.getAnnotation(Id.class) == null)
                .map(elem -> (VariableElement) elem)
                .toList();
    }

    static String getColumnName(VariableElement element) throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {

        Annotation annotation = element.getAnnotation(Column.class);
        if(annotation != null){
            String fieldName = (String) annotation.getClass().getMethod("name").invoke(annotation);
            return (fieldName != null && fieldName.length() > 0) ? fieldName : element.getSimpleName().toString();
        }

        return element.getSimpleName().toString();
    }

    static String getFieldName(VariableElement element){
        return element.getSimpleName().toString();
    }

    static VariableElement getIdField(Element element){
        return ((DeclaredType) element.asType()).asElement().getEnclosedElements().stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .filter(elem -> elem.getAnnotation(Id.class) != null)
                .map(elem -> (VariableElement) elem)
                .toList().get(0);
    }

    static String getSqlTypeForField(VariableElement element) throws Exception {
        String type = element.asType().toString();

        if (type.equals(String.class.getName())) {
            return "TEXT";
        } else if(type.equals(int.class.getName()) || type.equals(long.class.getName())){
            return "INTEGER";
        } else if(type.equals(float.class.getName()) || type.equals(double.class.getName())){
            return "REAL";
        } else {
            throw new Exception("Unhandled type ( " + getColumnName(element) + ") of variable.");
        }
    }

    static List<VariableElement> getVariables(Element element){
        return ((DeclaredType) element.asType()).asElement().getEnclosedElements().stream()
                .filter(elem -> elem.getKind() == ElementKind.FIELD)
                .map(elem -> (VariableElement) elem)
                .toList();
    }

    static List<VariableElement> getManyToOneVariables(Element element){
        return  getVariables(element).stream()
                .filter(elem -> elem.getAnnotation(ManyToOne.class) != null)
                .toList();
    }

    static List<VariableElement> getManyToOneVariablesDefaultFetching(Element element){
        return  getManyToOneVariables(element).stream()
                .filter(elem -> elem.getAnnotation(ManyToOne.class).fetch() != FetchType.LAZY)
                .toList();
    }

    static List<VariableElement> getManyToOneVariablesLazyFetching(Element element){
        return  getManyToOneVariables(element).stream()
                .filter(elem -> elem.getAnnotation(ManyToOne.class).fetch() == FetchType.LAZY)
                .toList();
    }

    static  List<VariableElement> getNonManyToOneFields(Element element){
        return getVariables(element)
                .stream()
                .filter(elem -> elem.getAnnotation(ManyToOne.class) == null)
                .toList();
    }
}
