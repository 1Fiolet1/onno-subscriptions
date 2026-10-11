package ru.seleznev.onnosubscriptions.support;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/** Access to Spring services from Onno entities instantiated by reflection. */
@Component
public class ApplicationBeans implements ApplicationContextAware {
    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static <T> T get(Class<T> type) {
        ApplicationContext current = context;
        if (current == null) {
            throw new IllegalStateException("Application context has not been initialized");
        }
        return current.getBean(type);
    }
}
