package sk.tuke.meta.example;

import sk.tuke.meta.persistence.GeneratedPersistenceManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

public class Main {
    public static final String DB_PATH = "test.db";

    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);

        conn.createStatement().execute("DROP TABLE IF EXISTS PersonTable");
        conn.createStatement().execute("DROP TABLE IF EXISTS Department");


        GeneratedPersistenceManager manager = new GeneratedPersistenceManager(conn);
        manager.createTables();

        Department development = new Department("Development", "DVLP");
        Department marketing = new Department("Marketing", "MARK");
        Department operations = new Department("Operations", "OPRS");

        Person hrasko = new Person("Janko", "Hrasko", 30);
        hrasko.setDepartment(development);
        Person mrkvicka = new Person("Jozko", "Mrkvicka", 25);
        mrkvicka.setDepartment(development);
        Person novak = new Person("Jan", "Novak", 45);
        novak.setDepartment(marketing);

        manager.save(hrasko);
        manager.save(mrkvicka);
        manager.save(novak);

        List<Person> persons = manager.getAll(Person.class);
        for (Person person : persons) {
            System.out.println(person);
            System.out.println("  " + person.getDepartment());
        }

//        Person person = manager.get(Person.class, 1).get();
//        Department department = person.getDepartment();
//
////        department.setCode("NEW");
//        person.setAge(333);
//
//        manager.save(department);

        conn.close();
    }

}
