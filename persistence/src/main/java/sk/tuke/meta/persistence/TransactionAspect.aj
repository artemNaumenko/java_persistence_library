package sk.tuke.meta.persistence;

import javax.persistence.PersistenceException;
import java.sql.SQLException;

public aspect TransactionAspect {
    PersistenceManager manager;

    pointcut objectInitialization(): initialization(PersistenceManager.new(..));
    after(PersistenceManager manager): objectInitialization() && this(manager){
        this.manager = manager;
    }

    pointcut onAtomicOperation(): execution( @sk.tuke.meta.persistence.AtomicPersistenceOperation * *(..));
    Object around(): onAtomicOperation(){
        try {
            manager.startTransaction();
            Object result = proceed();
            manager.commitTransaction();
            return result;
        } catch (SQLException | PersistenceException e){
            try {
                manager.rollbackTransaction();
                return null;
            } catch (SQLException ex) {
                throw new PersistenceException(ex);
            }
        }
    }

}
