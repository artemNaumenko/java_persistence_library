package sk.tuke.meta.processor;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes({"javax.persistence.Table", "javax.persistence.Entity"})
public class SqlTableCreator extends AbstractProcessor {
    private JavaFileObject fileObject;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        try {
            fileObject = (JavaFileObject) processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", "createTable.sql");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWithAny(Set.of(Table.class, Entity.class));

        if(elements.isEmpty()){
            return true;
        }

        try {
            try (Writer  writer = fileObject.openWriter()) {
                for (Element element : elements) {
                    TypeElement typeElement = (TypeElement) element;
                    String sql = generateSqlForTableCreation(typeElement);
                    writer.write(sql);
                }
            }

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        }

        return true;
    }

    private String generateSqlForTableCreation(TypeElement element) throws Exception {
        // TODO add checks

        List<String> strings = new ArrayList<>();
        List<? extends VariableElement> fields = TableManager.getFields(element);

        for (VariableElement field : fields) {
            String str = String.format("\n    '%s' ", TableManager.getFieldName(field));

            if (field.getAnnotation(Transient.class) != null){
                continue;
            } else if(field.getAnnotation(Id.class) != null){
                str += "INTEGER PRIMARY KEY AUTOINCREMENT";
                strings.add(str);
                continue;
            } else if (field.getAnnotation(ManyToOne.class) != null) {
                str += "INTEGER";
                strings.add(str);

                String freignKeyString = String.format(
                        "\n    FOREIGN KEY ('%s') REFERENCES '%s'('%s')",
                        TableManager.getFieldName(field),
                        TableManager.getFieldName(field),
                        TableManager.getFieldName(TableManager.getIdField(field)));

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
}
