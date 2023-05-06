package sk.tuke.meta.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
public @interface AtomicPersistenceOperatio {
}
