package sk.tuke.meta.processor;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import sk.tuke.meta.processor.exceptions.MissingAnnotationException;
import sk.tuke.meta.processor.exceptions.PrimaryKeyException;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.persistence.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes("javax.persistence.Entity")
public class CodeGeneratingProcessor extends AbstractProcessor {
    private static final String TEMPLATE_PATH = "sk/tuke/meta/processor/" ;

    private VelocityEngine velocity;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        velocity = new VelocityEngine();
        velocity.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        velocity.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWithAny(Set.of(Entity.class));

        if(elements.isEmpty()) {
            return true;
        }

        generateSqlFiles(elements);
        generateDAOs(elements);
        generatePersistenceManager(elements);

        return true;
    }

    private void generateDAOs(Set<? extends Element> elements) {
        for(Element element: elements) {
            try {
                generateClassDAO((TypeElement) element);
            } catch (IOException e){
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
            }
        }
    }
    private void generateSqlFiles(Set<? extends Element> elements) {
        for (Element element : elements) {
            try {
                JavaFileObject fileObject = (JavaFileObject) processingEnv.getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", "create" + element.getSimpleName() + "Table.sql");
                try (Writer sqlWriter = fileObject.openWriter()) {
                    TypeElement typeElement = (TypeElement) element;
                    String sql = generateSqlForTableCreation(typeElement);
                    sqlWriter.write(sql);
                }
            } catch (Exception e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.toString());
            }
        }
    }

    private void generatePersistenceManager(Set<? extends Element> elements){
        try {
            JavaFileObject fileObject = processingEnv.getFiler().createSourceFile("GeneratedPersistenceManager");

            List<String> imports = elements.stream()
                    .map(element -> element.getEnclosingElement().toString() + "." + element.getSimpleName().toString())
                    .toList();

            List<String> elementNames = elements.stream()
                    .map(element -> element.getSimpleName().toString())
                    .toList();

            try(Writer writer = fileObject.openWriter()){
                Template template = velocity.getTemplate(TEMPLATE_PATH + "GeneratedPersistenceManager.java.vm");

                VelocityContext context = new VelocityContext();
                context.put("package", ((TypeElement) elements.toArray()[0]).getEnclosingElement().toString());
                context.put("entities", elementNames);
                context.put("imports", imports);

                template.merge(context, writer);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        }
    }

    private void generateClassDAO(TypeElement entity) throws IOException {
        JavaFileObject fileObject = processingEnv.getFiler().createSourceFile(entity.toString() + "DAO");

        try(Writer writer = fileObject.openWriter()){
            Template template = velocity.getTemplate(TEMPLATE_PATH + "DAO.java.vm");

            List<String> columnsNames = TableManager.getNonIdFields(entity).stream()
                    .map(field -> {
                        try {
                            return TableManager.getColumnName(field);
                        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                            throw new PersistenceException(e);
                        }
                    })
                    .toList();

            List<String> fieldNames = TableManager.getNonIdFields(entity).stream()
                    .map(field -> capitalize(TableManager.getFieldName(field)))
                    .toList();

            List<String> nonReferenceFields = TableManager.getNonManyToOneFields(entity).stream()
                    .filter(field -> field.getAnnotation(Id.class) == null)
                    .map(field -> capitalize(TableManager.getFieldName(field)))
                    .toList();

            List<String> nonReferenceFieldsWithId = TableManager.getNonManyToOneFields(entity).stream()
                    .map(field -> capitalize(TableManager.getFieldName(field)))
                    .map(name -> capitalize(name))
                    .toList();

            List<String> referenceFields = TableManager.getManyToOneVariables(entity).stream()
                    .map(field -> capitalize(TableManager.getFieldName(field)))
                    .toList();

            List<String> referenceFieldTypes = TableManager.getManyToOneVariables(entity).stream()
                    .map(field -> field.asType().toString())
                    .toList();

            List<String> nonReferenceFieldTypes = TableManager.getNonManyToOneFields(entity).stream()
                    .map(field -> field.asType().toString())
                    .toList();

            List<String> referenceFieldIds = TableManager.getManyToOneVariables(entity).stream()
                    .map(elem -> TableManager.getIdField(elem).getSimpleName().toString())
                    .map(name -> capitalize(name))
                    .toList();

            List<String> nonReferenceColumns = TableManager.getNonManyToOneFields(entity)
                    .stream()
                    .map(field -> {
                        try {
                            return TableManager.getColumnName(field);
                        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                            throw new PersistenceException(e);
                        }
                    })
                    .toList();

            List<String> referenceColumnWithDefaultFetching = TableManager.getManyToOneVariablesDefaultFetching(entity)
                    .stream()
                    .map(field -> {
                        try {
                            return TableManager.getColumnName(field);
                        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                            throw new PersistenceException(e);
                        }
                    })
                    .toList();

            List<String> referenceFieldsWithDefaultFetchingTypes = TableManager.getManyToOneVariablesDefaultFetching(entity)
                    .stream()
                    .map(field -> field.asType().toString())
                    .toList();

            List<String> referenceFieldsWithDefaultFetching = TableManager.getManyToOneVariablesDefaultFetching(entity)
                    .stream()
                    .map(field -> TableManager.getFieldName(field))
                    .map(name -> capitalize(name))
                    .toList();


            VelocityContext context = new VelocityContext();
            context.put("package", entity.getEnclosingElement().toString());
            context.put("entity", entity.getSimpleName().toString());
            context.put("tableName", TableManager.getTableName(entity));
            context.put("entityLowerCase", entity.getSimpleName().toString().toLowerCase());
            context.put("idName", TableManager.getColumnName(TableManager.getIdField(entity)));
            context.put("capitalizedIdName", capitalize(TableManager.getFieldName(TableManager.getIdField(entity))));
            context.put("columnsNames", columnsNames);
            context.put("fieldNames", fieldNames);
            context.put("nonReferenceFields", nonReferenceFields);
            context.put("referenceFields", referenceFields);
            context.put("referenceFieldTypes", referenceFieldTypes);
            context.put("referenceFieldIds", referenceFieldIds);
            context.put("nonReferenceColumns", nonReferenceColumns);
            context.put("nonReferenceFieldsWithId", nonReferenceFieldsWithId);
            context.put("nonReferenceFieldTypes", nonReferenceFieldTypes);
            context.put("referenceColumnWithDefaultFetching", referenceColumnWithDefaultFetching);
            context.put("referenceFieldsWithDefaultFetchingTypes", referenceFieldsWithDefaultFetchingTypes);
            context.put("referenceFieldsWithDefaultFetching", referenceFieldsWithDefaultFetching);


            template.merge(context, writer);
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            throw new PersistenceException(e);
        }
    }

    private String generateSqlForTableCreation(TypeElement element) throws Exception {
        entityAnnotationCheck(element);
        idAnnotationCheck(element);
        manyToOneAnnotationCheck(element);

        List<String> strings = new ArrayList<>();
        List<? extends VariableElement> fields = TableManager.getFields(element);

        for (VariableElement field : fields) {
            String str = String.format("\n    '%s' ", TableManager.getColumnName(field));

            if (field.getAnnotation(Transient.class) != null){
                continue;
            } else if(field.getAnnotation(Id.class) != null){
                str += "INTEGER PRIMARY KEY AUTOINCREMENT";
                strings.add(str);
                continue;
            } else if (field.getAnnotation(ManyToOne.class) != null) {
                str += "INTEGER";
                strings.add(str);
                String idFieldName = TableManager.getColumnName(TableManager.getIdField(field));

                String freignKeyString = String.format(
                        "\n    FOREIGN KEY ('%s') REFERENCES '%s'('%s')",
                        TableManager.getColumnName(field),
                        TableManager.getColumnName(field),
                        idFieldName);

                strings.add(freignKeyString);
                continue;
            }

            str += TableManager.getSqlTypeForField(field);
            if(field.getAnnotation(Column.class) != null){
                Annotation annotation = field.getAnnotation(Column.class);

                boolean nullable = (boolean) annotation.getClass().getMethod("nullable").invoke(annotation);
                boolean unique = (boolean) annotation.getClass().getMethod("unique").invoke(annotation);

                if(nullable){
                    str += " NULL";
                } else {
                    str += " NOT NULL";
                }

                if(unique){
                    str += " UNIQUE";
                }
            }

            strings.add(str);

        }

        String sql = String.format("CREATE TABLE IF NOT EXISTS '%s' (%s\n);\n\n",
                TableManager.getTableName(element), String.join(", ", strings));
        return sql;
    }

    private void entityAnnotationCheck(Element element) throws MissingAnnotationException {
        if(element.getAnnotation(Entity.class) != null){
            return;
        }
        throw new MissingAnnotationException(element.getClass().getName() + " does not have Entity annotation.");
    }

    private void idAnnotationCheck(Element element) throws PrimaryKeyException {
        VariableElement variableElement = TableManager.getIdField(element);

        if(variableElement == null){
            throw new PrimaryKeyException("Entity (" + element.getClass().getName() +
                    ") does not have field with Id annotation.");
        }

        if(!variableElement.asType().toString().equals("long")){
            throw new PrimaryKeyException("Field (" + element.getClass().getName() + ") with Id annotation must be long.");
        }

    }

    private void manyToOneAnnotationCheck(Element element) throws PrimaryKeyException {
        for(VariableElement variableElement: TableManager.getManyToOneVariables(element)){
            idAnnotationCheck(variableElement);
        }
    }

    private String capitalize(String str){
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

