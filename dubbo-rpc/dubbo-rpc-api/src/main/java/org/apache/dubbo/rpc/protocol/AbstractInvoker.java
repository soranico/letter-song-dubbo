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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.Node;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.PenetrateAttachmentSelector;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_SERIALIZATION;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_ID_KEY;

/**
 * This Invoker works on Consumer side.
 */
public abstract class AbstractInvoker<T> implements Invoker<T> {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractInvoker.class);

    /**
     * Service interface type
     */
    private final Class<T> type;

    /**
     * {@link Node} url
     */
    private final URL url;

    /**
     * {@link Invoker} default attachment
     */
    private final Map<String, Object> attachment;

    /**
     * {@link Node} available
     */
    private volatile boolean available = true;

    /**
     * {@link Node} destroy
     */
    private boolean destroyed = false;

    // -- Constructor

    public AbstractInvoker(Class<T> type, URL url) {
        this(type, url, (Map<String, Object>) null);
    }

    public AbstractInvoker(Class<T> type, URL url, String[] keys) {
        this(type, url, convertAttachment(url, keys));
    }

    public AbstractInvoker(Class<T> type, URL url, Map<String, Object> attachment) {
        if (type == null) {
            throw new IllegalArgumentException("service type == null");
        }
        if (url == null) {
            throw new IllegalArgumentException("service url == null");
        }
        this.type = type;
        this.url = url;
        this.attachment = attachment == null
                ? null
                : Collections.unmodifiableMap(attachment);
    }

    private static Map<String, Object> convertAttachment(URL url, String[] keys) {
        if (ArrayUtils.isEmpty(keys)) {
            return null;
        }
        Map<String, Object> attachment = new HashMap<>();
        for (String key : keys) {
            String value = url.getParameter(key);
            if (value != null && value.length() > 0) {
                attachment.put(key, value);
            }
        }
        return attachment;
    }

    // -- Public api

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void destroy() {
        this.destroyed = true;
        setAvailable(false);
    }

    protected void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? "" : getUrl().getAddress());
    }

    @Override
    public Result invoke(Invocation inv) throws RpcException {
        // if invoker is destroyed due to address refresh from registry, let's allow the current invoke to proceed
        if (isDestroyed()) {
            logger.warn("Invoker for service " + this + " on consumer " + NetUtils.getLocalHost() + " is destroyed, "
                    + ", dubbo version is " + Version.getVersion() + ", this invoker should not be used any longer");
        }

        RpcInvocation invocation = (RpcInvocation) inv;

        // prepare rpc invocation
        /**
         * 请求前会设置 当前请求的方式 同步 或异步 默认同步
         * 以及请求使用的 序列化的方式 默认 hessian2
         */
        prepareInvocation(invocation);

        // do invoke rpc invocation and return async result
        /**
         * 此时是使用线程池发送了一个异步请求
         * @see AbstractInvoker#doInvokeAndReturn(RpcInvocation)
         */
        AsyncRpcResult asyncResult = doInvokeAndReturn(invocation);

        // wait rpc result if sync
        /**
         * 如果是同步的那么就需要阻塞等待执行结果
         * 这里设置的是 int 最大值
         * 但是不会阻塞那么久因为在发送之前
         * 会设置一个定时任务
         * @see DefaultFuture#newFuture(org.apache.dubbo.remoting.Channel, org.apache.dubbo.remoting.exchange.Request, int, java.util.concurrent.ExecutorService) 同步则阻塞等待响应
         */
        waitForResultIfSync(asyncResult, invocation);

        return asyncResult;
    }

    private void prepareInvocation(RpcInvocation inv) {
        inv.setInvoker(this);

        addInvocationAttachments(inv);

        /**
         * 调用模式,Future 同步 异步
         */
        inv.setInvokeMode(RpcUtils.getInvokeMode(url, inv));

        RpcUtils.attachInvocationIdIfAsync(getUrl(), inv);

        /**
         * 序列化的方式,默认是 hessian2
         */
        Byte serializationId = CodecSupport.getIDByName(getUrl().getParameter(SERIALIZATION_KEY, DEFAULT_REMOTING_SERIALIZATION));
        if (serializationId != null) {
            inv.put(SERIALIZATION_ID_KEY, serializationId);
        }
    }

    private void addInvocationAttachments(RpcInvocation invocation) {
        // invoker attachment
        if (CollectionUtils.isNotEmptyMap(attachment)) {
            invocation.addObjectAttachmentsIfAbsent(attachment);
        }

        // client context attachment
        Map<String, Object> clientContextAttachments = RpcContext.getClientAttachment().getObjectAttachments();
        if (CollectionUtils.isNotEmptyMap(clientContextAttachments)) {
            invocation.addObjectAttachmentsIfAbsent(clientContextAttachments);
        }

        // server context attachment
        ExtensionLoader<PenetrateAttachmentSelector> selectorExtensionLoader = ExtensionLoader.getExtensionLoader(PenetrateAttachmentSelector.class);
        Set<String> supportedSelectors = selectorExtensionLoader.getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedSelectors)) {
            // custom context attachment
            for (String supportedSelector : supportedSelectors) {
                Map<String, Object> selected = selectorExtensionLoader.getExtension(supportedSelector).select();
                if (CollectionUtils.isNotEmptyMap(selected)) {
                    invocation.addObjectAttachmentsIfAbsent(selected);
                }
            }
        } else {
            Map<String, Object> serverContextAttachments = RpcContext.getServerAttachment().getObjectAttachments();
            invocation.addObjectAttachmentsIfAbsent(serverContextAttachments);
        }
    }

    private AsyncRpcResult doInvokeAndReturn(RpcInvocation invocation) {
        AsyncRpcResult asyncResult;
        try {
            /**
             * 调用子类去发送请求,对于不同的协议此时的请求实现必然不同
             * @see org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker#doInvoke(org.apache.dubbo.rpc.Invocation)
             */
            asyncResult = (AsyncRpcResult) doInvoke(invocation);
        } catch (InvocationTargetException e) {
            Throwable te = e.getTargetException();
            if (te != null) {
                // if biz exception
                if (te instanceof RpcException) {
                    ((RpcException) te).setCode(RpcException.BIZ_EXCEPTION);
                }
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, te, invocation);
            } else {
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
            }
        } catch (RpcException e) {
            // if biz exception
            if (e.isBiz()) {
                asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
            } else {
                throw e;
            }
        } catch (Throwable e) {
            asyncResult = AsyncRpcResult.newDefaultAsyncResult(null, e, invocation);
        }

        // set server context
        /**
         * 设置到TL
         * @see RpcContext#SERVICE_CONTEXT
         */
        RpcContext.getServiceContext().setFuture(new FutureAdapter<>(asyncResult.getResponseFuture()));

        return asyncResult;
    }

    private void waitForResultIfSync(AsyncRpcResult asyncResult, RpcInvocation invocation) {
        if (InvokeMode.SYNC != invocation.getInvokeMode()) {
            return;
        }
        try {
            /*
             * NOTICE!
             * must call {@link java.util.concurrent.CompletableFuture#get(long, TimeUnit)} because
             * {@link java.util.concurrent.CompletableFuture#get()} was proved to have serious performance drop.
             */
            /**
             * TODO get()有性能问题 ?
             * @see AsyncRpcResult#get(long, TimeUnit) 阻塞获取结果
             */
            asyncResult.get(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RpcException("Interrupted unexpectedly while waiting for remote result to return! method: " +
                    invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            Throwable rootCause = e.getCause();
            if (rootCause instanceof TimeoutException) {
                throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            } else if (rootCause instanceof RemotingException) {
                throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            } else {
                throw new RpcException(RpcException.UNKNOWN_EXCEPTION, "Fail to invoke remote method: " +
                        invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            }
        } catch (Throwable e) {
            throw new RpcException(e.getMessage(), e);
        }
    }

    // -- Protected api

    protected ExecutorService getCallbackExecutor(URL url, Invocation inv) {

        /**
         * @see org.apache.dubbo.common.threadpool.manager.DefaultExecutorRepository#getExecutor(URL) 
         */
        ExecutorService sharedExecutor = ExtensionLoader.getExtensionLoader(ExecutorRepository.class)
                .getDefaultExtension()
                .getExecutor(url);
        /**
         * 如果是同步请求那么就
         */
        if (InvokeMode.SYNC == RpcUtils.getInvokeMode(getUrl(), inv)) {
            return new ThreadlessExecutor(sharedExecutor);
        } else {
            return sharedExecutor;
        }
    }

    /**
     * Specific implementation of the {@link #invoke(Invocation)} method
     */
    protected abstract Result doInvoke(Invocation invocation) throws Throwable;
}
