/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.context;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.bootstrap.BootstrapTakeoverMode;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

import org.apache.dubbo.config.spring.context.event.DubboAnnotationInitedEvent;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotationMetadata;

import static org.springframework.util.ObjectUtils.nullSafeEquals;

/**
 * The {@link ApplicationListener} for {@link DubboBootstrap}'s lifecycle when the {@link ContextRefreshedEvent}
 * and {@link ContextClosedEvent} raised
 *
 * @since 2.7.5
 */
public class DubboBootstrapApplicationListener implements ApplicationListener, ApplicationContextAware, Ordered {

    /**
     * The bean name of {@link DubboBootstrapApplicationListener}
     *
     * @since 2.7.6
     */
    public static final String BEAN_NAME = "dubboBootstrapApplicationListener";

    private final Log logger = LogFactory.getLog(getClass());

    private final DubboBootstrap dubboBootstrap;
    private ApplicationContext applicationContext;

    public DubboBootstrapApplicationListener() {
        this.dubboBootstrap = initBootstrap();
    }

    private DubboBootstrap initBootstrap() {
        /**
         * 里面初始化了一个配置管理器
         * @see DubboBootstrap#configManager
         */
        DubboBootstrap dubboBootstrap = DubboBootstrap.getInstance();
        if (dubboBootstrap.getTakeoverMode() != BootstrapTakeoverMode.MANUAL) {
            dubboBootstrap.setTakeoverMode(BootstrapTakeoverMode.SPRING);
        }
        return dubboBootstrap;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (isOriginalEventSource(event)) {
            /**
             * 这个事件会在spring创建bean之前调用
             */
            if (event instanceof DubboAnnotationInitedEvent) {
                // This event will be notified at AbstractApplicationContext.registerListeners(),
                // init dubbo config beans before spring singleton beans
                initDubboConfigBeans();
            } else if (event instanceof ApplicationContextEvent) {
                this.onApplicationContextEvent((ApplicationContextEvent) event);
            }
        }
    }

    private void initDubboConfigBeans() {
        // load DubboConfigBeanInitializer to init config beans
        /**
         * 这个BD是通过Enable注解注册进来的
         * @see org.apache.dubbo.config.spring.context.annotation.DubboConfigConfigurationRegistrar#registerBeanDefinitions(AnnotationMetadata, BeanDefinitionRegistry) 
         */
        if (applicationContext.containsBean(DubboConfigBeanInitializer.BEAN_NAME)) {
            applicationContext.getBean(DubboConfigBeanInitializer.BEAN_NAME, DubboConfigBeanInitializer.class);
        } else {
            logger.warn("Bean '" + DubboConfigBeanInitializer.BEAN_NAME + "' was not found");
        }

        // All infrastructure config beans are loaded, initialize dubbo here
        /**
         * 这个时候dubbo的配置都已经创建完成
         * 可以进行dubbo的初始化
         */
        DubboBootstrap.getInstance().initialize();
    }

    private void onApplicationContextEvent(ApplicationContextEvent event) {
        if (DubboBootstrapStartStopListenerSpringAdapter.applicationContext == null) {
            DubboBootstrapStartStopListenerSpringAdapter.applicationContext = event.getApplicationContext();
        }

        if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        } else if (event instanceof ContextClosedEvent) {
            onContextClosedEvent((ContextClosedEvent) event);
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {
        if (dubboBootstrap.getTakeoverMode() == BootstrapTakeoverMode.SPRING) {
            dubboBootstrap.start();
        }
    }

    private void onContextClosedEvent(ContextClosedEvent event) {
        if (dubboBootstrap.getTakeoverMode() == BootstrapTakeoverMode.SPRING) {
            // will call dubboBootstrap.stop() through shutdown callback.
            DubboShutdownHook.getDubboShutdownHook().run();
        }
    }

    /**
     * Is original {@link ApplicationContext} as the event source
     * @param event {@link ApplicationEvent}
     * @return if original, return <code>true</code>, or <code>false</code>
     */
    private boolean isOriginalEventSource(ApplicationEvent event) {

        boolean originalEventSource = nullSafeEquals(getApplicationContext(), event.getSource());
//        if (!originalEventSource) {
//            if (log.isDebugEnabled()) {
//                log.debug("The source of event[" + event.getSource() + "] is not original!");
//            }
//        }
        return originalEventSource;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;

        checkCallStackAndInit();
    }

    private void checkCallStackAndInit() {
        // check call stack whether contains org.springframework.context.support.AbstractApplicationContext.registerListeners()
        /**
         * 通过异常获取调用栈
         */
        Exception exception = new Exception();
        StackTraceElement[] stackTrace = exception.getStackTrace();
        boolean found = false;
        /**
         * 如果是通过
         * @see AbstractApplicationContext#registerListeners()
         * 调用的 说明需要发布早期事件 对于Dubbo而言是
         * @see DubboAnnotationInitedEvent
         * 在创建bean之前发布
         */
        for (StackTraceElement frame : stackTrace) {
            if (frame.getMethodName().equals("registerListeners") && frame.getClassName().endsWith("AbstractApplicationContext")) {
                found = true;
                break;
            }
        }
        if (found) {
            // init config beans here, compatible with spring 3.x/4.1.x
            /**
             * 初始化Dubbo的配置bean
             */
            initDubboConfigBeans();
        } else {
            logger.warn("DubboBootstrapApplicationListener initialization is unexpected, " +
                    "it should be created in AbstractApplicationContext.registerListeners() method", exception);
        }
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }
}
