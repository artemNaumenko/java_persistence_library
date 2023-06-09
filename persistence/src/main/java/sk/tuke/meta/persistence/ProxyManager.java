package sk.tuke.meta.persistence;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

public class ProxyManager {

    private static <T> void setProxyFields(T proxy, T object) throws IllegalAccessException, NoSuchFieldException, InvocationTargetException, NoSuchMethodException {
        if(object == null){
            return;
        }

        Map<String, Object> fieldsWithValues = ReflectionManager.getFieldNamesWithValues(object);

        for (Map.Entry<String, Object> entry : fieldsWithValues.entrySet()) {
            Field proxyField = proxy.getClass().getSuperclass().getDeclaredField(entry.getKey());
            proxyField.setAccessible(true);
            proxyField.set(proxy, entry.getValue());
        }
    }

    public static <T> T createProxy(Connection connection, Class<T> targetClass, long id)
            throws InstantiationException, IllegalAccessException {




        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setSuperclass(targetClass);
        Class clazz = proxyFactory.createClass();

        MethodHandler handler = new MyMethodHandler(connection, targetClass, id);

        Object instance = clazz.newInstance();

        ((ProxyObject) instance).setHandler(handler);

        return (T) instance;
    }



    private static class MyMethodHandler implements MethodHandler{
        final private Class<?> targetEntity;
        final private long primaryKey;
        final private Connection connection;
        public MyMethodHandler(Connection connection, Class<?> targetEntity, long primaryKey) {
            this.targetEntity = targetEntity;
            this.primaryKey = primaryKey;
            this.connection = connection;
        }

        private <T> void loadObject(T proxy) throws IllegalAccessException, NoSuchFieldException, InvocationTargetException, NoSuchMethodException {
            Field idField = ReflectionManager.getIdField(targetEntity);
            idField.setAccessible(true);
            long idValue = (long) idField.get(proxy);

            if(idValue == 0 && primaryKey != 0){
                ReflectivePersistenceManager manager = new ReflectivePersistenceManager(connection);
                Optional<T> optional = (Optional<T>) manager.get(targetEntity, primaryKey);
                if(optional.isEmpty()){
                    proxy = null;
                } else {
                    setProxyFields(proxy, optional.get());
                }
            }
        }

        @Override
        public Object invoke(Object self, Method overridden, Method forwarder,
                             Object[] args) throws Throwable {

            loadObject(self);
            return forwarder.invoke(self, args);
        }
    }
}
