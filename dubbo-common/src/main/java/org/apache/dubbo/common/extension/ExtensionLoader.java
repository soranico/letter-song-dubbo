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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Extension;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.NATIVE;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>(64);

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>(64);

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();
    /**
     * 存放非 Adaptive 和 Wrapper 的实现
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();
    /**
     * 缓存 @Activate 标记的实现
     */
    private final Map<String, Object> cachedActivates = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Set<String>> cachedActivateGroups = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String[]> cachedActivateValues = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /**
     *  通过Java的spi机制加载
     *  META-INF/services下的
     *  org.apache.dubbo.common.extension.LoadingStrategy配置
     *
     * 默认实现
     * @see org.apache.dubbo.common.extension.DubboInternalLoadingStrategy
     *
     * @see org.apache.dubbo.common.extension.DubboLoadingStrategy
     *
     * @see org.apache.dubbo.common.extension.ServicesLoadingStrategy
     */
    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private Set<String> unacceptableExceptions = new ConcurrentHashSet<>();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false)
            .sorted()
            .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    private ExtensionLoader(Class<?> type) {
        this.type = type;
        /**
         * 如果不是加载工厂，那么会通过spi
         * 获取adaptive的加载工厂
         * 默认没有adaptive实现会通过
         * 代理生成一个
         * @see org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory
         */
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        /**
         * 校验接口
         */
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        /**
         * 必须是SPI
         */
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }
        /**
         * 先从缓存中读取这个接口的扩展加载
         * 如果不存在的话就创建一个
         * @see ExtensionLoader
         * 这里不使用
         * @see ConcurrentHashMap#computeIfAbsent(Object, Function) 是因为有hash值的bug
         */
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });

        // TODO Improve extension loader, clear static refer extension instance.
        // Some extension instances may be referenced by static fields, if clear EXTENSION_INSTANCES may cause inconsistent.
        // e.g. org.apache.dubbo.registry.client.metadata.MetadataUtils.localMetadataService
        // EXTENSION_INSTANCES.clear();

        EXTENSION_LOADERS.clear();
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        /**
         * 首先从URL获取key对应的值
         */
        String value = url.getParameter(key);
        /**
         * 如果没有指定值的话那就是null
         * 如果值,分割那么久转为数组
         * @see ExtensionLoader#getActivateExtension(URL, String[], String) 
         */
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * 获取当前URL生效的类
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names 这个值是排除的
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        Map<Class<?>, T> activateExtensionsMap = new TreeMap<>(ActivateComparator.COMPARATOR);
        List<String> names = values == null ? new ArrayList<>(0) : asList(values);
        /**
         * 不包含 -default 值
         */
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            if (cachedActivateGroups.size() == 0) {
                synchronized (cachedActivateGroups) {
                    // cache all extensions
                    if (cachedActivateGroups.size() == 0) {
                        getExtensionClasses();
                        /**
                         * 遍历所有的 @Activate 标记类
                         */
                        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                            String name = entry.getKey();
                            Object activate = entry.getValue();

                            String[] activateGroup, activateValue;
                            /**
                             * 获取注解中的 分组 和 URL中需要存在的key
                             * @see Activate
                             */
                            if (activate instanceof Activate) {
                                activateGroup = ((Activate) activate).group();
                                activateValue = ((Activate) activate).value();
                            } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                                activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                                activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                            } else {
                                continue;
                            }
                            cachedActivateGroups.put(name, new HashSet<>(Arrays.asList(activateGroup)));
                            cachedActivateValues.put(name, activateValue);
                        }
                    }
                }
            }

            // traverse all cached extensions
            /**
             * 分组相同如果没有指定那就是匹配,如果指定那么需要存在注解中配置的分组里
             * 不在排除names(排除的扩展名)里面
             * 并且URL中存在这个扩展名对对应类注解的指定的key
             * 那么这个扩展器就会生效
             */
            cachedActivateGroups.forEach((name, activateGroup) -> {
                if (isMatchGroup(group, activateGroup)
                    && !names.contains(name)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)
                    && isActive(cachedActivateValues.get(name), url)) {

                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            });
        }
        /**
         * 如果扩展名里面包含 default
         */
        if (names.contains(DEFAULT_KEY)) {
            // will affect order
            // `ext1,default,ext2` means ext1 will happens before all of the default extensions while ext2 will after them
            ArrayList<T> extensionsResult = new ArrayList<>(activateExtensionsMap.size() + names.size());
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                /**
                 * 指定的扩展名不以 - 开头 并且 指定扩展名集合里不包含 -这个属性
                 * e.g
                 * 当前扩展名kano 则-kano也不能在里面
                 */
                if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                    /**
                     * 不是default扩展名 并且存在这个扩展 那么加入集合
                     */
                    if (!DEFAULT_KEY.equals(name)) {
                        if (containsExtension(name)) {
                            extensionsResult.add(getExtension(name));
                        }
                    } else {
                        extensionsResult.addAll(activateExtensionsMap.values());
                    }
                }
            }
            return extensionsResult;
        } else {
            // add extensions, will be sorted by its order
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                    if (!DEFAULT_KEY.equals(name)) {
                        if (containsExtension(name)) {
                            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                        }
                    }
                }
            }
            return new ArrayList<>(activateExtensionsMap.values());
        }
    }

    public List<T> getActivateExtensions() {
        List<T> activateExtensions = new ArrayList<>();
        TreeMap<Class<?>, T> activateExtensionsMap = new TreeMap<>(ActivateComparator.COMPARATOR);
        getExtensionClasses();
        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Object activate = entry.getValue();
            if (!(activate instanceof Activate)) {
                continue;
            }
            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
        }
        if (!activateExtensionsMap.isEmpty()) {
            activateExtensions.addAll(activateExtensionsMap.values());
        }

        return activateExtensions;
    }

    private boolean isMatchGroup(String group, Set<String> groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(groups)) {
            return groups.contains(group);
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            if (key.contains(":")) {
                String[] arr = key.split(":");
                key = arr[0];
                keyValue = arr[1];
            }

            String realValue = url.getParameter(key);
            if (StringUtils.isEmpty(realValue)) {
                realValue = url.getAnyMethodParameter(key);
            }
            if ((keyValue != null && keyValue.equals(realValue)) || (keyValue == null && ConfigUtils.isNotEmpty(realValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            /**
             * 利用ConcurrentHashMap的特性
             * 只会有一个操作成功
             * 因此get取到的就是第一个成功的
             */
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

//    public T getPrioritizedExtensionInstance() {
//        Set<String> supported = getSupportedExtensions();
//
//        Set<T> instances = new HashSet<>();
//        Set<T> prioritized = new HashSet<>();
//        for (String s : supported) {
//
//        }
//
//    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     *
     * 回去指定名字的扩展
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        /**
         * 获取实现并且需要被 Wrapper 代理
         * 如果name 是 true 那么就使用默认的
         */
        T extension = getExtension(name, true);
        if (extension == null) {
            throw new IllegalArgumentException("Not find extension: " + name);
        }
        return extension;
    }

    /**
     * 获取指定的扩展
     * 如果需要warp那么会对实例进行wrap操作
     */
    public T getExtension(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        /**
         * 如果是true则使用默认的
         */
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        final Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    /**
                     * 创建实例如果需要进行 wrap 那么会对对象进行封装
                     * @see ExtensionLoader#createExtension(String, boolean)
                     */
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        /**
         * 获取默认的指定实现
         * 如果配置为 true 那么会返回null
         *
         */
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        sort(instances, Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                    name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                    name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        /**
         * 获取具体的实现类
         * @see Adaptive 标记的
         * 如果没有的话那么会动态生成一个
         */
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                    createAdaptiveInstanceError.toString(),
                    createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        /**
                         * 创建一个 Adaptive实现类的对象,可能是动态代理生成的
                         * @see ExtensionLoader#createAdaptiveExtension()
                         */
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        /**
         * @see ExtensionLoader#cachedClasses
         * 这个里面放的是 非 Adaptive 和 Wrapper 的实现类
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                /**
                 * 创建实例缓存
                 * 全局缓存
                 */
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.getDeclaredConstructor().newInstance());
                /**
                 * 这个是原始的对象
                 */
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            /**
             * 依赖注入
             * 只会注入接口并且被@SPI标记的
             * @see ExtensionLoader#initExtension(Object)
             */
            injectExtension(instance);

            /**
             * 如果需要进行wrap 那么就会对实例进行进行 wrap 包装
             * 这也是为什么 Wrapper 需要一个和接口类型相同的构造方法
             */
            if (wrap) {

                List<Class<?>> wrapperClassesList = new ArrayList<>();
                /**
                 *
                 * 如果存在 Wrapper
                 * 那么需要应用
                 * 排序后逆序
                 * 从大到小
                 */
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    Collections.reverse(wrapperClassesList);
                }

                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        /**
                         * 没有被@Wrapper标记
                         * 或者
                         * 被标记但当前扩展可以匹配并且不在排除集合中
                         */
                        if (wrapper == null
                            || (ArrayUtils.contains(wrapper.matches(), name) && !ArrayUtils.contains(wrapper.mismatches(), name))) {
                            /**
                             * 将原始对象传入Wrapper对象中
                             * 并更新对象引用
                             * 此时会构成一个调用链
                             * 代理2 - 代理1 - 原始对象
                             */
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                        }
                    }
                }
            }
            /**
             * 如果实现了LifeCycle
             * 则会调用初始化方法
             * @see  Lifecycle#initialize()
             */
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * 进行属性的注入
     */
    private T injectExtension(T instance) {
        /**
         * 依赖注入工厂
         * 这个是在创建一个 ExtensionLoader 时通过
         * Java的spi机制去加载的，而dubbo的 spi
         * @see ExtensionLoader#ExtensionLoader(Class)
         * 默认有
         * @see org.apache.dubbo.common.extension.factory.SpiExtensionFactory
         * spi默认的
         * @see org.apache.dubbo.config.spring.extension.SpringExtensionFactory
         * 支持spring的
         *
         * @see org.apache.dubbo.common.extension.MyExtensionFactory
         * 不知道干啥的，已经废弃
         *
         */
        if (objectFactory == null) {
            return instance;
        }

        try {
            /**
             * 获取实例的方法
             */
            for (Method method : instance.getClass().getMethods()) {
                /**
                 * 是否为set方法
                 * 不是public 可以拿到？
                 */
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 *
                 * 被标记为禁止注入
                 *
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }
                Class<?> pt = method.getParameterTypes()[0];
                /**
                 * 不处理基本类型和几个特殊类型
                 *
                 * 对比spring
                 * spring在处理自动注入的写方法时，提供了不同的级别
                 * 查看 org.springframework.beans.factory.support.AbstractBeanDefinition
                 * 默认的会解析 String 基本类型
                 * 之所以spring可以解析这些数据
                 * 1.通过BD直接添加
                 * 2.通过注解标记读取配置
                 *
                 * TODO Dubbo无法解析这些数据是因为没有数据来源？
                 *
                 */
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    /**
                     * 获取属性
                     */
                    String property = getSetterProperty(method);
                    /**
                     *
                     *
                     * 获取被 SPI 标记接口的 Adaptive实现
                     * @see org.apache.dubbo.common.extension.factory.SpiExtensionFactory#getExtension(Class, String)
                     *
                     * 从spring容器中获取对应的bean
                     * @see org.apache.dubbo.config.spring.extension.SpringExtensionFactory#getExtension(java.lang.Class, java.lang.String)
                     */
                    Object object = objectFactory.getExtension(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                        + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
            && method.getParameterTypes().length == 1
            && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    /**
                     * 加载所有扩展
                     * @see ExtensionLoader#loadExtensionClasses()
                     *
                     */
                    classes = loadExtensionClasses();
                    /**
                     * 这个里面存放的是非 Adaptive Wrapper的实现
                     */
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     * 只会返回非 Adaptive 和 Wrapper 的实现
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        /**
         * 缓存默认的扩展的名字
         * @see ExtensionLoader#cacheDefaultExtensionName()
         */
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();
        /**
         *
         * 默认三个实现
         *
         * 用于加载 META-INF/dubbo/internal/
         * @see org.apache.dubbo.common.extension.DubboInternalLoadingStrategy
         *
         *
         * 加载 META-INF/dubbo/external/
         * @see org.apache.dubbo.common.extension.DubboLoadingStrategy
         *
         *
         * 加载 META-INF/services/
         * @see org.apache.dubbo.common.extension.ServicesLoadingStrategy
         *
         *
         * 这种设计可以动态扩展加载目录
         *
         * 从META-INF下的不用目录加载
         * @see ExtensionLoader#loadDirectory(Map, String, String, boolean, boolean, String...)
         */
        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        /**
         * 解析 spi 注解
         * @see SPI 标记的是默认的
         */
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }
        /**
         *
         * spi 只能存在一个
         * 里面配置的 会作为默认的
         * 实现来加载
         *
         */
        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                    + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false, false);
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type,
                               boolean extensionLoaderClassLoaderFirst, boolean overridden, String... excludedPackages) {
        /**
         * 文件名
         *
         */
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            /**
             * 首先会使用线程上下文的
             * 如果没有的话那就使用加载
             * @see ExtensionLoader 的类加载器
             */
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            /**
             * 先从加载 ExtensionLoader 这个类的类加载器进行加载
             * 这个一般都是false目前没有看到是true的
             */
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    /**
                     * 线程上下文类加载器加载
                     */
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    /**
                     *
                     * 这一步会加载 spi 配置文件里面配置的类
                     * 如果不是 Adaptive 和 Wrapper类
                     * 那么会放到 extensionClasses 这个Map里面
                     * @see ExtensionLoader#loadResource(Map, ClassLoader, java.net.URL, boolean, String...)
                     */
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden, String... excludedPackages) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                String clazz = null;
                while ((line = reader.readLine()) != null) {
                    // 处理注释
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                /**
                                 * 配置的名
                                 * name = className
                                 */
                                name = line.substring(0, i).trim();
                                clazz = line.substring(i + 1).trim();
                            } else {
                                clazz = line;
                            }
                            /**
                             * 类没有被排除则需要加载
                             */
                            if (StringUtils.isNotEmpty(clazz) && !isExcluded(clazz, excludedPackages)) {
                                /**
                                 * 加载就调用类初始化方法 cinit方法
                                 * @see ExtensionLoader#loadClass(Map, java.net.URL, Class, String, boolean)
                                 */
                                loadClass(extensionClasses, resourceURL, Class.forName(clazz, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                type + ", class line: " + clazz.getName() + "), class "
                + clazz.getName() + " is not subtype of interface.");
        }
        /**
         *
         * 缓存被 @Adaptive 标记的实现
         * 如果已经存在并且不允许重写的话那么会抛出异常
         * @see ExtensionLoader#cacheAdaptiveClass(Class, boolean)
         */
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        }
        /**
         *
         * 如果是 wrapper 实现
         * wrapper实现必须存在一个传入实现类的引用的
         * 构造方法
         * @see ExtensionLoader#cacheWrapperClass(Class)
         */
        else if (isWrapperClass(clazz)) {
            // 缓存wrapper
            cacheWrapperClass(clazz);
        } else {
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                /**
                 * 获取配置类的名字
                 */
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                /**
                 * 缓存 Activate 标记的 类
                 * @see Activate
                 * 并且只会使用第一个名字
                 * @see ExtensionLoader#cachedActivates 属性中
                 */
                cacheActivateClass(clazz, names[0]);
                /**
                 * 缓存所有类
                 * @see ExtensionLoader#cachedNames
                 */
                for (String n : names) {
                    cacheName(clazz, n);
                    /**
                     * 缓存到 extensionClasses 也就是外部传入的那个
                     */
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        /**
         * 存入或者重写
         */
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                + cachedAdaptiveClass.getName()
                + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            /**
             * 有一个以type为参数的构造方法
             */
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        /**
         * 获取类上注解的指定名
         * @see Extension#value()
         */
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }
        /**
         *
         * 获取类名,如果类名以接口名为结尾
         * 去掉接口名
         *
         */
        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            /**
             *
             * 1.先通过spi来生成不同配置下的代理实现
             * 默认为 javassist
             *
             * 2.注入属性,默认只注入 Object？
             * 而且必须是被 @SPI 标记的接口
             * @see ExtensionLoader#injectExtension(Object) 
             *
             * @see ExtensionLoader#getAdaptiveExtensionClass() 此时会返回
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        /**
         * 这一步会加载在配置文件中的实现
         * @see ExtensionLoader#getExtensionClasses()
         */
        getExtensionClasses();
        /**
         * 如果这个时候没有的话说明无法从配置文件里面加载到
         * 那么这个时候就需要去动态生成一个了
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        /**
         * 获取或者动态生成 Adaptive 的实现类
         * @see ExtensionLoader#createAdaptiveExtensionClass()
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 动态生成一个Adaptive实现类
     */
    private Class<?> createAdaptiveExtensionClass() {
        ClassLoader classLoader = findClassLoader();
        try {
            /**
             *
             * 是否需要本地加载
             * 默认的为 接口$Adaptive
             * 此前可能已经生成了
             * 先尝试加载，失败的话再去动态生成一个
             */
            if (ApplicationModel.getEnvironment().getConfiguration().getBoolean(NATIVE, false)) {
                return classLoader.loadClass(type.getName() + "$Adaptive");
            }
        } catch (Throwable ignore) {

        }
        /**
         * 生成代理
         * @see AdaptiveClassCodeGenerator#generate()
         */
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        System.out.println("type = "+type.getName());
        System.out.println(code);
        /**
         *
         * 编译器默认会有个 Adaptive的实现
         * 因为此时走到这里表明需要代理生成
         * 如果编译器也需要去代理则造成了
         * 递归死循环
         *
         * 默认实现
         * @see org.apache.dubbo.common.compiler.support.AdaptiveCompiler
         *
         */
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
