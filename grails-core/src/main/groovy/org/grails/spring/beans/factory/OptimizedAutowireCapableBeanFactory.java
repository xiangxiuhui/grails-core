/*
 * Copyright 2004-2005 Graeme Rocher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.spring.beans.factory;

import grails.util.Environment;
import grails.util.GrailsUtil;
import groovy.lang.Closure;
import org.springframework.beans.*;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.CglibSubclassingInstantiationStrategy;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.util.ClassUtils;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
/**
 *
 * Applies autowiring performance optimizations to Spring
 *
 * @author Graeme Rocher
 * @author Lari Hotari
 */
public class OptimizedAutowireCapableBeanFactory extends DefaultListableBeanFactory {

    public static boolean DISABLE_AUTOWIRE_BY_NAME_OPTIMIZATIONS = Boolean.getBoolean("grails.disable.optimization.autowirebyname");

    ConcurrentMap<Class<?>, Map<String,PropertyDescriptor>> autowireableBeanPropsCacheForClass =
            new ConcurrentHashMap<Class<?>, Map<String,PropertyDescriptor>>();
    private boolean reloadEnabled;

    /**
     * Default constructor.
     */
    public OptimizedAutowireCapableBeanFactory() {
        reloadEnabled = GrailsUtil.isDevelopmentEnv() || Environment.getCurrent().isReloadEnabled();
        if (reloadEnabled) {

            // Implementation note: The default Spring InstantiationStrategy caches constructors.
            // This is no good at development time because if the class reloads then Spring
            // continues to use the old class. We deal with this here by disabling the caching
            // for development time only
            setInstantiationStrategy(new CglibSubclassingInstantiationStrategy() {
                @Override
                public Object instantiate(RootBeanDefinition beanDefinition, String beanName, BeanFactory owner) {
                    // Don't override the class with CGLIB if no overrides.
                    if (beanDefinition.getMethodOverrides().isEmpty()) {
                        Constructor<?> constructorToUse;
                        Class<?> clazz = beanDefinition.getBeanClass();
                        if (clazz.isInterface()) {
                            throw new BeanInstantiationException(clazz, "Specified class is an interface");
                        }
                        try {
                            constructorToUse = clazz.getDeclaredConstructor((Class[]) null);
                        } catch (Exception ex) {
                            throw new BeanInstantiationException(clazz, "No default constructor found", ex);
                        }

                        return BeanUtils.instantiateClass(constructorToUse);
                    }
                    // Must generate CGLIB subclass.
                    return instantiateWithMethodInjection(beanDefinition, beanName, owner);
                }
            });
        }

        setParameterNameDiscoverer(new LocalVariableTableParameterNameDiscoverer());
        setAutowireCandidateResolver(new QualifierAnnotationAutowireCandidateResolver());
        ignoreDependencyType(Closure.class);
    }



    @Override
    protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
        // exclude properties generated by the groovy compiler from autowiring checks
        return pd.getName().indexOf('$') > -1 || super.isExcludedFromDependencyCheck(pd);
    }

    @Override
    public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck) throws BeansException {
        if (Environment.isInitializing()) {
            return;
        }
        if (autowireMode == AUTOWIRE_BY_NAME) {
            if (DISABLE_AUTOWIRE_BY_NAME_OPTIMIZATIONS || dependencyCheck || existingBean instanceof Aware) {
                super.autowireBeanProperties(existingBean, autowireMode, dependencyCheck);
            } else {
                try {
                    populateBeanInAutowireByName(existingBean);
                } catch (Exception e) {
                    logger.error("Bean couldn't be autowired using grails optimization: " + e.getMessage());
                    logger.error("Retrying using spring autowire");

                    // Remove the cache value in order to asure there is no problem with a previous value
                    Class<?> beanClass = ClassUtils.getUserClass(existingBean.getClass());
                    autowireableBeanPropsCacheForClass.remove(beanClass);

                    // Calls the spring method
                    super.autowireBeanProperties(existingBean, autowireMode, dependencyCheck);
                }
            }
        } else {
            super.autowireBeanProperties(existingBean, autowireMode, dependencyCheck);
        }
    }

    @Override
    protected void autowireByName(String beanName, AbstractBeanDefinition mbd, final BeanWrapper bw, MutablePropertyValues pvs) {
        if (!DISABLE_AUTOWIRE_BY_NAME_OPTIMIZATIONS && mbd.isPrototype()) {
            Map<String, PropertyDescriptor> autowireableBeanProps = resolveAutowireablePropertyDescriptorsForClass(bw.getWrappedClass(), new Callable<BeanWrapper>() {
                public BeanWrapper call() throws Exception {
                    return bw;
                }
            });
            for (Map.Entry<String, PropertyDescriptor> entry : autowireableBeanProps.entrySet()) {
                final PropertyDescriptor pd = entry.getValue();
                final String propertyName = pd.getName();
                if (!pvs.contains(propertyName)) {
                    final String otherBeanName = entry.getKey();
                    final Object otherBean = getBean(otherBeanName);
                    pvs.add(propertyName, otherBean);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Added autowiring by name from bean name '" + beanName +
                                "' via property '" + propertyName + "' to bean named '" + propertyName + "'");
                    }
                }
            }
        } else {
            super.autowireByName(beanName, mbd, bw, pvs);
        }
    }

    protected void populateBeanInAutowireByName(final Object existingBean) {
        // list of bean properties for that a bean exists
        Map<String, PropertyDescriptor> autowireableBeanProps = resolveAutowireablePropertyDescriptors(existingBean);

        // apply autowire instances directly without all the layers of Spring
        autowireBeanInAutowireByName(existingBean, autowireableBeanProps);
    }

    protected void autowireBeanInAutowireByName(final Object existingBean, Map<String, PropertyDescriptor> autowireableBeanProps) {
        for (Map.Entry<String, PropertyDescriptor> entry : autowireableBeanProps.entrySet()) {
            final PropertyDescriptor pd = entry.getValue();
            final Method writeMethod = pd.getWriteMethod();
            final String beanName = entry.getKey();
            final Object value = getBean(beanName);
            try {
                if (System.getSecurityManager() != null) {
                    try {
                        AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                            public Object run() throws Exception {
                                writeMethod.invoke(existingBean, value);
                                return null;
                            }
                        }, getAccessControlContext());
                    }
                    catch (PrivilegedActionException ex) {
                        throw ex.getException();
                    }
                }
                else {
                    writeMethod.invoke(existingBean, value);
                }
            }
            catch (TypeMismatchException ex) {
                throw ex;
            }
            catch (InvocationTargetException ex) {
                PropertyChangeEvent propertyChangeEvent = new PropertyChangeEvent(existingBean, beanName, null, value);
                if (ex.getTargetException() instanceof ClassCastException) {
                    throw new TypeMismatchException(propertyChangeEvent, pd.getPropertyType(), ex.getTargetException());
                }
                throw new MethodInvocationException(propertyChangeEvent, ex.getTargetException());
            }
            catch (Exception ex) {
                PropertyChangeEvent pce = new PropertyChangeEvent(existingBean, beanName, null, value);
                throw new MethodInvocationException(pce, ex);
            }
        }
    }

    protected Map<String, PropertyDescriptor> resolveAutowireablePropertyDescriptors(final Object existingBean) {
        return resolveAutowireablePropertyDescriptorsForClass(existingBean.getClass(), new Callable<BeanWrapper>() {
            public BeanWrapper call() throws Exception {
                BeanWrapperImpl bw = new BeanWrapperImpl(false);
                Class userClass = ClassUtils.getUserClass(existingBean.getClass());
                if(userClass != existingBean.getClass()) {
                    bw.setWrappedInstance(BeanUtils.instantiate(userClass));
                } else {
                    bw.setWrappedInstance(existingBean);
                }
                bw.setConversionService(getConversionService());
                return bw;
            }
        });
    }

    protected Map<String, PropertyDescriptor> resolveAutowireablePropertyDescriptorsForClass(Class<?> beanClass, final Callable<BeanWrapper> beanWrapperCallback) {
        beanClass = ClassUtils.getUserClass(beanClass);
        Map<String, PropertyDescriptor> autowireableBeanProps = autowireableBeanPropsCacheForClass.get(beanClass);
        if (autowireableBeanProps == null) {
            autowireableBeanProps = new HashMap<String, PropertyDescriptor>();
            BeanWrapper bw=null;
            try {
                bw = beanWrapperCallback.call();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            PropertyDescriptor[] pds = bw.getPropertyDescriptors();
            for (PropertyDescriptor pd : pds) {
                if (containsBean(pd.getName()) && pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd)
                        && !BeanUtils.isSimpleProperty(pd.getPropertyType())) {
                    final Method writeMethod = pd.getWriteMethod();
                    if (!Modifier.isPublic(writeMethod.getDeclaringClass().getModifiers()) && !writeMethod.isAccessible()) {
                        if (System.getSecurityManager() != null) {
                            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                                public Object run() {
                                    writeMethod.setAccessible(true);
                                    return null;
                                }
                            });
                        }
                        else {
                            writeMethod.setAccessible(true);
                        }
                    }
                    autowireableBeanProps.put(pd.getName(), pd);
                }
            }
            if (!reloadEnabled) {
                autowireableBeanPropsCacheForClass.put(beanClass, autowireableBeanProps);
            }
        }
        return autowireableBeanProps;
    }
}
